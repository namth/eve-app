import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  TextInput,
  StatusBar,
  ScrollView,
  Modal,
  Alert,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Switch,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { EVEAvatarWebView } from './src/components/EVEAvatarWebView';
import { StatusBadge } from './src/components/StatusBadge';
import { ControlPanel } from './src/components/ControlPanel';
import { ProfileSettingsModal } from './src/components/ProfileSettingsModal';
import { useEVEState } from './src/hooks/useEVEState';
import { n8nService } from './src/services/n8nService';
import { audioService } from './src/services/audioService';
import { notificationService } from './src/services/notificationService';
import { sttService } from './src/services/sttService';
import { notificationStorage } from './src/services/notificationStorage';
import { peopleDatabaseService } from './src/services/peopleDatabaseService';
import { enrollmentService } from './src/services/enrollmentService';
import { faceRecognitionService } from './src/services/faceRecognitionService';
import welcomeGreetings from './src/assets/data/welcome_greetings.json';
import { ChatWebhookResponse, PushNotificationPayload } from './src/types/api';
import { PersonProfile } from './src/types/personProfile';

const getRandomWelcomeGreeting = (): string => {
  if (Array.isArray(welcomeGreetings) && welcomeGreetings.length > 0) {
    const idx = Math.floor(Math.random() * welcomeGreetings.length);
    return welcomeGreetings[idx];
  }
  return 'Xin chào, tôi là Eve, trợ lý của công ty INOVA. Tôi có thể giúp gì cho bạn hôm nay?';
};

export default function App() {
  const [isRecording, setIsRecording] = useState(false);
  const isRecordingRef = useRef(false);
  isRecordingRef.current = isRecording;

  const isFidgetBlocked = useCallback(() => isRecordingRef.current, []);
  const { expression, setExpression, handleCanvasTap, isWakingUp } = useEVEState(isFidgetBlocked);

  const [inputMessage, setInputMessage] = useState('');
  const [lastReplyText, setLastReplyText] = useState<string | null>(null);
  const [currentSessionId, setCurrentSessionId] = useState<string | undefined>();
  const [pendingConfirm, setPendingConfirm] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [showControlUI, setShowControlUI] = useState<boolean>(false);
  const [activeNotificationQueue, setActiveNotificationQueue] = useState<PushNotificationPayload[]>([]);

  // User Profile & Recognition State
  const [currentPerson, setCurrentPerson] = useState<PersonProfile | null>(null);
  const currentPersonRef = useRef<PersonProfile | null>(null);
  currentPersonRef.current = currentPerson;

  const [showProfileModal, setShowProfileModal] = useState(false);

  // Enrollment State for Unknown Person
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [enrollPredictedGender, setEnrollPredictedGender] = useState<'male' | 'female' | 'unknown'>('male');
  const [enrollFaceVector, setEnrollFaceVector] = useState<number[] | undefined>();

  // Hands-Free Mode State (Default: True for natural duplex conversation)
  const [isHandsFreeMode, setIsHandsFreeMode] = useState<boolean>(true);
  const isHandsFreeModeRef = useRef<boolean>(true);
  isHandsFreeModeRef.current = isHandsFreeMode;

  // Settings Modal State
  const [showSettings, setShowSettings] = useState(false);
  const [pushToken, setPushToken] = useState<string | null>(null);
  const [n8nWebhookUrl, setN8nWebhookUrl] = useState(
    process.env.EXPO_PUBLIC_N8N_WEBHOOK_URL || 'https://ai.oa.io.vn/webhook/eve-chat'
  );

  // Idle Timer (2 minutes = 120,000 ms)
  const idleTimeoutRef = useRef<any>(null);

  const resetIdleTimer = useCallback(() => {
    if (idleTimeoutRef.current) clearTimeout(idleTimeoutRef.current);
    idleTimeoutRef.current = setTimeout(() => {
      console.log('[App] 2-minute idle timeout reached. Pausing Hands-Free VAD to save battery...');
      setIsRecording(false);
      audioService.stopRecording();
      setExpression('sleeping');
    }, 120000); // 2 phút
  }, [setExpression]);

  // Finish recording ref to avoid closure ordering issues
  const finishRecordingRef = useRef<() => Promise<void>>(async () => {});

  /**
   * Bắt đầu ghi âm với Real-Time VAD (Tự động ngắt sau 800ms im lặng & hỗ trợ Smart Barge-In)
   */
  const startVADListening = useCallback(async () => {
    resetIdleTimer();
    setIsRecording(true);
    const success = await audioService.startRecording({
      silenceThresholdMs: 800,
      onSpeechEnd: () => {
        console.log('[App] VAD 800ms silence auto-stop triggered!');
        if (finishRecordingRef.current) {
          finishRecordingRef.current();
        }
      },
      onBargeIn: () => {
        console.log('[App] Smart Barge-in triggered! Halting TTS playback...');
        setExpression('thinking');
      },
      checkIsLookingAtEVE: () => {
        // Kiểm tra hướng nhìn tức thời
        return faceRecognitionService.checkIsLookingAtEVE({ yaw: 0, pitch: 0, roll: 0 }, 25, 25);
      },
    });
    if (!success) {
      setIsRecording(false);
    }
  }, [resetIdleTimer, setExpression]);

  /**
   * Xử lý và phát âm thanh cho hàng đợi thông báo:
   * BẢO MẬT: CHỈ PHÁT ĐỌC THÔNG BÁO KHI CURRENT PERSON LÀ ADMIN
   */
  const processNotificationQueue = useCallback(
    async (
      customQueue?: PushNotificationPayload[],
      overridePerson?: PersonProfile | null
    ): Promise<boolean> => {
      const activePerson = overridePerson !== undefined ? overridePerson : currentPersonRef.current;
      console.log('[App] Processing notification queue. Active Person Role:', activePerson?.role);

      // QUY TẮC BẢO MẬT: Chỉ phát đọc thông báo nếu người dùng có role ADMIN
      if (!activePerson || activePerson.role !== 'admin') {
        console.log('[App] Security restriction: Current user is NOT ADMIN. Keeping notifications stored safely.');
        return false;
      }

      const queue = customQueue || (await notificationStorage.getNotificationQueue());
      console.log('[App] Processing notification queue for ADMIN:', queue?.length);

      if (!queue || queue.length === 0) return false;

      setActiveNotificationQueue(queue);

      while (isWakingUp) {
        await new Promise((res) => setTimeout(res, 200));
      }

      const { speechText, targetEmotion } =
        notificationStorage.formatNaturalSpeech(queue, activePerson);

      setLastReplyText(speechText);
      setExpression('speaking');

      return new Promise<boolean>((resolve) => {
        audioService
          .playTTS(speechText, undefined, async () => {
            console.log('[App] EVE completely finished reading queued notifications to Admin!');
            setExpression(targetEmotion);
            await notificationStorage.clearNotificationQueue();
            setTimeout(() => setExpression('idle'), 2000);
            resolve(true);
          })
          .catch(async (err) => {
            console.error('[App] Error during notification TTS playback:', err);
            setExpression('idle');
            resolve(false);
          });
      });
    },
    [isWakingUp, setExpression]
  );

  // Guard against React 18 Strict Mode double scan on mount
  // Guard against React 18 Strict Mode double scan on mount
  const hasScannedRef = useRef(false);
  // Ref lưu ID người đã được EVE chào mừng thành công (Tránh lặp lại câu chào)
  const greetedPersonIdRef = useRef<string | null>(null);



  /**
   * Lắng nghe khi có 1 Push Notification mới đến
   */
  const handleIncomingSpeech = useCallback(
    async (payload: PushNotificationPayload) => {
      console.log('[App] Received incoming push payload:', payload);
      const currentQueue = await notificationStorage.addNotificationToQueue(payload);
      await processNotificationQueue(currentQueue);
    },
    [processNotificationQueue]
  );

  /**
   * Kiểm tra và tự động phát thông báo chưa đọc từ đĩa cứng khi vừa vào App
   */
  const checkAndPlayPendingNotification = useCallback(async () => {
    const queue = await notificationStorage.getNotificationQueue();
    if (queue && queue.length > 0) {
      console.log('[App] Found pending notification queue in storage, count:', queue.length);
      setActiveNotificationQueue(queue);
      if (currentPersonRef.current?.role === 'admin') {
        setTimeout(() => {
          processNotificationQueue(queue);
        }, 800);
      }
    }
  }, [processNotificationQueue]);



  /**
   * Khởi tạo App: Đăng ký Push Token, Lắng nghe thông báo & Chạy Nhận diện Ban đầu
   */
  useEffect(() => {
    if (hasScannedRef.current) return;
    hasScannedRef.current = true;

    notificationService.registerForPushNotifications().then((token) => {
      if (token) setPushToken(token);
    });

    // 0. Xin quyền Camera từ HĐH (iOS / Android)
    faceRecognitionService.requestCameraPermission();

    // 1. Lắng nghe Foreground Notification
    const foregroundSub = notificationService.addForegroundNotificationListener(
      (payload) => {
        handleIncomingSpeech(payload);
      }
    );

    // 2. Lắng nghe Background Notification Tapped
    const backgroundSub = notificationService.addNotificationResponseListener(
      (payload) => {
        handleIncomingSpeech(payload);
      }
    );

    // 3. Khởi tạo App: Luôn bắt đầu ở chế độ Guest (Chưa xác định danh tính)
    peopleDatabaseService.getPeopleList().then(async () => {
      // Mới vào app -> chưa xác định là ai (Guest Mode)
      setCurrentPerson(null);
      await peopleDatabaseService.saveCurrentUser(null);

      // Luồng Welcome: Phát 1 câu chào ngẫu nhiên giới thiệu Eve & INOVA ở dạng Guest
      const greetingText = getRandomWelcomeGreeting();
      console.log('[App] Welcome Flow (Guest Mode): Playing random greeting:', greetingText);
      setLastReplyText(greetingText);
      setExpression('speaking');

      await audioService.playTTS(greetingText, undefined, () => {
        setExpression('idle');
        if (isHandsFreeModeRef.current) {
          console.log('[App] Welcome greeting completed! Starting Hands-Free VAD listening as Guest...');
          startVADListening();
        }
      });
    });

    return () => {
      foregroundSub.remove();
      backgroundSub.remove();
    };
  }, [handleIncomingSpeech, processNotificationQueue, checkAndPlayPendingNotification, startVADListening, setExpression]);

  /**
   * Gửi câu lệnh bằng văn bản lên n8n Webhook
   */
  const handleSendMessage = async (customMessage?: string) => {
    const msgToSend = (customMessage || inputMessage).trim();
    if (!msgToSend) return;

    if (!customMessage) setInputMessage('');
    setIsProcessing(true);
    setExpression('thinking');

    try {
      console.log('[App] Sending Pure Voice Chat message to n8n Webhook with current_person metadata...');

      // Gửi câu thoại đính kèm current_person metadata (image_base64: null)
      const response: ChatWebhookResponse = await n8nService.sendChatMessage(
        msgToSend,
        currentSessionId,
        currentPerson
      );

      console.log('[App] n8n Response:', response);
      setLastReplyText(response.reply_text);
      setCurrentSessionId(response.session_id);
      setPendingConfirm(!!response.require_confirm);

      // TỰ ĐỘNG CẬP NHẬT / THÊM MỚI / ACTIVE NGƯỜI DÙNG TỪ N8N RESPONSE (KÈM DIFF CHECK)
      const incomingPerson = response.person || response.update_person;
      if (incomingPerson && incomingPerson.name) {
        const { person: syncedPerson, isUpdated } = await peopleDatabaseService.syncPersonFromResponse(
          incomingPerson,
          currentPerson
        );
        setCurrentPerson(syncedPerson);
        if (isUpdated) {
          console.log('[App] Profile synced & saved for:', syncedPerson.name, 'Role:', syncedPerson.role);
        } else {
          console.log('[App] Activated person profile for:', syncedPerson.name, 'Role:', syncedPerson.role);
        }

        // NẾU ROLE CỦA NGƯỜI VỪA ACTIVE LÀ ADMIN -> TIẾN HÀNH LUỒNG CŨ: ĐỌC NOTIFICATIONS (NẾU CÓ)
        if (syncedPerson.role === 'admin') {
          console.log('[App] Active person is ADMIN! Checking pending notifications...');
          processNotificationQueue(undefined, syncedPerson);
        }
      }

      const targetEmotion = response.emotion || 'happy';
      setExpression('speaking');

      await audioService.playTTS(response.reply_text, response.audio_url, () => {
        setExpression(targetEmotion);
        setTimeout(() => {
          setExpression('idle');
          // NẾU ĐANG Ở CHẾ ĐỘ HANDS-FREE: TỰ ĐỘNG BẬT LẠI MIC VAD LẮNG NGHE CÂU TIẾP THEO
          if (isHandsFreeModeRef.current) {
            console.log('[App] Auto Re-Listen Loop: Hands-Free VAD restarted!');
            startVADListening();
          }
        }, 1000);
      });
    } catch (error: any) {
      console.error('[App] Error sending message to n8n:', error);
      const errMsg = error?.message || 'Không thể kết nối tới n8n server.';
      setLastReplyText(`Lỗi kết nối: ${errMsg}`);
      setExpression('idle');
    } finally {
      setIsProcessing(false);
    }
  };

  /**
   * Xử lý 1-chạm Bật/Tắt Ghi Âm Micro với Hands-Free VAD
   */
  const handleMicToggle = async () => {
    if (isRecording) {
      await finishRecordingAndSend();
    } else {
      await startVADListening();
    }
  };

  const finishRecordingAndSend = async () => {
    setIsRecording(false);
    const audioUri = await audioService.stopRecording();
    console.log('[App] Recorded audio URI:', audioUri);

    if (!audioService.wasSpeechDetected) {
      console.log('[App] No speech detected above -26dB threshold. Skipping STT request.');
      setExpression('idle');
      if (isHandsFreeModeRef.current) {
        console.log('[App] Auto Re-Listen Loop restarted after silent VAD auto-stop.');
        startVADListening();
      }
      return;
    }

    if (audioUri) {
      handleSendAudio(audioUri);
    } else {
      Alert.alert('Lỗi', 'Không thu được âm thanh. Vui lòng thử lại.');
    }
  };
  finishRecordingRef.current = finishRecordingAndSend;

  /**
   * Xử lý âm thanh STT và phân nhánh luồng Chat hoặc luồng Đăng ký Người mới (Enrollment)
   */
  const handleSendAudio = async (audioUri: string) => {
    setIsProcessing(true);
    setExpression('thinking');

    try {
      const transcribedText = await sttService.transcribeAudio(audioUri);
      console.log('[App] Transcribed Text:', transcribedText);

      if (!transcribedText) {
        setLastReplyText('Không nhận diện được câu nói. Vui lòng thử lại.');
        setExpression('idle');
        setIsProcessing(false);
        return;
      }

      // LUỒNG ĐĂNG KÝ NGƯỜI MỚI (ENROLLMENT WORKFLOW)
      if (isEnrolling) {
        setIsEnrolling(false);
        setExpression('thinking');

        const enrollRes = await enrollmentService.enrollNewPerson(
          transcribedText,
          enrollPredictedGender,
          enrollFaceVector
        );

        setCurrentPerson(enrollRes.person);
        setLastReplyText(enrollRes.replyText);
        setExpression('happy');

        await audioService.playTTS(enrollRes.replyText, enrollRes.audioUrl, () => {
          setExpression('idle');
        });
        setIsProcessing(false);
        return;
      }

      // LUỒNG VOICE CHAT PURE N8N: Gửi văn bản STT trực tiếp lên n8n Webhook
      await handleSendMessage(transcribedText);
    } catch (error: any) {
      console.error('[App] Error processing STT audio:', error);
      const errMsg = error?.message || 'Không thể chuyển đổi giọng nói thành văn bản.';
      setLastReplyText(`Lỗi STT: ${errMsg}`);
      setExpression('idle');
      setIsProcessing(false);
    }
  };

  /**
   * Bật / Tạm dừng chế độ VAD đàm thoại tự động (Hands-Free)
   */
  const handleTogglePauseVAD = () => {
    if (isHandsFreeMode) {
      console.log('[App] User manually PAUSED Hands-Free VAD listening.');
      setIsHandsFreeMode(false);
      if (isRecording) {
        audioService.stopRecording();
        setIsRecording(false);
      }
    } else {
      console.log('[App] User manually RESUMED Hands-Free VAD listening.');
      setIsHandsFreeMode(true);
      startVADListening();
    }
  };

  const handleConfirmChoice = (confirmed: boolean) => {
    const choiceText = confirmed ? 'Đồng ý' : 'Hủy';
    setPendingConfirm(false);
    handleSendMessage(choiceText);
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="light-content" backgroundColor="#030712" />

        {/* Header Bar */}
        <View style={styles.header}>
          <View style={styles.headerTitleContainer}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <Text style={styles.headerTitle}>EVE Voice AI</Text>
              {currentPerson && (
                <TouchableOpacity
                  style={[
                    styles.roleBadgeHeader,
                    currentPerson.role === 'admin' ? styles.roleBadgeAdmin : styles.roleBadgeFriend,
                  ]}
                  onPress={() => setShowProfileModal(true)}
                >
                  <Text style={styles.roleBadgeHeaderText}>
                    {currentPerson.role === 'admin' ? '⚡ ADMIN' : '👤 FRIEND'}: {currentPerson.name}
                  </Text>
                </TouchableOpacity>
              )}
            </View>
            <Text style={styles.headerSubtitle}>n8n Agent Brain</Text>
          </View>

          <View style={{ flexDirection: 'row', gap: 8 }}>
            <TouchableOpacity
              style={styles.profileQuickBtn}
              onPress={() => setShowProfileModal(true)}
            >
              <Text style={styles.settingsIcon}>👤</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.settingsBtn}
              onPress={() => setShowSettings(true)}
            >
              <Text style={styles.settingsIcon}>⚙️</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Top Status Badge */}
        <View style={styles.topStatusContainer}>
          <StatusBadge expression={expression} isListening={isRecording} />
        </View>

        {/* Floating Control Panel */}
        {showControlUI && (
          <ControlPanel
            currentExpression={expression}
            onSelectExpression={setExpression}
            onOpenSettings={() => setShowSettings(true)}
          />
        )}

        <ScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
        >
          {/* EVE Robot Avatar WebView Canvas */}
          <TouchableOpacity
            activeOpacity={1}
            onPress={handleCanvasTap}
            style={styles.avatarWrapper}
          >
            <EVEAvatarWebView
              expression={expression}
              onTapCanvas={handleCanvasTap}
            />
          </TouchableOpacity>

          {/* Active Push Notification Card Banner (Hiển thị cho Admin) */}
          {activeNotificationQueue && activeNotificationQueue.length > 0 && currentPerson?.role === 'admin' && (
            <View style={styles.notifCard}>
              <View style={styles.notifCardHeader}>
                <Text style={styles.notifCardTitle}>
                  🔔 {activeNotificationQueue.length > 1 ? `${activeNotificationQueue.length} Thông báo mới` : (activeNotificationQueue[0].title || 'Thông báo mới')}
                </Text>
                <TouchableOpacity
                  onPress={() => setActiveNotificationQueue([])}
                  style={styles.notifCloseBtn}
                  hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                >
                  <Text style={styles.notifCloseText}>✕</Text>
                </TouchableOpacity>
              </View>

              {activeNotificationQueue.length === 1 ? (
                <Text style={styles.notifCardBody}>
                  "{activeNotificationQueue[0].text ||
                    activeNotificationQueue[0].body ||
                    activeNotificationQueue[0].message ||
                    'Có thông báo mới từ hệ thống.'}"
                </Text>
              ) : (
                <View style={styles.notifListContainer}>
                  {activeNotificationQueue.map((item, index) => (
                    <Text key={index} style={styles.notifListItem}>
                      <Text style={styles.notifItemNumber}>{index + 1}.</Text> {item.text || item.body || item.message}
                    </Text>
                  ))}
                </View>
              )}

              <TouchableOpacity
                style={styles.notifReplayBtn}
                onPress={() => processNotificationQueue(activeNotificationQueue)}
                activeOpacity={0.8}
              >
                <Text style={styles.notifReplayIcon}>🔊</Text>
                <Text style={styles.notifReplayText}>
                  Nghe lại báo cáo ({activeNotificationQueue.length} thông báo)
                </Text>
              </TouchableOpacity>
            </View>
          )}

          {/* Reply Bubble Box */}
          {lastReplyText && activeNotificationQueue.length === 0 && (
            <View style={styles.replyBox}>
              <Text style={styles.replyLabel}>EVE nói:</Text>
              <Text style={styles.replyText}>"{lastReplyText}"</Text>

              {/* Lệnh Cấp 2 - Nút Xác nhận Đồng ý / Hủy */}
              {pendingConfirm && (
                <View style={styles.confirmBox}>
                  <Text style={styles.confirmTitle}>
                    ⚡ Yêu cầu xác nhận lệnh Cấp 2:
                  </Text>
                  <View style={styles.confirmButtons}>
                    <TouchableOpacity
                      style={[styles.confirmBtn, styles.confirmBtnYes]}
                      onPress={() => handleConfirmChoice(true)}
                    >
                      <Text style={styles.confirmBtnText}>✓ Đồng ý</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                      style={[styles.confirmBtn, styles.confirmBtnNo]}
                      onPress={() => handleConfirmChoice(false)}
                    >
                      <Text style={styles.confirmBtnText}>✕ Hủy bỏ</Text>
                    </TouchableOpacity>
                  </View>
                </View>
              )}
            </View>
          )}
        </ScrollView>

        {/* Voice & Input Interaction Footer */}
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          style={styles.footer}
        >
          <View style={styles.inputContainer}>
            <TextInput
              style={styles.input}
              placeholder="Nói hoặc nhập câu lệnh..."
              placeholderTextColor="#64748b"
              value={inputMessage}
              onChangeText={setInputMessage}
              onSubmitEditing={() => handleSendMessage()}
            />

            <TouchableOpacity
              style={[styles.sendBtn, !inputMessage.trim() && styles.sendBtnDisabled]}
              disabled={!inputMessage.trim() || isProcessing}
              onPress={() => handleSendMessage()}
            >
              {isProcessing ? (
                <ActivityIndicator color="#ffffff" size="small" />
              ) : (
                <Text style={styles.sendBtnText}>➔</Text>
              )}
            </TouchableOpacity>
          </View>

          {/* Microphone Row */}
          <View style={styles.micRowContainer}>
            {/* Nút Tạm dừng VAD nhỏ nhắn ở phía bên trái (Chỉ hiện khi đang lắng nghe) */}
            {isRecording && (
              <TouchableOpacity
                style={styles.smallStopBtn}
                onPress={handleTogglePauseVAD}
                activeOpacity={0.75}
              >
                <Text style={styles.smallStopIcon}>⏹️ Dừng VAD</Text>
              </TouchableOpacity>
            )}

            <TouchableOpacity
              style={[
                styles.micBtn,
                isRecording && styles.micBtnRecording,
                isRecording && { flex: 1 },
              ]}
              onPress={handleMicToggle}
              activeOpacity={0.8}
            >
              <Text style={styles.micIcon}>{isRecording ? '🔴' : '🎤'}</Text>
              <Text style={styles.micLabel}>
                {isRecording ? 'Đang nghe... (Chạm để gửi)' : 'Chạm để nói với EVE'}
              </Text>
            </TouchableOpacity>
          </View>
        </KeyboardAvoidingView>

        {/* Profile Settings Modal */}
        <ProfileSettingsModal
          visible={showProfileModal}
          onClose={() => setShowProfileModal(false)}
          currentPerson={currentPerson}
          onSelectPerson={(person) => {
            setCurrentPerson(person);
            if (person.role === 'admin') {
              processNotificationQueue(undefined, person);
            }
          }}
        />

        {/* Modal Settings General */}
        <Modal visible={showSettings} animationType="slide" transparent>
          <View style={styles.modalOverlay}>
            <View style={styles.modalContent}>
              <Text style={styles.modalTitle}>⚙️ Cài đặt n8n & Thiết bị</Text>

              <View style={styles.settingRow}>
                <Text style={styles.modalLabel}>Hiển thị thanh trạng thái & công cụ:</Text>
                <Switch
                  value={showControlUI}
                  onValueChange={setShowControlUI}
                  trackColor={{ false: '#334155', true: '#0ea5e9' }}
                  thumbColor={showControlUI ? '#38bdf8' : '#94a3b8'}
                />
              </View>

              <Text style={styles.modalLabel}>URL Webhook Chat n8n:</Text>
              <TextInput
                style={styles.modalInput}
                value={n8nWebhookUrl}
                onChangeText={setN8nWebhookUrl}
                placeholder="https://your-n8n-instance.com/webhook/chat"
                placeholderTextColor="#64748b"
              />

              <Text style={styles.modalLabel}>Push Token của Thiết bị (Dùng cho n8n):</Text>
              <View style={styles.tokenBox}>
                <Text style={styles.tokenText} selectable={true}>
                  {pushToken || 'Đang lấy Push Token từ HĐH...'}
                </Text>
              </View>

              <View style={styles.tokenActionsRow}>
                <TouchableOpacity
                  style={styles.retryTokenBtn}
                  onPress={() => {
                    setPushToken('Đang xin quyền và lấy Token...');
                    notificationService.registerForPushNotifications().then((t) => setPushToken(t));
                  }}
                >
                  <Text style={styles.retryTokenText}>🔄 Lấy lại Push Token</Text>
                </TouchableOpacity>

                {pushToken && !pushToken.includes('Lỗi') && !pushToken.includes('Expo Go') && (
                  <TouchableOpacity
                    style={styles.copyTokenBtn}
                    onPress={() => {
                      Alert.alert('Đã lấy Push Token', pushToken);
                    }}
                  >
                    <Text style={styles.copyTokenText}>📋 Xem / Copy Token</Text>
                  </TouchableOpacity>
                )}
              </View>

              <TouchableOpacity
                style={styles.testNotifBtn}
                onPress={() => {
                  setShowSettings(false);
                  handleIncomingSpeech({
                    action: 'speak_notification',
                    title: 'Thông báo n8n đơn',
                    text: 'Sếp vừa phê duyệt hợp đồng dự án A.',
                    emotion: 'happy',
                  });
                }}
              >
                <Text style={styles.testNotifText}>🔔 Thử nhận 1 thông báo</Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[styles.testNotifBtn, { backgroundColor: 'rgba(16, 185, 129, 0.15)', borderColor: '#10b981' }]}
                onPress={async () => {
                  setShowSettings(false);
                  const sampleQueue: PushNotificationPayload[] = [
                    { action: 'speak_notification', text: 'Doanh thu hôm nay đạt mốc 100 triệu đồng', emotion: 'happy' },
                    { action: 'speak_notification', text: 'Hệ thống server vừa được nâng cấp băng thông', emotion: 'smile' },
                    { action: 'speak_notification', text: 'Bạn có 1 lịch họp mới vào lúc 3 giờ chiều', emotion: 'happy' },
                  ];
                  for (const item of sampleQueue) {
                    await notificationStorage.addNotificationToQueue(item);
                  }
                  processNotificationQueue();
                }}
              >
                <Text style={[styles.testNotifText, { color: '#34d399' }]}>🚀 Thử nhận 3 thông báo liên tiếp</Text>
              </TouchableOpacity>

              <View style={styles.modalButtons}>
                <TouchableOpacity
                  style={styles.modalSaveBtn}
                  onPress={() => setShowSettings(false)}
                >
                  <Text style={styles.modalSaveText}>Đóng & Lưu</Text>
                </TouchableOpacity>
              </View>
            </View>
          </View>
        </Modal>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#030712',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(30, 41, 59, 0.6)',
  },
  headerTitleContainer: {
    flexDirection: 'column',
  },
  headerTitle: {
    color: '#f8fafc',
    fontSize: 17,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  roleBadgeHeader: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
  },
  roleBadgeAdmin: {
    backgroundColor: '#00f0ff22',
    borderColor: '#00f0ff',
    borderWidth: 1,
  },
  roleBadgeFriend: {
    backgroundColor: '#ffbb0022',
    borderColor: '#ffbb00',
    borderWidth: 1,
  },
  roleBadgeHeaderText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: 'bold',
  },
  headerSubtitle: {
    color: '#0ea5e9',
    fontSize: 11,
    fontWeight: '600',
  },
  profileQuickBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: 'rgba(0, 240, 255, 0.15)',
    borderColor: '#00f0ff66',
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  settingsBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#1e293b',
    alignItems: 'center',
    justifyContent: 'center',
  },
  settingsIcon: {
    fontSize: 16,
  },
  topStatusContainer: {
    paddingTop: 10,
    paddingBottom: 4,
    alignItems: 'center',
    zIndex: 10,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingBottom: 20,
    width: '100%',
  },
  avatarWrapper: {
    alignItems: 'center',
    justifyContent: 'center',
    width: '100%',
    height: 440,
    overflow: 'visible',
    marginVertical: 0,
  },
  replyBox: {
    backgroundColor: 'rgba(15, 23, 42, 0.9)',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.3)',
    borderRadius: 16,
    padding: 16,
    marginHorizontal: 20,
    marginTop: 10,
    maxWidth: 360,
    width: '90%',
  },
  replyLabel: {
    color: '#38bdf8',
    fontSize: 12,
    fontWeight: '700',
    marginBottom: 4,
  },
  replyText: {
    color: '#f8fafc',
    fontSize: 15,
    lineHeight: 22,
    fontWeight: '500',
  },
  confirmBox: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: 'rgba(51, 65, 85, 0.5)',
  },
  confirmTitle: {
    color: '#fbbf24',
    fontSize: 13,
    fontWeight: '700',
    marginBottom: 8,
  },
  confirmButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 10,
  },
  confirmBtn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 10,
  },
  confirmBtnYes: {
    backgroundColor: '#10b981',
  },
  confirmBtnNo: {
    backgroundColor: '#ef4444',
  },
  confirmBtnText: {
    color: '#ffffff',
    fontSize: 13,
    fontWeight: '700',
  },
  footer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#0b0f19',
    borderTopWidth: 1,
    borderTopColor: 'rgba(30, 41, 59, 0.6)',
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  input: {
    flex: 1,
    backgroundColor: '#1e293b',
    color: '#f8fafc',
    borderRadius: 24,
    paddingHorizontal: 16,
    paddingVertical: 10,
    fontSize: 14,
    borderWidth: 1,
    borderColor: 'rgba(51, 65, 85, 0.6)',
  },
  sendBtn: {
    backgroundColor: '#0ea5e9',
    width: 42,
    height: 42,
    borderRadius: 21,
    justifyContent: 'center',
    alignItems: 'center',
    marginLeft: 8,
  },
  sendBtnDisabled: {
    backgroundColor: '#334155',
    opacity: 0.6,
  },
  sendBtnText: {
    color: '#ffffff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  micRowContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 8,
  },
  smallStopBtn: {
    backgroundColor: 'rgba(244, 63, 94, 0.2)',
    borderColor: '#f43f5e',
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
  },
  smallStopIcon: {
    color: '#f43f5e',
    fontSize: 12,
    fontWeight: 'bold',
  },
  micBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(14, 165, 233, 0.2)',
    borderWidth: 1,
    borderColor: '#0ea5e9',
    borderRadius: 24,
    paddingVertical: 12,
  },
  micBtnRecording: {
    backgroundColor: 'rgba(239, 68, 68, 0.25)',
    borderColor: '#ef4444',
  },
  micIcon: {
    fontSize: 18,
    marginRight: 8,
  },
  micLabel: {
    color: '#f8fafc',
    fontSize: 14,
    fontWeight: '700',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.8)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  modalContent: {
    backgroundColor: '#0f172a',
    borderRadius: 20,
    padding: 24,
    width: '100%',
    maxWidth: 360,
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.3)',
  },
  modalTitle: {
    color: '#f8fafc',
    fontSize: 18,
    fontWeight: '800',
    marginBottom: 16,
  },
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  modalLabel: {
    color: '#94a3b8',
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 6,
  },
  modalInput: {
    backgroundColor: '#1e293b',
    color: '#f8fafc',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 13,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  tokenBox: {
    backgroundColor: '#1e293b',
    borderRadius: 10,
    padding: 10,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#334155',
  },
  tokenText: {
    color: '#38bdf8',
    fontSize: 11,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  tokenActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  retryTokenBtn: {
    paddingVertical: 4,
  },
  retryTokenText: {
    color: '#0ea5e9',
    fontSize: 12,
    fontWeight: '600',
  },
  copyTokenBtn: {
    backgroundColor: 'rgba(56, 189, 248, 0.15)',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.4)',
  },
  copyTokenText: {
    color: '#38bdf8',
    fontSize: 12,
    fontWeight: '700',
  },
  testNotifBtn: {
    backgroundColor: 'rgba(14, 165, 233, 0.15)',
    borderColor: '#0ea5e9',
    borderWidth: 1,
    borderRadius: 12,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginBottom: 20,
    alignItems: 'center',
  },
  testNotifText: {
    color: '#38bdf8',
    fontSize: 13,
    fontWeight: '700',
  },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
  },
  modalSaveBtn: {
    backgroundColor: '#0ea5e9',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 10,
  },
  modalSaveText: {
    color: '#ffffff',
    fontWeight: '700',
    fontSize: 14,
  },
  notifCard: {
    backgroundColor: 'rgba(15, 23, 42, 0.95)',
    borderWidth: 1.5,
    borderColor: '#00f0ff',
    borderRadius: 18,
    padding: 16,
    marginHorizontal: 20,
    marginTop: 10,
    maxWidth: 360,
    width: '90%',
    shadowColor: '#00f0ff',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 10,
    elevation: 8,
  },
  notifCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  notifCardTitle: {
    color: '#00f0ff',
    fontSize: 13,
    fontWeight: '800',
    letterSpacing: 0.3,
  },
  notifCloseBtn: {
    padding: 4,
  },
  notifCloseText: {
    color: '#94a3b8',
    fontSize: 16,
    fontWeight: 'bold',
  },
  notifCardBody: {
    color: '#f8fafc',
    fontSize: 15,
    lineHeight: 22,
    fontWeight: '600',
    marginBottom: 14,
  },
  notifListContainer: {
    marginBottom: 14,
    gap: 6,
  },
  notifListItem: {
    color: '#f8fafc',
    fontSize: 14,
    lineHeight: 20,
    fontWeight: '500',
  },
  notifItemNumber: {
    color: '#38bdf8',
    fontWeight: '700',
  },
  notifReplayBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0, 240, 255, 0.15)',
    borderWidth: 1,
    borderColor: '#00f0ff',
    borderRadius: 12,
    paddingVertical: 10,
    paddingHorizontal: 14,
  },
  notifReplayIcon: {
    fontSize: 16,
    marginRight: 8,
  },
  notifReplayText: {
    color: '#00f0ff',
    fontSize: 13,
    fontWeight: '800',
  },
});

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
import { useEVEState } from './src/hooks/useEVEState';
import { n8nService } from './src/services/n8nService';
import { audioService } from './src/services/audioService';
import { notificationService } from './src/services/notificationService';
import { sttService } from './src/services/sttService';
import { notificationStorage } from './src/services/notificationStorage';
import { ChatWebhookResponse, PushNotificationPayload } from './src/types/api';

export default function App() {
  const { expression, setExpression, handleCanvasTap, isWakingUp } = useEVEState();

  const [inputMessage, setInputMessage] = useState('');
  const [lastReplyText, setLastReplyText] = useState<string | null>(null);
  const [currentSessionId, setCurrentSessionId] = useState<string | undefined>();
  const [pendingConfirm, setPendingConfirm] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [showControlUI, setShowControlUI] = useState<boolean>(false);
  const [activeNotificationQueue, setActiveNotificationQueue] = useState<PushNotificationPayload[]>([]);

  // Settings Modal State
  const [showSettings, setShowSettings] = useState(false);
  const [pushToken, setPushToken] = useState<string | null>(null);
  const [n8nWebhookUrl, setN8nWebhookUrl] = useState(
    process.env.EXPO_PUBLIC_N8N_WEBHOOK_URL || 'https://ai.oa.io.vn/webhook/eve-chat'
  );

  /**
   * Xử lý và phát âm thanh cho hàng đợi thông báo (Dù có 1 hay nhiều thông báo):
   * - Tạo câu mở đầu ngẫu nhiên tự nhiên (Có tính số lượng N)
   * - Đọc trực tiếp nếu có 1 thông báo, hoặc đọc kiểu liệt kê "Một là... Hai là..." nếu có nhiều thông báo
   * - Tạo câu kết thúc ngẫu nhiên ("Hết ạ", "Anh có chỉ thị gì không ạ?").
   */
  const processNotificationQueue = useCallback(
    async (customQueue?: PushNotificationPayload[]): Promise<boolean> => {
      const queue = customQueue || (await notificationStorage.getNotificationQueue());
      console.log('[App] Processing notification queue:', queue);

      if (!queue || queue.length === 0) return false;

      // Cập nhật giao diện UI hiển thị thẻ danh sách thông báo
      setActiveNotificationQueue(queue);

      // 1. Cơ chế chờ: Nếu EVE đang trong quá trình WakeUp / Khởi tạo, chờ cho đến khi hoàn tất
      while (isWakingUp) {
        await new Promise((res) => setTimeout(res, 200));
      }

      // Format câu thoại tự nhiên chuẩn tiếng Việt với Mở đầu -> Đếm N -> Liệt kê 1, 2... -> Kết thúc
      const { speechText, displayTitle, targetEmotion } =
        notificationStorage.formatNaturalSpeech(queue);

      setLastReplyText(speechText);
      setExpression('speaking');

      // 2. Cơ chế chờ hoàn thành: EVE phát hết toàn bộ câu thoại trước khi kết thúc nhiệm vụ
      return new Promise<boolean>((resolve) => {
        audioService
          .playTTS(speechText, undefined, async () => {
            console.log('[App] EVE completely finished reading all queued notifications!');
            setExpression(targetEmotion);
            // Xóa khỏi hàng đợi sau khi đã báo cáo xong 100%
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
      setTimeout(() => {
        processNotificationQueue(queue);
      }, 800);
    }
  }, [processNotificationQueue]);

  /**
   * Đăng ký Push Notification và lắng nghe tin nhắn thời gian thực khi đang mở/tắt App
   */
  useEffect(() => {
    notificationService.registerForPushNotifications().then((token) => {
      if (token) setPushToken(token);
    });

    // 1. Lắng nghe khi đang MỞ APP (Foreground)
    const foregroundSub = notificationService.addForegroundNotificationListener(
      (payload) => {
        console.log('[App] Received message while App OPEN (Foreground):', payload);
        handleIncomingSpeech(payload);
      }
    );

    // 2. Lắng nghe khi BẤM VÀO NOTIFICATION TỪ NGOÀI APP (Background / Lockscreen)
    const backgroundSub = notificationService.addNotificationResponseListener(
      (payload) => {
        console.log('[App] Clicked notification from Background/Lockscreen:', payload);
        handleIncomingSpeech(payload);
      }
    );

    // 3. Xử lý Cold Start & Quá trình khởi tạo App: Đọc thông báo còn chờ từ đĩa cứng
    notificationService.getInitialNotification().then((initialPayload) => {
      if (initialPayload) {
        console.log('[App] Cold Start notification detected:', initialPayload);
        notificationStorage.addNotificationToQueue(initialPayload).then((updatedQueue) => {
          processNotificationQueue(updatedQueue);
        });
      } else {
        checkAndPlayPendingNotification();
      }
    });

    return () => {
      foregroundSub.remove();
      backgroundSub.remove();
    };
  }, [handleIncomingSpeech, processNotificationQueue, checkAndPlayPendingNotification]);

  /**
   * Gửi câu lệnh bằng văn bản (hoặc đã chuyển từ STT) lên n8n Webhook
   */
  const handleSendMessage = async (customMessage?: string) => {
    const msgToSend = (customMessage || inputMessage).trim();
    if (!msgToSend) return;

    if (!customMessage) setInputMessage('');
    setIsProcessing(true);
    setExpression('thinking');

    try {
      const response: ChatWebhookResponse = await n8nService.sendChatMessage(
        msgToSend,
        currentSessionId
      );

      console.log('[App] n8n Response:', response);
      setLastReplyText(response.reply_text);
      setCurrentSessionId(response.session_id);
      setPendingConfirm(!!response.require_confirm);

      // Cập nhật biểu cảm cảm xúc EVE theo response
      const targetEmotion = response.emotion || 'happy';
      setExpression('speaking');

      // Phát âm thanh câu trả lời từ n8n (audio_url từ ElevenLabs / OpenAI hoặc Google Stream)
      await audioService.playTTS(response.reply_text, response.audio_url, () => {
        setExpression(targetEmotion);
        // Sau 2s luôn về idle (fidget sẽ tự chạy lại)
        setTimeout(() => setExpression('idle'), 2000);
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
   * Xử lý 1-chạm để Bật/Tắt Ghi Âm nói với EVE
   */
  const autoStopTimerRef = useRef<any>(null);

  const handleMicToggle = async () => {
    if (isRecording) {
      await finishRecordingAndSend();
    } else {
      setIsRecording(true);
      const success = await audioService.startRecording();
      if (!success) {
        setIsRecording(false);
        Alert.alert('Cảnh báo', 'Không thể khởi tạo Microphone.');
        return;
      }

      if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current);
      autoStopTimerRef.current = setTimeout(() => {
        console.log('[App] Auto-stop triggered after 8s silence');
        finishRecordingAndSend();
      }, 8000);
    }
  };

  const finishRecordingAndSend = async () => {
    if (autoStopTimerRef.current) {
      clearTimeout(autoStopTimerRef.current);
      autoStopTimerRef.current = null;
    }
    setIsRecording(false);
    const audioUri = await audioService.stopRecording();
    console.log('[App] Recorded audio URI:', audioUri);
    console.log('[App] Groq API Key present:', !!process.env.EXPO_PUBLIC_GROQ_API_KEY);

    if (audioUri) {
      console.log('[App] Sending audio to STT + n8n...');
      handleSendAudio(audioUri);
    } else {
      console.warn('[App] No audio URI returned from recording!');
      Alert.alert('Lỗi', 'Không thu được âm thanh. Vui lòng thử lại.');
    }
  };

  /**
   * Phương Án 2: Chuyển đổi giọng nói thu từ Microphone thành văn bản Tiếng Việt qua Groq Whisper LPU
   * Sau đó CHỈ gửi duy nhất văn bản "message" dạng JSON tinh gọn tới n8n Webhook
   */
  const handleSendAudio = async (audioUri: string) => {
    setIsProcessing(true);
    setExpression('thinking');

    try {
      // 1. Chuyển đổi giọng nói thu từ Micro thành văn bản Tiếng Việt qua Groq Whisper LPU (~100ms)
      const transcribedText = await sttService.transcribeAudio(audioUri);
      console.log('[App] Groq Transcribed Text:', transcribedText);

      if (transcribedText) {
        // 2. CHỈ GỬI VĂN BẢN THẬT VÀO N8N WEBHOOK (Phương Án 2)
        await handleSendMessage(transcribedText);
      } else {
        setLastReplyText('Không thể nhận diện được giọng nói. Vui lòng thử lại.');
        setExpression('idle');
        setIsProcessing(false);
      }
    } catch (error: any) {
      console.error('[App] Error processing audio STT:', error);
      const errMsg = error?.message || 'Không thể chuyển đổi giọng nói thành văn bản.';
      setLastReplyText(`Lỗi STT: ${errMsg}`);
      setExpression('idle');
      setIsProcessing(false);
    }
  };

  /**
   * Xử lý nút Xác nhận "Đồng ý" hoặc "Hủy" cho Lệnh Cấp 2
   */
  const handleConfirmChoice = (confirmed: boolean) => {
    const choiceText = confirmed ? 'Đồng ý' : 'Hủy';
    setPendingConfirm(false);
    handleSendMessage(choiceText);
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="light-content" backgroundColor="#030712" />

        {/* Floating Control Panel (Nút Biểu Cảm Nổi ở Cạnh Phải - Chỉ hiển thị khi bật Cài đặt) */}
        {showControlUI && (
          <ControlPanel
            currentExpression={expression}
            onSelectExpression={(exp) => setExpression(exp)}
          />
        )}

        {/* Header Bar */}
        <View style={styles.header}>
          <View style={styles.headerTitleContainer}>
            <Text style={styles.headerTitle}>EVE Voice AI</Text>
            <Text style={styles.headerSubtitle}>n8n Agent Brain</Text>
          </View>
          <TouchableOpacity
            style={styles.settingsBtn}
            onPress={() => setShowSettings(true)}
          >
            <Text style={styles.settingsIcon}>⚙️</Text>
          </TouchableOpacity>
        </View>

        {/* Top Status Badge - Chỉ hiển thị khi bật trong Cài đặt */}
        {showControlUI && (
          <View style={styles.topStatusContainer}>
            <StatusBadge expression={expression} isListening={isRecording} />
          </View>
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

          {/* Active Push Notification Card Banner (Hàng đợi thông báo) */}
          {activeNotificationQueue && activeNotificationQueue.length > 0 && (
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

          {/* Microphone 1-Tap Button */}
          <TouchableOpacity
            style={[styles.micBtn, isRecording && styles.micBtnRecording]}
            onPress={handleMicToggle}
            activeOpacity={0.8}
          >
            <Text style={styles.micIcon}>{isRecording ? '🔴' : '🎤'}</Text>
            <Text style={styles.micLabel}>
              {isRecording ? 'Đang nghe... (Chạm để gửi)' : 'Chạm để nói với EVE'}
            </Text>
          </TouchableOpacity>
        </KeyboardAvoidingView>

        {/* Modal Settings */}
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
                    <Text style={styles.copyTokenText}>📋 Xem / Coppy Token</Text>
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
                <Text style={[styles.testNotifText, { color: '#34d399' }]}>🚀 Thử nhận 3 thông báo liên tiếp (Tự nhiên)</Text>
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
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(30, 41, 59, 0.6)',
  },
  headerTitleContainer: {
    flexDirection: 'column',
  },
  headerTitle: {
    color: '#f8fafc',
    fontSize: 18,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  headerSubtitle: {
    color: '#0ea5e9',
    fontSize: 11,
    fontWeight: '600',
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
  micBtn: {
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

  /* Notification Card UI & Replay Speech Button Styles */
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

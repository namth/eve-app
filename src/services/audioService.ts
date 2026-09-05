import {
  createAudioPlayer,
  setAudioModeAsync,
  requestRecordingPermissionsAsync,
  AudioModule,
  RecordingPresets,
  type AudioPlayer,
  type AudioRecorder,
} from 'expo-audio';
import * as Speech from 'expo-speech';
import { PermissionsAndroid, Platform } from 'react-native';
import { ttsNormalizerService } from './ttsNormalizerService';

class AudioService {
  private audioPlayer: AudioPlayer | null = null;
  private audioRecorder: AudioRecorder | null = null;
  private recordingInterval: any = null;
  private isPlayingTTSStatus: boolean = false;
  private speechDetectedFlag: boolean = false;

  constructor() {
    setAudioModeAsync({
      allowsRecording: true,
      playsInSilentMode: true,
      interruptionMode: 'duckOthers',
    }).catch((err) => console.log('[AudioService] Init audio mode error:', err));
  }

  public get isPlayingTTS(): boolean {
    return this.isPlayingTTSStatus;
  }

  public get wasSpeechDetected(): boolean {
    return this.speechDetectedFlag;
  }

  /**
   * Phát âm thanh TTS (từ URL n8n / Edge-TTS / Native)
   * Tự động chuẩn hóa địa chỉ website/domain thành giọng đọc Tiếng Việt tự nhiên
   */
  async playTTS(text: string, audioUrl?: string, onEnd?: () => void): Promise<void> {
    try {
      await this.stopAudio();
      this.isPlayingTTSStatus = true;

      // Chuẩn hóa URL/Domain (ví dụ hoangskitchenhoian.com -> hoàng s kitchen hội an chấm com)
      const speechText = await ttsNormalizerService.normalizeForTTS(text);

      const handlePlaybackEnd = () => {
        this.isPlayingTTSStatus = false;
        try {
          this.audioPlayer?.remove();
        } catch (e) {}
        this.audioPlayer = null;
        if (onEnd) onEnd();
      };

      // 1. Ưu tiên 1: Audio URL từ n8n (ElevenLabs / OpenAI / Edge-TTS)
      if (audioUrl) {
        console.log('[AudioService] Playing audio stream from n8n URL:', audioUrl);
        const player = createAudioPlayer({ uri: audioUrl });
        this.audioPlayer = player;

        player.addListener('playbackStatusUpdate', (status) => {
          if (status.didJustFinish) {
            handlePlaybackEnd();
          }
        });
        player.play();
        return;
      }

      // 2. Ưu tiên 2: Phát qua Edge-TTS / Stream Proxy
      const encodedText = encodeURIComponent(speechText);
      const edgeTtsProxy = process.env.EXPO_PUBLIC_EDGE_TTS_URL;
      const ttsStreamUrl = edgeTtsProxy
        ? `${edgeTtsProxy}?text=${encodedText}&voice=vi-VN-HoaiMyNeural`
        : `https://translate.google.com/translate_tts?ie=UTF-8&q=${encodedText}&tl=vi&client=tw-ob`;

      console.log('[AudioService] Playing AI Voice Stream for speechText:', speechText);
      const player = createAudioPlayer({ uri: ttsStreamUrl });
      this.audioPlayer = player;
      try {
        player.setPlaybackRate(1.15);
        player.shouldCorrectPitch = true;
      } catch (e) {}

      player.addListener('playbackStatusUpdate', (status) => {
        if (status.didJustFinish) {
          handlePlaybackEnd();
        }
      });
      player.play();
    } catch (error) {
      console.warn('[AudioService] Neural stream error, falling back to Native TTS:', error);
      const speechText = await ttsNormalizerService.normalizeForTTS(text);
      this.isPlayingTTSStatus = true;
      Speech.speak(speechText, {
        language: 'vi-VN',
        pitch: 1.05,
        rate: 1.15,
        onDone: () => {
          this.isPlayingTTSStatus = false;
          if (onEnd) onEnd();
        },
        onError: () => {
          this.isPlayingTTSStatus = false;
          if (onEnd) onEnd();
        },
      });
    }
  }

  /**
   * Dừng âm thanh đang phát (cho tính năng Barge-in)
   */
  async stopAudio(): Promise<void> {
    this.isPlayingTTSStatus = false;
    Speech.stop();
    if (this.audioPlayer) {
      try {
        this.audioPlayer.pause();
        this.audioPlayer.remove();
      } catch (e) {}
      this.audioPlayer = null;
    }
  }

  /**
   * Bắt đầu ghi âm với Real-Time VAD Metering (800ms silence threshold & Barge-in)
   */
  async startRecording(options?: {
    silenceThresholdMs?: number;
    onSpeechEnd?: () => void;
    onBargeIn?: () => void;
    checkIsLookingAtEVE?: () => boolean;
  }): Promise<boolean> {
    try {
      if (this.recordingInterval) {
        clearInterval(this.recordingInterval);
        this.recordingInterval = null;
      }
      if (this.audioRecorder) {
        try {
          await this.audioRecorder.stop();
        } catch (e) {}
        this.audioRecorder = null;
      }

      const permission = await requestRecordingPermissionsAsync();
      if (Platform.OS === 'android') {
        try {
          await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
        } catch (e) {}
      }

      if (!permission.granted) {
        console.warn('[AudioService] Microphone permission not granted');
        return false;
      }

      await setAudioModeAsync({
        allowsRecording: true,
        playsInSilentMode: true,
        interruptionMode: 'duckOthers',
      });

      // Cấu hình ghi âm bật Metering đo âm lượng dB thời gian thực
      const recordingOptions = {
        ...RecordingPresets.HIGH_QUALITY,
        isMeteringEnabled: true,
      };

      const recorder: AudioRecorder = new AudioModule.AudioRecorder(recordingOptions);
      await recorder.prepareToRecordAsync();
      recorder.record();

      this.audioRecorder = recorder;
      this.speechDetectedFlag = false;

      const silenceThreshold = options?.silenceThresholdMs || 800; // 800ms
      let hasStartedSpeaking = false;
      let silenceStartTime: number | null = null;
      let speechStartedTime: number | null = null;
      let isTriggeredEnd = false;

      // Cân chỉnh tiếng ồn nền tự động (Adaptive Dynamic Noise Floor)
      const recordingStartTime = Date.now();
      let isCalibrated = false;
      let samplingSum = 0;
      let samplingCount = 0;
      let speechStartThreshold = -32; // Ngưỡng mặc định bắt đầu nói
      let silenceEndThreshold = -42;  // Ngưỡng mặc định ngắt im lặng

      this.recordingInterval = setInterval(() => {
        if (!recorder.isRecording || isTriggeredEnd) return;

        const status = recorder.getStatus();
        const metering = status.metering ?? -160; // dB value

        // 1. ĐO TIẾNG ỒN NỀN TRONG 300MS ĐẦU TIÊN
        const now = Date.now();
        if (!isCalibrated) {
          if (now - recordingStartTime <= 300) {
            if (metering > -160) {
              samplingSum += metering;
              samplingCount++;
            }
            return;
          } else {
            isCalibrated = true;
            const ambientNoise = samplingCount > 0 ? samplingSum / samplingCount : -50;
            // Tính toán ngưỡng động phù hợp với môi trường thực tế
            speechStartThreshold = Math.max(Math.round(ambientNoise + 10), -32);
            silenceEndThreshold = Math.max(Math.round(ambientNoise + 4), -40);
            console.log(`[AudioService] Adaptive VAD Calibrated: Ambient=${Math.round(ambientNoise)}dB | SpeechStart=${speechStartThreshold}dB | SilenceEnd=${silenceEndThreshold}dB`);
          }
        }

        // 2. CẮT NGANG THÔNG MINH (Smart Barge-In): Ngắt TTS khi nói vượt ngưỡng ồn động & Đang nhìn EVE
        if (this.isPlayingTTSStatus && metering > speechStartThreshold) {
          const isLooking = options?.checkIsLookingAtEVE ? options.checkIsLookingAtEVE() : true;
          if (isLooking) {
            console.log(`[AudioService] Smart Barge-in triggered! (Volume ${metering}dB > ${speechStartThreshold}dB AND Looking at EVE). Halting TTS...`);
            this.stopAudio();
            if (options?.onBargeIn) options.onBargeIn();
            return;
          } else {
            console.log(`[AudioService] Noise ${metering}dB detected during TTS, but user is NOT looking at EVE. Ignoring Barge-in.`);
          }
        }

        // 3. VAD: Bắt đầu phát hiện tiếng nói người dùng
        if (metering > speechStartThreshold) {
          if (!hasStartedSpeaking) {
            console.log(`[AudioService] VAD: Speech started (Metering: ${metering}dB > Threshold: ${speechStartThreshold}dB)`);
            hasStartedSpeaking = true;
            speechStartedTime = now;
            this.speechDetectedFlag = true;
          }
          silenceStartTime = null; // Reset bộ đếm im lặng khi đang có âm thanh giọng nói
        } else if (hasStartedSpeaking && metering < silenceEndThreshold) {
          // 4. VAD: Phát hiện im lặng sau khi đã nói (khi âm lượng xuống dưới ngưỡng im lặng động)
          if (!silenceStartTime) {
            silenceStartTime = now;
          } else {
            const silenceDuration = now - silenceStartTime;
            if (silenceDuration >= silenceThreshold) {
              console.log(`[AudioService] VAD: Speech end detected after ${silenceDuration}ms silence! Auto-stopping...`);
              isTriggeredEnd = true;
              if (this.recordingInterval) {
                clearInterval(this.recordingInterval);
                this.recordingInterval = null;
              }
              if (options?.onSpeechEnd) {
                options.onSpeechEnd();
              }
            }
          }
        }

        // 5. MAX SPEECH HARD TIMEOUT (8 GIÂY TOÀN BỘ CÂU NÓI):
        if (hasStartedSpeaking && speechStartedTime && !isTriggeredEnd) {
          const speechDuration = now - speechStartedTime;
          if (speechDuration >= 8000) {
            console.log(`[AudioService] VAD: Max speech duration reached (${speechDuration}ms >= 8000ms). Auto-stopping for STT...`);
            isTriggeredEnd = true;
            if (this.recordingInterval) {
              clearInterval(this.recordingInterval);
              this.recordingInterval = null;
            }
            if (options?.onSpeechEnd) {
              options.onSpeechEnd();
            }
          }
        }
      }, 100);

      return true;
    } catch (err) {
      console.error('[AudioService] Start recording error:', err);
      if (this.recordingInterval) {
        clearInterval(this.recordingInterval);
        this.recordingInterval = null;
      }
      if (this.audioRecorder) {
        try {
          await this.audioRecorder.stop();
        } catch (e) {}
        this.audioRecorder = null;
      }
      return false;
    }
  }

  /**
   * Dừng ghi âm và trả về URI
   */
  async stopRecording(): Promise<string | null> {
    if (this.recordingInterval) {
      clearInterval(this.recordingInterval);
      this.recordingInterval = null;
    }
    if (!this.audioRecorder) return null;
    try {
      await this.audioRecorder.stop();
      const uri = this.audioRecorder.uri;
      this.audioRecorder = null;
      return uri;
    } catch (err) {
      console.error('[AudioService] Stop recording error:', err);
      this.audioRecorder = null;
      return null;
    }
  }
}

export const audioService = new AudioService();


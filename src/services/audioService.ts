import { Audio } from 'expo-av';
import * as Speech from 'expo-speech';

class AudioService {
  private soundObject: Audio.Sound | null = null;
  private recordingObject: Audio.Recording | null = null;

  constructor() {
    Audio.setAudioModeAsync({
      allowsRecordingIOS: true,
      playsInSilentModeIOS: true,
      shouldDuckAndroid: true,
      playThroughEarpieceAndroid: false,
    }).catch((err) => console.log('[AudioService] Init audio mode error:', err));
  }

  /**
   * Phát âm thanh từ URL (ví dụ ElevenLabs / OpenAI TTS audio file trả về từ n8n)
   * Nếu không có URL hoặc lỗi -> Dùng Expo Native Speech làm fallback
   */
  async playTTS(text: string, audioUrl?: string, onEnd?: () => void): Promise<void> {
    try {
      await this.stopAudio();

      // 1. Ưu tiên 1: Audio URL từ n8n (ElevenLabs / OpenAI / Edge-TTS)
      if (audioUrl) {
        console.log('[AudioService] Playing audio stream from n8n URL:', audioUrl);
        const { sound } = await Audio.Sound.createAsync(
          { uri: audioUrl },
          { shouldPlay: true }
        );
        this.soundObject = sound;

        sound.setOnPlaybackStatusUpdate((status) => {
          if (status.isLoaded && status.didJustFinish) {
            this.soundObject?.unloadAsync();
            this.soundObject = null;
            if (onEnd) onEnd();
          }
        });
        return;
      }

      // 2. Ưu tiên 2: Phát qua Edge-TTS Giọng Hoài Mỹ (vi-VN-HoaiMyNeural) hoặc Google AI Neural Voice Stream
      const encodedText = encodeURIComponent(text);
      const edgeTtsProxy = process.env.EXPO_PUBLIC_EDGE_TTS_URL;
      const ttsStreamUrl = edgeTtsProxy
        ? `${edgeTtsProxy}?text=${encodedText}&voice=vi-VN-HoaiMyNeural`
        : `https://translate.google.com/translate_tts?ie=UTF-8&q=${encodedText}&tl=vi&client=tw-ob`;

      console.log('[AudioService] Playing AI Voice Stream (Speed 1.15x)...');
      const { sound } = await Audio.Sound.createAsync(
        { uri: ttsStreamUrl },
        { shouldPlay: true, rate: 1.15, shouldCorrectPitch: true }
      );
      this.soundObject = sound;

      sound.setOnPlaybackStatusUpdate((status) => {
        if (status.isLoaded && status.didJustFinish) {
          this.soundObject?.unloadAsync();
          this.soundObject = null;
          if (onEnd) onEnd();
        }
      });
    } catch (error) {
      console.warn('[AudioService] Neural stream error, falling back to Native TTS:', error);
      // 3. Fallback Native Speech ở tốc độ cao (rate: 1.15, pitch: 1.05)
      Speech.speak(text, {
        language: 'vi-VN',
        pitch: 1.05,
        rate: 1.15,
        onDone: onEnd,
        onError: onEnd,
      });
    }
  }

  /**
   * Dừng âm thanh đang phát
   */
  async stopAudio(): Promise<void> {
    Speech.stop();
    if (this.soundObject) {
      try {
        await this.soundObject.stopAsync();
        await this.soundObject.unloadAsync();
      } catch (e) {}
      this.soundObject = null;
    }
  }

  /**
   * Bắt đầu ghi âm qua Microphone với cơ chế tự động dọn dẹp đối tượng cũ
   */
  async startRecording(): Promise<boolean> {
    try {
      // Dọn dẹp recording cũ nếu chưa được unload
      if (this.recordingObject) {
        try {
          await this.recordingObject.stopAndUnloadAsync();
        } catch (e) {}
        this.recordingObject = null;
      }

      const permission = await Audio.requestPermissionsAsync();
      if (!permission.granted) {
        console.warn('[AudioService] Microphone permission not granted');
        return false;
      }

      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
        shouldDuckAndroid: true,
        playThroughEarpieceAndroid: false,
      });

      const { recording } = await Audio.Recording.createAsync(
        Audio.RecordingOptionsPresets.HIGH_QUALITY
      );
      this.recordingObject = recording;
      return true;
    } catch (err) {
      console.error('[AudioService] Start recording error:', err);
      if (this.recordingObject) {
        try {
          await this.recordingObject.stopAndUnloadAsync();
        } catch (e) {}
        this.recordingObject = null;
      }
      return false;
    }
  }

  /**
   * Dừng ghi âm và trả về URI của file âm thanh thu được
   */
  async stopRecording(): Promise<string | null> {
    if (!this.recordingObject) return null;
    try {
      await this.recordingObject.stopAndUnloadAsync();
      const uri = this.recordingObject.getURI();
      this.recordingObject = null;
      return uri;
    } catch (err) {
      console.error('[AudioService] Stop recording error:', err);
      this.recordingObject = null;
      return null;
    }
  }
}

export const audioService = new AudioService();

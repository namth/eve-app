import { Platform } from 'react-native';

// Danh sách các câu ảo giác (Hallucinations) phổ biến của Whisper trên file âm thanh im lặng
const WHISPER_HALLUCINATION_PATTERNS = [
  /ghiền mì gõ/i,
  /subscribe/i,
  /đăng ký kênh/i,
  /theo dõi kênh/i,
  /cảm ơn các bạn đã xem/i,
  /cảm ơn các bạn đã theo dõi/i,
  /hãy bấm Like/i,
  /subcribe/i,
  /chúc các bạn/i,
  /hãy đăng ký/i,
  /liên hệ quảng cáo/i,
  /video hấp dẫn/i,
];

export const sttService = {
  /**
   * Chuyển đổi tệp âm thanh thu từ Microphone thành văn bản Tiếng Việt
   * Sử dụng Groq Whisper Large V3 Turbo LPU siêu tốc (~100ms)
   * Tích hợp bộ lọc loại bỏ hiện tượng "Ảo Giác Im Lặng" (Silence Hallucinations)
   */
  async transcribeAudio(audioUri: string): Promise<string | null> {
    const groqKey = process.env.EXPO_PUBLIC_GROQ_API_KEY || process.env.EXPO_PUBLIC_OPENAI_API_KEY;
    console.log('[sttService] Starting Speech-to-Text via Groq Whisper for audio:', audioUri);

    try {
      const formData = new FormData();
      const fileType = 'audio/m4a';
      const fileName = 'recording.m4a';

      // @ts-ignore
      formData.append('file', {
        uri: audioUri,
        name: fileName,
        type: fileType,
      });

      // 1. Nhận diện giọng nói bằng Groq Whisper (Whisper Large V3 Turbo - LPU hardware ~100ms)
      if (groqKey) {
        formData.append('model', 'whisper-large-v3-turbo');
        formData.append('language', 'vi');

        const startTime = Date.now();
        const response = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${groqKey}`,
          },
          body: formData,
        });

        const duration = Date.now() - startTime;

        if (response.ok) {
          const result = await response.json();
          const rawText = result.text ? result.text.trim() : '';

          console.log(`[sttService] Groq Whisper SUCCESS in ${duration}ms! Transcribed text: "${rawText}"`);

          if (!rawText) return null;

          // 2. BỘ LỌC KHẮC PHỤC ẢO GIÁC FILE IM LẶNG CỦA WHISPER
          const isHallucination = WHISPER_HALLUCINATION_PATTERNS.some((pattern) =>
            pattern.test(rawText)
          );

          if (isHallucination) {
            console.log(`[sttService] Filtered out Whisper Silence Hallucination: "${rawText}"`);
            return null; // Coi như file im lặng, không gửi câu rác này lên server
          }

          return rawText;
        } else {
          const errText = await response.text();
          console.warn(`[sttService] Groq Whisper API returned HTTP ${response.status}:`, errText);
        }
      } else {
        console.warn('[sttService] No Groq API Key found in EXPO_PUBLIC_GROQ_API_KEY');
      }

      return null;
    } catch (error) {
      console.error('[sttService] Transcribe error:', error);
      return null;
    }
  },
};

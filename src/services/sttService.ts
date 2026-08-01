import { Platform } from 'react-native';

// Các cụm từ rác/ảo giác của Whisper cần bóc tách khỏi văn bản (Sanitize)
const HALLUCINATION_PHRASES = [
  /hãy subscribe cho kênh [^.?!]+/gi,
  /để không bỏ lỡ những video hấp dẫn/gi,
  /cảm ơn các bạn đã (?:xem|theo dõi)[^.?!]*/gi,
  /hãy (?:bấm|nhấn) (?:like|thích) (?:và|hoặc) đăng ký (?:kênh)?[^.?!]*/gi,
  /liên hệ quảng cáo[^.?!]*/gi,
  /chúc các bạn (?:vui vẻ|xem video)[^.?!]*/gi,
  /ghiền mì gõ/gi,
  /subscribe/gi,
  /đăng ký kênh/gi,
  /theo dõi kênh/gi,
];

function sanitizeWhisperText(rawText: string): string | null {
  if (!rawText) return null;
  let cleaned = rawText;

  for (const phrasePattern of HALLUCINATION_PHRASES) {
    cleaned = cleaned.replace(phrasePattern, '');
  }

  // Làm sạch khoảng trắng thừa và dấu câu rác
  cleaned = cleaned.replace(/^[\s,._\-:;?!]+/, '').replace(/\s+/g, ' ').trim();

  if (!cleaned || cleaned.length < 2) return null;
  return cleaned;
}

export const sttService = {
  /**
   * Chuyển đổi tệp âm thanh thu từ Microphone thành văn bản Tiếng Việt
   * Sử dụng Groq Whisper Large V3 Turbo LPU siêu tốc (~100ms)
   * Tích hợp bộ lọc bóc tách loại bỏ hiện tượng "Ảo Giác Im Lặng" (Sanitize Whisper Hallucinations)
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

          console.log(`[sttService] Groq Whisper SUCCESS in ${duration}ms! Raw transcribed text: "${rawText}"`);

          if (!rawText) return null;

          // 2. BÓC TÁCH & BĂM BỎ CÁC CỤM TỪ ẢO GIÁC RÁC CỦA WHISPER (GIỮ LẠI LỜI NÓI THẬT CỦA USER)
          const sanitizedText = sanitizeWhisperText(rawText);

          if (!sanitizedText) {
            console.log(`[sttService] Discarded pure Whisper silence hallucination: "${rawText}"`);
            return null;
          }

          if (sanitizedText !== rawText) {
            console.log(`[sttService] Sanitized Whisper hallucination! Stripped noise. Clean text: "${sanitizedText}"`);
          }

          return sanitizedText;
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

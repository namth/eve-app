import { Platform } from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';

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

    if (!groqKey) {
      console.warn('[sttService] No Groq API Key found in EXPO_PUBLIC_GROQ_API_KEY');
      return null;
    }

    try {
      let rawText = '';
      const startTime = Date.now();

      if (Platform.OS === 'web') {
        const audioBlob = await fetch(audioUri).then((r) => r.blob());
        const formData = new FormData();
        formData.append('file', audioBlob, 'recording.m4a');
        formData.append('model', 'whisper-large-v3-turbo');
        formData.append('language', 'vi');

        const response = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${groqKey}`,
          },
          body: formData,
        });

        if (response.ok) {
          const result = await response.json();
          rawText = result.text ? result.text.trim() : '';
        } else {
          const errText = await response.text();
          console.warn(`[sttService] Groq Whisper API returned HTTP ${response.status}:`, errText);
          return null;
        }
      } else {
        // Native (Android / iOS): Sử dụng FileSystem.uploadAsync để tương thích 100% với Expo SDK 57 (tránh lỗi Unsupported FormDataPart)
        const uploadResult = await FileSystem.uploadAsync(
          'https://api.groq.com/openai/v1/audio/transcriptions',
          audioUri,
          {
            httpMethod: 'POST',
            uploadType: FileSystem.FileSystemUploadType.MULTIPART,
            fieldName: 'file',
            mimeType: 'audio/m4a',
            parameters: {
              model: 'whisper-large-v3-turbo',
              language: 'vi',
            },
            headers: {
              Authorization: `Bearer ${groqKey}`,
            },
          }
        );

        if (uploadResult.status >= 200 && uploadResult.status < 300) {
          const result = JSON.parse(uploadResult.body);
          rawText = result.text ? result.text.trim() : '';
        } else {
          console.warn(`[sttService] Groq Whisper API returned HTTP ${uploadResult.status}:`, uploadResult.body);
          return null;
        }
      }

      const duration = Date.now() - startTime;
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
    } catch (error) {
      console.error('[sttService] Transcribe error:', error);
      return null;
    }
  },
};

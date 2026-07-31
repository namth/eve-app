import { EnrollPersonResponse, PersonProfile } from '../types/personProfile';
import { peopleDatabaseService } from './peopleDatabaseService';

const N8N_WEBHOOK_URL =
  process.env.EXPO_PUBLIC_N8N_WEBHOOK_URL ||
  'https://ai.oa.io.vn/webhook/eve-chat';

const DEFAULT_USER_ID = process.env.EXPO_PUBLIC_USER_ID || 'user_default_01';

export const enrollmentService = {
  /**
   * Gửi câu thoại đăng ký người mới tới Webhook duy nhất n8n (/webhook/eve-chat)
   * Tích hợp bộ lọc Không Cưỡng Ép Gán Tên Giả (Non-Forced Name Extraction)
   */
  async enrollNewPerson(
    transcript: string,
    predictedGender: 'male' | 'female' | 'unknown',
    faceVector?: number[],
    voiceVector?: number[]
  ): Promise<{ person: PersonProfile; replyText: string; audioUrl?: string }> {
    console.log('[enrollmentService] Sending enrollment request to n8n:', transcript);

    const payload = {
      user_id: DEFAULT_USER_ID,
      message: transcript,
      context: 'enrollment',
      predicted_gender: predictedGender,
      timestamp: Math.floor(Date.now() / 1000),
      client_locale: 'vi-VN',
    };

    let enrollResult: EnrollPersonResponse;

    try {
      const response = await fetch(N8N_WEBHOOK_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error(`Enrollment server returned HTTP ${response.status}`);
      }

      const raw = await response.json();
      console.log('[enrollmentService] Received n8n enroll response:', raw);
      const parsedData = raw?.output ?? raw;

      enrollResult = {
        status: parsedData.status || 'success',
        person: parsedData.person || parsedData.update_person || undefined,
        reply_text: parsedData.reply_text || 'Dạ em đã nghe rõ rồi ạ!',
        audio_url: parsedData.audio_url,
      };
    } catch (err) {
      console.warn('[enrollmentService] Failed to reach n8n unified webhook, using fallback parser:', err);
      enrollResult = this.fallbackLocalParser(transcript, predictedGender);
    }

    // Xử lý không gán tên giả nếu người dùng không nói tên
    const rawName = enrollResult.person?.name?.trim() || '';
    const isFakeName = !rawName || /^(người quen|chị mới|anh mới|không|chưa|không cần|chỉ|bạn)$/i.test(rawName);
    const finalName = isFakeName ? '' : rawName;

    const isFemale = (enrollResult.person?.gender || predictedGender) === 'female';
    const pronoun = enrollResult.person?.preferred_pronoun || (isFemale ? 'Chị' : 'Anh');

    // Tạo hồ sơ người dùng
    const newPerson: PersonProfile = {
      id: `person_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
      name: finalName, // Để trống nếu người dùng không nói tên
      age: enrollResult.person?.age,
      gender: isFemale ? 'female' : 'male',
      preferred_pronoun: pronoun,
      role: 'friend',
      face_embedding: faceVector || this.generateDummyEmbedding(1),
      voice_embedding: voiceVector,
      created_at: Date.now(),
      last_seen_at: Date.now(),
    };

    await peopleDatabaseService.savePersonProfile(newPerson);

    const replyText =
      enrollResult.reply_text ||
      (finalName
        ? `Dạ em chào ${pronoun} ${finalName}! Em đã ghi nhớ tên của ${pronoun.toLowerCase()} rồi ạ.`
        : `Dạ ${pronoun.toLowerCase()} ơi, em đã ghi nhớ cách xưng hô rồi ạ.`);

    return {
      person: newPerson,
      replyText,
      audioUrl: enrollResult.audio_url,
    };
  },

  /**
   * Bộ đọc dự phòng cục bộ khi n8n offline (Không cưỡng ép gán tên giả)
   */
  fallbackLocalParser(
    transcript: string,
    predictedGender: 'male' | 'female' | 'unknown'
  ): EnrollPersonResponse {
    const isFemale = predictedGender === 'female' || /chị|cô|bà|em nữ/i.test(transcript);
    const pronoun = isFemale ? 'Chị' : 'Anh';

    // Bóc tách tên nếu có từ khóa: "tên là Vy", "tên tôi là Nam"
    let extractedName = '';
    const nameMatch = transcript.match(/(?:tên(?: là)?|gọi (?:tôi|chị|anh) là)\s+([A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯÀáâãèéêìíòóôõùúăđĩũơư\s\w]+)/i);
    if (nameMatch && nameMatch[1]) {
      const candidate = nameMatch[1].trim().split(/\s+/)[0];
      if (candidate && !/^(không|chưa|gì|đó|nào)$/i.test(candidate)) {
        extractedName = candidate.charAt(0).toUpperCase() + candidate.slice(1);
      }
    }

    let extractedAge: number | undefined;
    const ageMatch = transcript.match(/(\d{1,2})\s*tuổi/i);
    if (ageMatch) {
      extractedAge = parseInt(ageMatch[1], 10);
    }

    return {
      status: 'success',
      person: {
        name: extractedName,
        age: extractedAge,
        gender: isFemale ? 'female' : 'male',
        preferred_pronoun: pronoun,
      },
      reply_text: extractedName
        ? `Dạ em chào ${pronoun} ${extractedName}! Em đã nhớ tên của ${pronoun.toLowerCase()} rồi ạ.`
        : `Dạ ${pronoun.toLowerCase()} ơi, em đã ghi nhớ cách xưng hô rồi ạ.`,
    };
  },

  generateDummyEmbedding(seed: number): number[] {
    const arr: number[] = [];
    for (let i = 0; i < 128; i++) {
      arr.push(Math.sin(seed + i));
    }
    return arr;
  },
};

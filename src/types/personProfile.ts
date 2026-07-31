export type UserRole = 'admin' | 'friend';

export interface PersonProfile {
  id: string;
  name: string;
  age?: number;
  gender: 'male' | 'female' | 'unknown';
  preferred_pronoun: string; // 'Anh' | 'Chị' | 'Chú' | 'Cô' | 'Bạn'
  role: UserRole;
  face_embedding?: number[];
  voice_embedding?: number[];
  created_at: number;
  last_seen_at: number;
}

export interface EnrollPersonResponse {
  status: 'success' | 'error';
  person?: {
    name: string;
    age?: number;
    gender: 'male' | 'female' | 'unknown';
    preferred_pronoun: string;
  };
  reply_text: string;
  audio_url?: string;
}

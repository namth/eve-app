import { PersonProfile } from './personProfile';

export type EVEExpression = 'idle' | 'happy' | 'smile' | 'sad' | 'thinking' | 'speaking' | 'sleeping' | 'wakeup';

export interface ChatWebhookRequest {
  user_id: string;
  session_id?: string;
  message: string;
  audio_base64?: string;
  timestamp: number;
  client_locale: string;
  current_person?: {
    id: string;
    name: string;
    age?: number;
    gender: string;
    preferred_pronoun: string;
    role: string;
  };
}

export interface ChatWebhookResponse {
  status: 'ok' | 'awaiting_confirmation' | 'error';
  session_id: string;
  reply_text: string;
  emotion: EVEExpression;
  audio_url?: string;
  require_confirm?: boolean;
  pending_action?: string;
  suggested_answers?: string[];
  person?: {
    name?: string;
    age?: number;
    gender?: 'male' | 'female' | 'unknown';
    preferred_pronoun?: string;
    role?: 'admin' | 'friend';
  };
  update_person?: {
    name?: string;
    age?: number;
    gender?: 'male' | 'female' | 'unknown';
    preferred_pronoun?: string;
    role?: 'admin' | 'friend';
  };
}

export interface DeviceRegisterRequest {
  user_id: string;
  device_name: string;
  push_token: string;
  platform: 'ios' | 'android' | 'web';
  app_version: string;
}

export interface PushNotificationPayload {
  action: 'speak_notification' | 'open_app';
  text?: string;
  message?: string;
  body?: string;
  title?: string;
  audio_url?: string;
  emotion?: EVEExpression;
}

export interface WebViewBridgeMessage {
  type: 'SET_EXPRESSION' | 'SPEAK_SIMULATION' | 'UPDATE_VOLUME' | 'WAKEUP';
  payload?: any;
}

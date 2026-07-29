import { ChatWebhookRequest, ChatWebhookResponse, DeviceRegisterRequest } from '../types/api';
import { Platform } from 'react-native';

const N8N_WEBHOOK_URL =
  process.env.EXPO_PUBLIC_N8N_WEBHOOK_URL ||
  'https://ai.oa.io.vn/webhook/eve-chat';

const N8N_REGISTER_URL =
  process.env.EXPO_PUBLIC_N8N_REGISTER_URL ||
  'https://ai.oa.io.vn/webhook/register-device';

const DEFAULT_USER_ID = process.env.EXPO_PUBLIC_USER_ID || 'user_default_01';

export const n8nService = {
  /**
   * Gửi dữ liệu Văn Bản (message) tinh gọn tới n8n Webhook
   */
  async sendChatMessage(
    message: string,
    sessionId?: string
  ): Promise<ChatWebhookResponse> {
    const payload: ChatWebhookRequest = {
      user_id: DEFAULT_USER_ID,
      session_id: sessionId,
      message,
      timestamp: Math.floor(Date.now() / 1000),
      client_locale: 'vi-VN',
    };

    console.log('[n8nService] Sending clean TEXT payload to n8n Webhook:', payload);

    const response = await fetch(N8N_WEBHOOK_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      throw new Error(`n8n Server returned HTTP ${response.status}`);
    }

    const raw = await response.json();
    console.log('[n8nService] Received n8n raw response:', raw);

    // n8n trả về dạng { "output": { "reply_text": ..., "emotion": ... } }
    // hoặc trực tiếp { "reply_text": ..., "emotion": ... }
    const data: ChatWebhookResponse = raw?.output ?? raw;
    console.log('[n8nService] Parsed response data:', data);
    return data;
  },

  /**
   * Đăng ký Expo Push Token của điện thoại với n8n server
   */
  async registerDevicePushToken(pushToken: string): Promise<boolean> {
    const payload: DeviceRegisterRequest = {
      user_id: DEFAULT_USER_ID,
      device_name: `${Platform.OS.toUpperCase()} Device`,
      push_token: pushToken,
      platform: Platform.OS as 'ios' | 'android' | 'web',
      app_version: '1.0.0',
    };

    try {
      const res = await fetch(N8N_REGISTER_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      return res.ok;
    } catch (err) {
      console.log('[n8nService] Device registration offline / failed:', err);
      return false;
    }
  },
};

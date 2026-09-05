import * as FileSystem from 'expo-file-system/legacy';
import { Platform } from 'react-native';
import { PushNotificationPayload } from '../types/api';
import { PersonProfile } from '../types/personProfile';
import { stripMarkdown } from '../utils/markdownUtils';

const LEGACY_STORAGE_KEY = '@eve_pending_notification_queue';

// Hàng đợi thông báo phiên làm việc (In-Memory Session Queue)
// Nguồn dữ liệu duy nhất đến từ khay thông báo thật (Presented Notifications) trên máy
let memoryQueue: PushNotificationPayload[] = [];

/**
 * Xóa sạch file lưu trữ đĩa cứng cũ nếu còn sót lại từ các phiên bản trước
 */
async function purgeLegacyDiskStorage(): Promise<void> {
  if (Platform.OS === 'web') {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.removeItem(LEGACY_STORAGE_KEY);
    }
    return;
  }

  try {
    const filename = LEGACY_STORAGE_KEY.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json';
    const filePath = `${FileSystem.documentDirectory}${filename}`;
    const info = await FileSystem.getInfoAsync(filePath);
    if (info.exists) {
      await FileSystem.deleteAsync(filePath, { idempotent: true });
      console.log('[NotificationStorage] Purged legacy disk queue file');
    }
  } catch (e) {
    console.warn('[NotificationStorage] purgeLegacyDiskStorage error:', e);
  }
}

export const notificationStorage = {
  /**
   * Đồng bộ/Ghi đè toàn bộ hàng đợi thông báo hiện tại (không tích trữ)
   */
  setNotificationQueue(list: PushNotificationPayload[]): PushNotificationPayload[] {
    const uniqueItems: PushNotificationPayload[] = [];
    for (const item of list) {
      const text = (item.text || item.body || item.message || '').trim();
      if (!text) continue;
      const exists = uniqueItems.some((u) => {
        const uText = (u.text || u.body || u.message || '').trim();
        return uText === text;
      });
      if (!exists) {
        uniqueItems.push(item);
      }
    }
    memoryQueue = uniqueItems;
    console.log('[NotificationStorage] Set active queue count:', memoryQueue.length);
    return [...memoryQueue];
  },

  /**
   * Thêm thông báo mới vào hàng đợi bộ nhớ hiện tại
   */
  async addNotificationToQueue(payload: PushNotificationPayload): Promise<PushNotificationPayload[]> {
    try {
      const targetText = (payload.text || payload.body || payload.message || '').trim();
      if (!targetText) return [...memoryQueue];

      // Tránh lặp lại cùng 1 thông báo nếu bị nhận trùng lặp trong phiên
      const exists = memoryQueue.some((item) => {
        const itemText = (item.text || item.body || item.message || '').trim();
        return itemText === targetText;
      });

      if (!exists) {
        memoryQueue.push(payload);
        console.log('[NotificationStorage] Added notification to active queue. Total:', memoryQueue.length);
      }

      return [...memoryQueue];
    } catch (err) {
      console.warn('[NotificationStorage] Error adding to queue:', err);
      return [...memoryQueue];
    }
  },

  /**
   * Lấy toàn bộ hàng đợi thông báo đang chờ xử lý trong phiên
   */
  async getNotificationQueue(): Promise<PushNotificationPayload[]> {
    return [...memoryQueue];
  },

  /**
   * Xóa toàn bộ hàng đợi thông báo sau khi EVE đã báo cáo xong
   */
  async clearNotificationQueue(): Promise<void> {
    try {
      memoryQueue = [];
      await purgeLegacyDiskStorage();
      console.log('[NotificationStorage] Cleared active notification queue');
    } catch (err) {
      console.warn('[NotificationStorage] Error clearing queue:', err);
    }
  },

  /**
   * Dọn sạch file lưu trữ đĩa cứng cũ
   */
  async purgeLegacyStorage(): Promise<void> {
    await purgeLegacyDiskStorage();
  },

  /**
   * Tạo câu nói tự nhiên cá nhân hóa theo Tên & Danh xưng của Admin
   */
  formatNaturalSpeech(
    notifications: PushNotificationPayload[],
    person?: PersonProfile | null
  ): {
    speechText: string;
    displayTitle: string;
    targetEmotion: any;
  } {
    if (!notifications || notifications.length === 0) {
      return {
        speechText: 'Không có thông báo mới nào.',
        displayTitle: 'Thông báo',
        targetEmotion: 'idle',
      };
    }

    const pronoun = person?.preferred_pronoun || 'Anh';
    const name = person?.name ? ` ${person.name}` : '';
    const honorific = `${pronoun}${name}`;
    const honorificLower = pronoun.toLowerCase();

    const n = notifications.length;
    const targetEmotion = notifications[notifications.length - 1].emotion || 'happy';

    // Danh sách câu mở đầu cá nhân hóa
    const singleOpenings = [
      `${honorific} ơi, em vừa nhận được 1 thông báo từ hệ thống là:`,
      `Em xin phép báo cáo đến ${honorificLower} 1 thông báo em mới nhận được:`,
      `Dạ ${honorificLower} ơi, có 1 thông báo mới gửi đến ${honorificLower}:`,
    ];

    const multiOpenings = [
      `${honorific} ơi, có ${n} thông báo được gửi đến ${honorificLower} hôm nay.`,
      `Em xin phép báo cáo đến ${honorificLower} ${n} thông báo em mới nhận được:`,
      `Dạ ${honorificLower} ơi, em vừa nhận được ${n} thông báo mới từ hệ thống nè:`,
    ];

    // Danh sách câu kết thúc
    const closings = [
      'Hết ạ.',
      `${honorific} có chỉ thị gì không ạ?`,
      'Dạ thế thôi ạ.',
      `Em xin hết ạ, chúc ${honorificLower} một ngày làm việc hiệu quả!`,
    ];

    const numberWords = [
      'Một',
      'Hai',
      'Ba',
      'Bốn',
      'Năm',
      'Sáu',
      'Bảy',
      'Tám',
      'Chín',
      'Mười',
    ];

    // Chọn ngẫu nhiên mở đầu & kết thúc
    const randomClosing = closings[Math.floor(Math.random() * closings.length)];

    if (n === 1) {
      const randomOpening = singleOpenings[Math.floor(Math.random() * singleOpenings.length)];
      const rawContent =
        notifications[0].text ||
        notifications[0].body ||
        notifications[0].message ||
        'Có thông báo mới.';
      const bodyContent = stripMarkdown(rawContent);
      const speechText = `${randomOpening} ${bodyContent} ${randomClosing}`;
      return {
        speechText,
        displayTitle: stripMarkdown(notifications[0].title || 'EVE AI Assistant'),
        targetEmotion,
      };
    }

    // Trường hợp n > 1: Đọc mở đầu -> Liệt kê "Một là...", "Hai là..." -> Kết thúc
    const randomOpening = multiOpenings[Math.floor(Math.random() * multiOpenings.length)];
    const listSpeechParts = notifications.map((item, idx) => {
      const countLabel = numberWords[idx] || `${idx + 1}`;
      const rawText = item.text || item.body || item.message || 'Nội dung thông báo.';
      const itemText = stripMarkdown(rawText);
      return `${countLabel} là: ${itemText}.`;
    });

    const speechText = `${randomOpening} ${listSpeechParts.join(' ')} ${randomClosing}`;
    return {
      speechText,
      displayTitle: `${n} Thông báo hệ thống`,
      targetEmotion,
    };
  },
};

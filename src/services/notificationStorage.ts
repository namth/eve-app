import { PushNotificationPayload } from '../types/api';

const QUEUE_STORAGE_KEY = '@eve_pending_notification_queue';

let memoryQueue: PushNotificationPayload[] = [];

export const notificationStorage = {
  /**
   * Thêm thông báo mới vào hàng đợi (Queue)
   */
  async addNotificationToQueue(payload: PushNotificationPayload): Promise<PushNotificationPayload[]> {
    try {
      // Tránh lặp lại cùng 1 thông báo nếu bị nhận trùng lặp trong thời gian ngắn
      const exists = memoryQueue.some(
        (item) => (item.text || item.body) === (payload.text || payload.body)
      );

      if (!exists) {
        memoryQueue.push(payload);
      }

      let AsyncStorage: any = null;
      try {
        AsyncStorage = require('@react-native-async-storage/async-storage').default;
      } catch (e) {}

      if (AsyncStorage) {
        await AsyncStorage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(memoryQueue));
        console.log('[NotificationStorage] Queue updated in AsyncStorage. Total:', memoryQueue.length);
      }
      return [...memoryQueue];
    } catch (err) {
      console.warn('[NotificationStorage] Error adding to queue:', err);
      return [...memoryQueue];
    }
  },

  /**
   * Lấy toàn bộ hàng đợi thông báo đang chờ xử lý
   */
  async getNotificationQueue(): Promise<PushNotificationPayload[]> {
    try {
      if (memoryQueue.length > 0) {
        return [...memoryQueue];
      }

      let AsyncStorage: any = null;
      try {
        AsyncStorage = require('@react-native-async-storage/async-storage').default;
      } catch (e) {}

      if (AsyncStorage) {
        const jsonVal = await AsyncStorage.getItem(QUEUE_STORAGE_KEY);
        if (jsonVal) {
          const list = JSON.parse(jsonVal) as PushNotificationPayload[];
          if (Array.isArray(list)) {
            memoryQueue = list;
            console.log('[NotificationStorage] Retrieved queue from AsyncStorage. Total:', list.length);
            return [...memoryQueue];
          }
        }
      }
      return [...memoryQueue];
    } catch (err) {
      console.warn('[NotificationStorage] Error reading queue:', err);
      return [...memoryQueue];
    }
  },

  /**
   * Xóa toàn bộ hàng đợi thông báo sau khi EVE đã báo cáo xong
   */
  async clearNotificationQueue(): Promise<void> {
    try {
      memoryQueue = [];
      let AsyncStorage: any = null;
      try {
        AsyncStorage = require('@react-native-async-storage/async-storage').default;
      } catch (e) {}

      if (AsyncStorage) {
        await AsyncStorage.removeItem(QUEUE_STORAGE_KEY);
        console.log('[NotificationStorage] Cleared notification queue from AsyncStorage');
      }
    } catch (err) {
      console.warn('[NotificationStorage] Error clearing queue:', err);
    }
  },

  /**
   * Tạo câu nói tự nhiên (Có mở đầu ngẫu nhiên, đếm số lượng, liệt kê Một là/Hai là, và câu kết thúc)
   */
  formatNaturalSpeech(notifications: PushNotificationPayload[]): {
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

    const n = notifications.length;
    const targetEmotion = notifications[notifications.length - 1].emotion || 'happy';

    // Danh sách câu mở đầu
    const singleOpenings = [
      'Anh ơi, em vừa nhận được 1 thông báo từ hệ thống là:',
      'Em xin phép báo cáo đến anh 1 thông báo em mới nhận được:',
      'Dạ anh ơi, có 1 thông báo mới gửi đến anh:',
    ];

    const multiOpenings = [
      `Anh ơi, có ${n} thông báo được gửi đến anh hôm nay.`,
      `Em xin phép báo cáo đến anh ${n} thông báo em mới nhận được:`,
      `Dạ anh ơi, em vừa nhận được ${n} thông báo mới từ hệ thống nè:`,
    ];

    // Danh sách câu kết thúc
    const closings = [
      'Hết ạ.',
      'Anh có chỉ thị gì không ạ?',
      'Dạ thế thôi ạ.',
      'Em xin hết ạ, chúc anh một ngày làm việc hiệu quả!',
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
      const bodyContent =
        notifications[0].text ||
        notifications[0].body ||
        notifications[0].message ||
        'Có thông báo mới.';
      const speechText = `${randomOpening} ${bodyContent} ${randomClosing}`;
      return {
        speechText,
        displayTitle: notifications[0].title || 'EVE AI Assistant',
        targetEmotion,
      };
    }

    // Trường hợp n > 1: Đọc mở đầu -> Liệt kê "Một là...", "Hai là..." -> Kết thúc
    const randomOpening = multiOpenings[Math.floor(Math.random() * multiOpenings.length)];
    const listSpeechParts = notifications.map((item, idx) => {
      const countLabel = numberWords[idx] || `${idx + 1}`;
      const itemText = item.text || item.body || item.message || 'Nội dung thông báo.';
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

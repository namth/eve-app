import * as Notifications from 'expo-notifications';
import * as Device from 'expo-device';
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { PushNotificationPayload } from '../types/api';
import { n8nService } from './n8nService';
import { notificationStorage } from './notificationStorage';

// Cấu hình hiển thị Notification Banner theo phong cách EVE
try {
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowAlert: true, // Hiển thị banner notification với Icon EVE & màu sắc Cyan #00f0ff
      shouldPlaySound: true,
      shouldSetBadge: true,
      shouldShowBanner: true,
      shouldShowList: true,
    }),
  });
} catch (err) {
  console.warn('[NotificationService] Error setting notification handler:', err);
}

export const notificationService = {
  /**
   * Xin quyền và lấy Token gửi cho n8n
   */
  async registerForPushNotifications(): Promise<string | null> {
    if (Constants.appOwnership === 'expo') {
      console.log('[NotificationService] Running in Expo Go client. Remote Push Notifications require a Development Build (EAS Build).');
      return '[Expo Go] Cần dùng bản APK Build / Dev Build để lấy Push Token thật từ Google FCM';
    }

    if (!Device.isDevice && Platform.OS !== 'web') {
      console.log('[NotificationService] Must use physical device for Push Notifications');
    }

    try {
      // 1. Tạo Android Notification Channel với thương hiệu EVE
      if (Platform.OS === 'android') {
        await Notifications.setNotificationChannelAsync('default', {
          name: 'EVE AI Assistant',
          importance: Notifications.AndroidImportance.MAX,
          vibrationPattern: [0, 250, 250, 250],
          lightColor: '#00f0ff',
          sound: 'default',
          showBadge: true,
        });
      }

      // 2. Xin quyền Push Notification từ HĐH
      const { status: existingStatus } = await Notifications.getPermissionsAsync();
      let finalStatus = existingStatus;

      if (existingStatus !== 'granted') {
        const { status } = await Notifications.requestPermissionsAsync();
        finalStatus = status;
      }

      if (finalStatus !== 'granted') {
        console.warn('[NotificationService] Permission not granted for push notifications.');
        return 'Lỗi: Chưa cấp quyền Thông báo trong Cài đặt máy';
      }

      // 3. Lấy EAS Project ID
      const easProjectId =
        Constants.expoConfig?.extra?.eas?.projectId ||
        (Constants as any).easConfig?.projectId ||
        '41db366a-19e2-4418-9906-0bc40574baa9';

      console.log('[NotificationService] Fetching push token with projectId:', easProjectId);

      let pushToken = '';
      try {
        const tokenData = await Notifications.getExpoPushTokenAsync({
          projectId: easProjectId,
        });
        pushToken = tokenData.data;
      } catch (tokenErr: any) {
        console.warn('[NotificationService] getExpoPushTokenAsync error:', tokenErr);
        const errMsg = tokenErr?.message || String(tokenErr);
        if (errMsg.includes('FirebaseApp') || errMsg.includes('fcm-credentials') || errMsg.includes('initialized')) {
          pushToken = 'Lỗi Firebase FCM: Ứng dụng Android APK cần file google-services.json để lấy Push Token. Vui lòng tải file từ Firebase Console và làm theo hướng dẫn 3 bước.';
        } else {
          pushToken = `Lỗi lấy Push Token: ${errMsg}`;
        }
      }

      console.log('[NotificationService] Push Token obtained:', pushToken);

      // 4. Đăng ký với n8n ngầm (không chặn hoặc làm hỏng giá trị Token trả về)
      if (pushToken && !pushToken.startsWith('Lỗi')) {
        n8nService.registerDevicePushToken(pushToken).catch((err) => {
          console.warn('[NotificationService] n8n bg register token error:', err);
        });
      }

      return pushToken;
    } catch (error: any) {
      console.error('[NotificationService] Detailed register push token error:', error);
      const errMsg = error?.message || String(error);
      return `Lỗi lấy Push Token: ${errMsg}`;
    }
  },

  /**
   * Đồng bộ toàn bộ thông báo đang hiển thị trên thanh thông báo (Notification Bar) của thiết bị.
   * Giúp gom tất cả các thông báo chưa đọc vào hàng đợi mà không lưu trữ vĩnh viễn trên đĩa.
   */
  async syncPresentedNotifications(tappedPayload?: PushNotificationPayload | null): Promise<PushNotificationPayload[]> {
    try {
      const presented = await Notifications.getPresentedNotificationsAsync();
      const activeList: PushNotificationPayload[] = [];

      // 1. Nếu có thông báo vừa bấm mở app, đưa lên đầu danh sách
      if (tappedPayload) {
        activeList.push(tappedPayload);
      }

      // 2. Gom toàn bộ thông báo đang còn treo trên khay thông báo của máy
      if (presented && presented.length > 0) {
        console.log(`[NotificationService] Found ${presented.length} presented notification(s) on device tray.`);
        for (const item of presented) {
          const content = item.request?.content;
          if (!content) continue;
          const rawData = content.data || {};
          const payload: PushNotificationPayload = {
            title: content.title || 'EVE AI Assistant',
            body: content.body || 'Có thông báo mới từ hệ thống.',
            text: (rawData as any)?.text || content.body || content.title || 'Có thông báo mới từ hệ thống.',
            ...(rawData as any),
          };
          activeList.push(payload);
        }
      }

      // 3. Cập nhật hàng đợi phiên làm việc (loại bỏ trùng lặp nếu cùng nội dung)
      const currentQueue = notificationStorage.setNotificationQueue(activeList);
      return currentQueue;
    } catch (err) {
      console.warn('[NotificationService] Error syncing presented notifications:', err);
      return notificationStorage.getNotificationQueue();
    }
  },

  /**
   * Dọn sạch toàn bộ thông báo của EVE trên thanh trạng thái sau khi đã đọc xong
   */
  async dismissAllNotifications(): Promise<void> {
    try {
      await Notifications.dismissAllNotificationsAsync();
      console.log('[NotificationService] Dismissed all presented notifications from device tray.');
    } catch (err) {
      console.warn('[NotificationService] Error dismissing notifications:', err);
    }
  },

  /**
   * Lấy thông báo ban đầu khi mở App từ trạng thái đóng hoàn toàn (Cold Start)
   * Quét cả thông báo được bấm lẫn toàn bộ thông báo còn lại đang treo trên máy
   */
  async getInitialNotification(): Promise<PushNotificationPayload | null> {
    try {
      // 1. Quét thông báo cụ thể mà người dùng vừa bấm (nếu có)
      const response = await Notifications.getLastNotificationResponseAsync();
      let initialData: PushNotificationPayload | null = null;
      if (response) {
        const content = response.notification?.request?.content;
        const rawData = content?.data || {};
        initialData = {
          title: content?.title || 'EVE AI Assistant',
          body: content?.body || 'Có thông báo mới từ hệ thống.',
          text: (rawData as any)?.text || content?.body || content?.title || 'Có thông báo mới từ hệ thống.',
          ...(rawData as any),
        } as PushNotificationPayload;
      }

      // 2. Quét TẤT CẢ các thông báo còn lại đang hiển thị trên thanh Notification của máy
      await this.syncPresentedNotifications(initialData);

      return initialData;
    } catch (e) {
      console.warn('[NotificationService] Error getting initial notification:', e);
      return null;
    }
  },

  /**
   * Lắng nghe thông báo ĐẾN TRONG LÚC ĐANG MỞ APP (Foreground)
   */
  addForegroundNotificationListener(
    onReceiveForeground: (data: PushNotificationPayload) => void
  ) {
    try {
      return Notifications.addNotificationReceivedListener(async (notification) => {
        const content = notification?.request?.content;
        const rawData = content?.data || {};
        const data = {
          title: content?.title || 'EVE AI Assistant',
          body: content?.body || 'Có thông báo mới từ hệ thống.',
          ...rawData,
        } as unknown as PushNotificationPayload;
        console.log('[NotificationService] Foreground Push Notification arrived:', data);

        await notificationStorage.addNotificationToQueue(data);
        if (data && onReceiveForeground) {
          onReceiveForeground(data);
        }
      });
    } catch (e) {
      console.warn('[NotificationService] Listener error:', e);
      return { remove: () => {} };
    }
  },

  /**
   * Lắng nghe khi người dùng BẤM VÀO NOTIFICATION KHI ĐANG Ở NGOÀI APP (Background/Lock screen)
   */
  addNotificationResponseListener(
    onNotificationTapped: (data: PushNotificationPayload) => void
  ) {
    try {
      return Notifications.addNotificationResponseReceivedListener(async (response) => {
        const content = response?.notification?.request?.content;
        const rawData = content?.data || {};
        const data = {
          title: content?.title || 'EVE AI Assistant',
          body: content?.body || 'Có thông báo mới từ hệ thống.',
          text: (rawData as any)?.text || content?.body || content?.title || 'Có thông báo mới từ hệ thống.',
          ...rawData,
        } as unknown as PushNotificationPayload;
        console.log('[NotificationService] Background Push Notification tapped:', data);

        // Quét và nạp tất cả thông báo hiện có trên máy (bao gồm cả thông báo vừa bấm)
        await this.syncPresentedNotifications(data);

        if (data && onNotificationTapped) {
          onNotificationTapped(data);
        }
      });
    } catch (e) {
      console.warn('[NotificationService] Response listener error:', e);
      return { remove: () => {} };
    }
  },
};

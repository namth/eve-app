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
   * Lấy thông báo ban đầu khi mở App từ trạng thái đóng hoàn toàn (Cold Start)
   */
  async getInitialNotification(): Promise<PushNotificationPayload | null> {
    try {
      const response = await Notifications.getLastNotificationResponseAsync();
      if (!response) return null;

      const content = response.notification?.request?.content;
      const rawData = content?.data || {};
      const data = {
        title: content?.title || 'EVE AI Assistant',
        body: content?.body || 'Có thông báo mới từ hệ thống.',
        text: (rawData as any)?.text || content?.body || content?.title || 'Có thông báo mới từ hệ thống.',
        ...(rawData as any),
      } as PushNotificationPayload;

      // Thêm vào hàng đợi đĩa cứng để không bị mất
      await notificationStorage.addNotificationToQueue(data);
      return data;
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
          ...rawData,
        } as unknown as PushNotificationPayload;
        console.log('[NotificationService] Background Push Notification tapped:', data);

        // Thêm vào hàng đợi đĩa cứng trước khi mở màn hình
        await notificationStorage.addNotificationToQueue(data);
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

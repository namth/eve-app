import { PushNotificationPayload } from '../types/api';

type MessageHandler = (data: PushNotificationPayload) => void;

class WebSocketService {
  private socket: WebSocket | null = null;
  private messageListeners: MessageHandler[] = [];
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private isConnected = false;

  /**
   * Kết nối WebSocket tới n8n / WebSocket Server để nhận dữ liệu thời gian thực
   */
  connect(url: string) {
    if (this.socket) {
      this.socket.close();
    }

    try {
      console.log('[WebSocketService] Connecting to WebSocket URL:', url);
      this.socket = new WebSocket(url);

      this.socket.onopen = () => {
        console.log('[WebSocketService] Connected to real-time WebSocket server!');
        this.isConnected = true;
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
      };

      this.socket.onmessage = (event) => {
        try {
          const data: PushNotificationPayload = JSON.parse(event.data);
          console.log('[WebSocketService] Real-time message received:', data);
          this.messageListeners.forEach((listener) => listener(data));
        } catch (e) {
          console.error('[WebSocketService] Parse message error:', e);
        }
      };

      this.socket.onerror = (error) => {
        console.log('[WebSocketService] Socket error:', error);
      };

      this.socket.onclose = () => {
        console.log('[WebSocketService] Socket closed. Reconnecting in 5s...');
        this.isConnected = false;
        this.reconnectTimer = setTimeout(() => this.connect(url), 5000);
      };
    } catch (err) {
      console.log('[WebSocketService] Connection failed:', err);
    }
  }

  /**
   * Đăng ký callback khi có tin nhắn thời gian thực đến
   */
  onMessage(listener: MessageHandler) {
    this.messageListeners.push(listener);
    return () => {
      this.messageListeners = this.messageListeners.filter((l) => l !== listener);
    };
  }

  disconnect() {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
  }
}

export const websocketService = new WebSocketService();

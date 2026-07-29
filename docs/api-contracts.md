# 3. Hợp đồng API & Giao thức Dữ liệu (API Contracts & Data Interfaces)

Tài liệu này định nghĩa chi tiết cấu trúc JSON Request / Response giữa **Mobile App EVE** và **n8n Backend**.

---

## 3.1. API Đăng ký Thiết bị (Device Token Registration)

### `POST /webhook/register-device`
Được gọi khi ứng dụng khởi chạy lần đầu để đăng ký Token nhận Push Notification với n8n.

#### Request Payload:
```json
{
  "user_id": "user_default_01",
  "device_name": "iPhone 15 Pro",
  "push_token": "ExponentPushToken[AbCdEf123456...]",
  "platform": "ios",
  "app_version": "1.0.0"
}
```

#### Response Payload:
```json
{
  "status": "success",
  "message": "Device registered successfully",
  "registered_at": "2026-07-27T19:00:00Z"
}
```

---

## 3.2. API Gửi Lệnh Giọng nói & Nhận Phản hồi (`/webhook/chat`)

### `POST /webhook/chat`
Endpoint chính để Mobile App gửi văn bản người dùng nói (STT) lên n8n AI Agent.

#### Request Payload:
```json
{
  "user_id": "user_default_01",
  "session_id": "sess_987654", // Bắt buộc nếu đang trong luồng xác nhận bước 2
  "message": "Gửi báo cáo doanh thu tuần này cho sếp",
  "timestamp": 1785153600,
  "client_locale": "vi-VN"
}
```

#### Response Payload (Lệnh thường - Direct Action):
```json
{
  "status": "ok",
  "session_id": "sess_987654",
  "reply_text": "Doanh thu tuần này đạt 120 triệu đồng, tăng 15% so với tuần trước.",
  "emotion": "happy", // Các giá trị: "idle" | "happy" | "thinking" | "speaking" | "sleeping"
  "audio_url": "https://n8n-server.com/assets/tts_response_101.mp3",
  "require_confirm": false,
  "action_executed": "query_revenue"
}
```

#### Response Payload (Lệnh Cấp 2 - Cần Xác nhận):
```json
{
  "status": "awaiting_confirmation",
  "session_id": "sess_987654",
  "reply_text": "Tôi chuẩn bị gửi email báo cáo cho Giám đốc. Bạn có chắc chắn muốn gửi ngay không?",
  "emotion": "thinking",
  "audio_url": "https://n8n-server.com/assets/tts_confirm_102.mp3",
  "require_confirm": true,
  "pending_action": "send_email_report",
  "suggested_answers": ["Đồng ý", "Hủy"]
}
```

---

## 3.3. Expo Push Notification Payload (n8n gửi Push tới Điện thoại)

Khi hệ thống quản lý có tin tức/thông báo mới, n8n gọi HTTP Request tới `https://exp.host/--/api/v2/push/send`:

#### Payload Mẫu & Cấu trúc Mặc định:
```json
{
  "to": "ExponentPushToken[AbCdEf123456...]",
  "sound": "default",
  "title": "🤖 EVE - Thông báo mới",
  "body": "Có thông báo mới từ hệ thống quản lý",
  "data": {
    "action": "speak_notification",
    "text": "Có thông báo mới từ hệ thống.",
    "emotion": "idle",
    "audio_url": "https://n8n-server.com/assets/tts_notif_201.mp3"
  }
}
```

---

## 3.4. TypeScript Interfaces trong Mã nguồn Client (`src/types/api.ts`)

```typescript
export type EVEExpression = 'idle' | 'happy' | 'thinking' | 'speaking' | 'sleeping' | 'wakeup';

export interface DeviceRegisterRequest {
  user_id: string;
  device_name: string;
  push_token: string;
  platform: 'ios' | 'android';
  app_version: string;
}

export interface ChatWebhookRequest {
  user_id: string;
  session_id?: string;
  message: string;
  timestamp: number;
  client_locale: string;
}

export interface ChatWebhookResponse {
  status: 'ok' | 'awaiting_confirmation' | 'error';
  session_id: string;
  reply_text: string;
  emotion: EVEExpression;
  audio_url?: string;
  require_confirm: boolean;
  pending_action?: string;
  suggested_answers?: string[];
}

export interface PushNotificationData {
  action: 'speak_notification' | 'open_app';
  text: string;
  audio_url?: string;
  emotion?: EVEExpression;
}
```

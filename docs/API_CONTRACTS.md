# 📜 Hợp Đồng Dữ Liệu & Giao Thức API (API Contracts)

Tài liệu này chuẩn hóa toàn bộ các cấu trúc dữ liệu JSON, giao thức mạng REST API và lược đồ cơ sở dữ liệu SQLite trong hệ thống **EVE AI Assistant**.

---

## 1. OpenRouter LLM REST API (`LocalAiAgentService`)

Hệ thống kết nối trực tiếp đến OpenRouter API Gateway để gọi mô hình `openai/gpt-4o-mini`.

* **Endpoint:** `https://openrouter.ai/api/v1/chat/completions`
* **Method:** `POST`
* **Headers:**
  ```http
  Authorization: Bearer sk-or-v1-YOUR_OPENROUTER_KEY_HERE
  Content-Type: application/json
  HTTP-Referer: https://eve-ai.local
  X-Title: EVE Face Detector Assistant
  ```

### A. Tác vụ: Gộp & Tóm tắt Ngữ nghĩa Thông báo (`summarizeNotifications`)

**Request Payload:**
```json
{
  "model": "openai/gpt-4o-mini",
  "temperature": 0.3,
  "max_tokens": 250,
  "messages": [
    {
      "role": "system",
      "content": "Bạn là EVE - Nữ trợ lý quản gia AI thông minh... Phân tích ngữ nghĩa, gộp các sự việc cùng bản chất, tạo câu văn tóm tắt tối đa 2-3 câu đọc to cho Admin. Không dùng markdown..."
    },
    {
      "role": "user",
      "content": "Dưới đây là danh sách 4 thông báo thô:\n[\n  {\"id\":\"1\", \"title\":\"Camera sân\", \"body\":\"Phát hiện có người bấm chuông cổng\"},\n  {\"id\":\"2\", \"title\":\"Camera cổng\", \"body\":\"Shipper giao đồ ăn đang đứng chờ\"},\n  {\"id\":\"3\", \"title\":\"Server\", \"body\":\"Nhiệt độ phòng máy chủ vượt 38 độ\"},\n  {\"id\":\"4\", \"title\":\"Cảnh báo\", \"body\":\"Nhiệt độ máy chủ cao\"}\n]\nHãy tạo bản tin tóm tắt cho Anh Nam ngay:"
    }
  ]
}
```

**Response Format (Chuỗi văn bản thuần túy cho TTS):**
```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "Dạ anh Nam ơi, em xin phép báo cáo: Cổng trước có người bấm chuông và shipper giao đồ ăn vừa tới khoảng 5 phút trước, đồng thời hệ thống ghi nhận 2 cảnh báo về nhiệt độ phòng máy chủ đang tăng cao. Anh kiểm tra nhé! Hết ạ!"
      }
    }
  ]
}
```

---

### B. Tác vụ: Phân loại Ý định Xác nhận (`classifyConfirmationIntent`)

**Request Payload:**
```json
{
  "model": "openai/gpt-4o-mini",
  "temperature": 0.0,
  "max_tokens": 10,
  "messages": [
    {
      "role": "system",
      "content": "Phân loại vào ĐÚNG 1 TRONG 3 NHÃN: CONFIRMED, DENIED, AMBIGUOUS. Chỉ trả về đúng 1 từ duy nhất."
    },
    {
      "role": "user",
      "content": "Câu hỏi của Robot: \"Có phải là Anh Nam em gặp hôm qua không ạ?\"\nCâu trả lời của Người: \"Chuẩn cơm mẹ nấu rồi em ơi\"\nNhãn:"
    }
  ]
}
```

**Response Format:**
```
CONFIRMED
```

---

### C. Tác vụ: Bóc tách Danh tính Tự nhiên (`extractIdentityAndPronoun`)

**Response Format (JSON có cấu trúc):**
```json
{
  "name": "Nam",
  "preferredPronoun": "Anh",
  "gender": "male",
  "role": "admin"
}
```

---

## 2. Firebase Cloud Messaging (FCM v1) Push API

Sử dụng để n8n bắn thông báo đẩy về thiết bị EVE.

* **Endpoint:** `https://fcm.googleapis.com/v1/projects/eve-agent-7c30d/messages:send`
* **Method:** `POST`
* **Headers:**
  ```http
  Authorization: Bearer <GOOGLE_OAUTH2_ACCESS_TOKEN>
  Content-Type: application/json
  ```

### Cấu trúc Payload Chuẩn:
```json
{
  "message": {
    "token": "c_X9v...APA91bF... (FCM Token máy EVE)",
    "notification": {
      "title": "Cảnh báo an ninh",
      "body": "Camera sân trước phát hiện chuyển động bất thường"
    },
    "data": {
      "category": "security",
      "priority": "high",
      "timestamp": "1725700860",
      "event_type": "motion_detected"
    },
    "android": {
      "priority": "high",
      "notification": {
        "channel_id": "eve_notifications_channel",
        "sound": "default",
        "default_vibrate_timings": true
      }
    }
  }
}
```

---

## 3. n8n AI Chat Webhook (`N8nService.kt`)

Được dùng khi người dùng nói chuyện hai chiều bằng giọng nói với EVE.

* **Endpoint mặc định:** `https://ai.oa.io.vn/webhook/eve-chat`
* **Method:** `POST`

### Request từ EVE gửi lên n8n:
```json
{
  "user_id": "user_default_01",
  "message": "Hôm nay tôi có lịch hẹn nào không?",
  "context": "chat",
  "timestamp": 1725700860,
  "client_locale": "vi-VN",
  "session_id": "sess_1725700850",
  "current_person": {
    "id": "person_1725700100",
    "name": "Nam",
    "preferred_pronoun": "Anh",
    "role": "admin"
  }
}
```

### Response từ n8n trả về cho EVE:
```json
{
  "reply_text": "Dạ thưa anh Nam, chiều nay lúc 15 giờ anh có cuộc họp với đối tác kỹ thuật ạ!",
  "emotion": "happy",
  "audio_url": null,
  "session_id": "sess_1725700850",
  "action": null,
  "pronunciation": {
    "word": "AI",
    "speak": "Ây Ai"
  }
}
```

---

## 4. Lược Đồ Cơ Sở Dữ Liệu SQLite (`EveDatabaseHelper`)

### Bảng 1: `people` (Hồ sơ sinh trắc học & Thông tin người dùng)
| Tên cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `TEXT` | `PRIMARY KEY` | Khóa chính dạng `person_{timestamp}` |
| `name` | `TEXT` | `NOT NULL` | Tên người dùng (ví dụ: "Nam") |
| `age` | `INTEGER` | `NULL` | Tuổi người dùng |
| `gender` | `TEXT` | | Giới tính: `"male"`, `"female"`, `"unknown"` |
| `preferred_pronoun`| `TEXT` | | Đại từ xưng hô: `"Anh"`, `"Chị"`, `"Chú"`, `"Bạn"` |
| `role` | `TEXT` | | Quyền hạn: `"admin"` hoặc `"friend"` |
| `avatar_base64` | `TEXT` | `NULL` | Ảnh khuôn mặt crop nhỏ (160x160 JPEG Base64) |
| `face_embedding` | `BLOB` | `NULL` | Mảng float[] vector 192 chiều nhúng khuôn mặt |
| `created_at` | `INTEGER` | `NOT NULL` | Thời gian tạo hồ sơ (Epoch millis) |
| `last_seen_at` | `INTEGER` | `NOT NULL` | Thời gian lần gần nhất camera nhìn thấy |

### Bảng 2: `notifications` (Hàng đợi thông báo từ n8n/FCM)
| Tên cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `TEXT` | `PRIMARY KEY` | ID thông báo từ FCM hoặc UUID |
| `title` | `TEXT` | | Tiêu đề thông báo |
| `body` | `TEXT` | | Nội dung văn bản thông báo |
| `data` | `TEXT` | `NULL` | Chuỗi JSON chứa metadata kèm theo |
| `is_read` | `INTEGER` | `DEFAULT 0` | Trạng thái: `0` (Chưa đọc/Pending), `1` (Đã báo cáo) |
| `received_at` | `INTEGER` | `NOT NULL` | Thời điểm nhận tin nhắn trên máy |

### Bảng 3: `app_settings` (Cấu hình hệ thống Key-Value)
| Khóa (`key`) | Giá trị mặc định (`value`) | Mô tả |
| :--- | :--- | :--- |
| `ai_provider` | `openrouter` | Nhà cung cấp AI chính |
| `ai_api_key` | `sk-or-v1-ed579a944aed...` | API Key kết nối OpenRouter |
| `ai_model` | `openai/gpt-4o-mini` | Tên model AI thực thi |
| `ai_base_url` | `https://openrouter.ai/api/v1/chat/completions` | Endpoint REST API |
| `fcm_token` | `c_X9v...APA91bF...` | Device Token của máy để n8n gửi tin |

### Bảng 4: `pronunciations` (Từ điển phát âm TTS)
| Tên cột | Kiểu dữ liệu | Ràng buộc | Ví dụ |
| :--- | :--- | :--- | :--- |
| `word` | `TEXT` | `PRIMARY KEY` | `"IoT"` |
| `speak` | `TEXT` | `NOT NULL` | `"Ai Ô Ti"` |

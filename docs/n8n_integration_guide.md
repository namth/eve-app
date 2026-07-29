# 📖 Hướng dẫn Chi tiết: Tương tác 2 chiều giữa n8n và EVE Mobile App

Tài liệu này hướng dẫn chi tiết cách cấu hình n8n Workflows để gửi/nhận dữ liệu với **EVE Mobile Voice AI Assistant**.

---

## 🟢 Chiều 1: App -> n8n (Ra lệnh bằng Giọng nói & Nhận Phản hồi)

### 1.1. Cấu hình Node Webhook trên n8n
* **Node Type**: `Webhook`
* **HTTP Method**: `POST`
* **Path**: `chat`
* **URL n8n Webhook đầy đủ**: `https://your-n8n-domain.com/webhook/chat`
* **Cấu hình trong App**: Dán URL này vào file `.env` (`EXPO_PUBLIC_N8N_WEBHOOK_URL`) hoặc nhập vào màn hình **⚙️ Cài đặt** trên App.

### 1.2. Cấu trúc Payload App gửi lên n8n (`POST JSON`)
```json
{
  "user_id": "user_default_01",
  "session_id": "sess_1785153600",
  "message": "Kiểm tra doanh thu hôm nay",
  "timestamp": 1785153600,
  "client_locale": "vi-VN"
}
```

### 1.3. Cấu trúc JSON n8n Phản hồi lại cho App
n8n AI Agent xử lý xong bắt buộc phản hồi JSON theo định dạng sau:

```json
{
  "status": "ok",
  "session_id": "sess_1785153600",
  "reply_text": "Doanh thu hôm nay đạt 50 triệu đồng, tăng 12% so với hôm qua.",
  "emotion": "happy",
  "audio_url": "https://your-server.com/assets/tts_response.mp3",
  "require_confirm": false
}
```

* **Giải thích các trường**:
  - `reply_text`: Văn bản câu trả lời để hiển thị và đọc.
  - `emotion`: Nhãn biểu cảm cho EVE (`"happy"` | `"thinking"` | `"idle"` | `"sleeping"`).
  - `audio_url` *(tùy chọn)*: URL file âm thanh từ ElevenLabs/OpenAI TTS. *Nếu bỏ trống (`null`), App sẽ tự động sử dụng giọng nói tiếng Việt chuẩn trên thiết bị!*
  - `require_confirm`: Đặt `true` nếu là lệnh Cấp 2 cần người dùng xác nhận ("Đồng ý" / "Hủy").

---

## 🔵 Chiều 2: n8n -> App (Gửi Push Notification Chủ động)

Khi hệ thống quản lý của bạn (CRM, Google Sheets, Database, Webhook bên ngoài) có tin mới:

### 2.1. Cấu hình Node HTTP Request trên n8n
* **Node Type**: `HTTP Request`
* **Method**: `POST`
* **URL**: `https://exp.host/--/api/v2/push/send`
* **Headers**: `Content-Type: application/json`

### 2.2. JSON Body gửi tới Expo Push API:
```json
{
  "to": "ExponentPushToken[xxxxxxxxx]",
  "sound": "default",
  "title": "🤖 EVE - Thông báo mới",
  "body": "Có báo cáo mới từ hệ thống quản lý",
  "data": {
    "action": "speak_notification",
    "text": "Sếp vừa phê duyệt hợp đồng dự án A, bạn có muốn nghe tóm tắt không?",
    "audio_url": "https://your-server.com/assets/notif.mp3",
    "emotion": "happy"
  }
}
```

### 2.3. Luồng Hoạt động trên Điện thoại:
1. Điện thoại nhận thông báo ngắn gọn.
2. Người dùng chạm vào Thông báo -> App mở lên.
3. EVE tự động chuyển từ `idle` sang `speaking` và đọc giọng nói nội dung trong `data.text` (hoặc phát `data.audio_url`).

---

## 📥 Cách Import Workflow Mẫu vào n8n

Dự án đã tạo sẵn file mẫu **[docs/n8n_workflow_template.json](file:///Users/namtran/Local%20Apps/eve-app/docs/n8n_workflow_template.json)**.

**Các bước Import**:
1. Đăng nhập vào giao diện n8n của bạn.
2. Vào mục **Workflows** -> Nhấn nút **Import from File**.
3. Chọn file `n8n_workflow_template.json`.
4. Kết nối API Key OpenAI / Claude cho node AI Agent và bấm **Activate Workflow**.

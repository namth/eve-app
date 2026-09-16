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
  "reply_text": "Dạ em chào anh Nam, em xin phép nghỉ đây ạ!",
  "emotion": "wave-right",
  "action": "logout",
  "audio_url": "https://your-server.com/assets/tts_response.mp3",
  "require_confirm": false
}
```

* **Giải thích các trường**:
  - `reply_text`: Văn bản câu trả lời để hiển thị và đọc qua TTS.
  - `emotion`: Nhãn biểu cảm hoặc cử chỉ one-shot của EVE:
    - Biểu cảm: `"idle"` | `"happy"` | `"smile"` | `"sad"` | `"angry"` | `"thinking"` | `"sleeping"` | `"wakeup"`
    - Cử chỉ cơ thể: `"wave-left"` (vẫy tay trái) | `"wave-right"` (vẫy tay phải) | `"spin-360"` (xoay tròn 360°)
  - `action`: Lệnh chức năng hệ thống dành riêng cho Client:
    - `"logout"`: Tắt app và thoát ra ngoài màn hình chính điện thoại (dành cho các câu: *"em nghỉ đi"*, *"em tự out đi"*, *"tắt app đi"*...).
    - `"update-face-detect"`: Yêu cầu EVE chụp lại snapshot camera và cập nhật hồ sơ khuôn mặt cho người dùng.
    - `"none"`: Trò chuyện thông thường, không can thiệp hệ thống.
  - `audio_url` *(tùy chọn)*: URL file âm thanh từ ElevenLabs/OpenAI TTS. *Nếu bỏ trống (`null`), App sẽ tự động sử dụng giọng nói tiếng Việt chuẩn trên thiết bị!*
  - `require_confirm`: Đặt `true` nếu là lệnh Cấp 2 cần người dùng xác nhận ("Đồng ý" / "Hủy").

---

### 1.4. Mẫu System Prompt chuẩn cho Node AI Agent trên n8n
```text
Bạn là EVE - Một Robot AI Assistant cá nhân thông minh, hóm hỉnh và truyền cảm của công ty INOVA.

Nhiệm vụ của bạn:
1. Phản hồi bằng tiếng Việt tự nhiên, ngắn gọn (1-3 câu).
2. Xác định nhãn cảm xúc hoặc cử chỉ ("emotion") phù hợp nhất:
   - "wave-right" hoặc "wave-left": Khi người dùng chào, tạm biệt, bảo vẫy tay.
   - "spin-360": Khi người dùng bảo xoay một vòng, nhảy múa, biểu diễn.
   - "smile": Khi vui vẻ, thân thiện nhẹ nhàng.
   - "happy": Khi rất vui, phấn khởi.
   - "sad": Khi người dùng buồn, thông báo tin xấu.
   - "angry": Khi bị trêu chọc quá mức, phản ứng giận dỗi hóm hỉnh.
   - "thinking": Khi đang tra cứu, suy nghĩ, tính toán.
   - "idle": Trạng thái bình thường.

3. Xác định lệnh hệ thống ("action"):
   - "logout": Khi người dùng ra lệnh "em nghỉ đi", "em tự out đi", "tắt app", "thoát app".
   - "update-face-detect": Khi người dùng ra lệnh "cập nhật lại nhận diện khuôn mặt cho anh", "cập nhật khuôn mặt", "quét lại mặt".
   - "none": Cho tất cả các câu nói bình thường khác.

Format đầu ra JSON bắt buộc:
{
  "reply_text": "Câu trả lời của bạn",
  "emotion": "wave-right",
  "action": "logout",
  "require_confirm": false
}
```

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

# 🤖 CONTEXT.md - EVE Mobile Voice AI Assistant

Tài liệu này tóm tắt thông tin cốt lõi nhất của dự án để bất kỳ AI Agent nào khi bắt đầu làm việc cũng có thể nắm nhanh toàn bộ kiến trúc và quy chuẩn dự án.

---

## 📌 1. Bức tranh Tổng thể (Project Summary)
* **Dự án**: **EVE Mobile Voice AI Assistant** - Ứng dụng di động trợ lý giọng nói 2 chiều kết nối với bộ não **n8n AI Agent**.
* **Mục đích**: 
  1. Nhận Push Notification từ n8n khi hệ thống quản lý có tin mới -> Người dùng chạm thông báo -> App mở -> EVE chuyển từ `idle` sang `speaking` tự động phát âm thanh thông báo.
  2. Người dùng ra lệnh bằng giọng nói (STT) -> Gửi JSON lên n8n Webhook -> n8n xử lý (LLM + Memory) -> Trả về văn bản + nhãn cảm xúc + audio TTS -> EVE phát giọng nói và hiển thị biểu cảm sinh động.
  3. Lệnh quan trọng (High-Impact Action): EVE nhắc lại yêu cầu và chờ người dùng xác nhận ("Đồng ý" / "Hủy") trước khi thực thi.

---

## 🎨 2. Giao diện EVE Robot & Trạng thái Biểu cảm
Giao diện EVE được nhúng qua **WebView** từ file `eve_robot_interface.html`:
- **6 Trạng thái EVE**:
  1. `idle`: Chế độ chờ, chớp mắt tự nhiên.
  2. `happy`: Cười vẫy tay (`giggle-anim`).
  3. `thinking`: Visor trượt xuống, xoa cằm, bong bóng mờ dấu `?`.
  4. `speaking`: Đôi mắt nhấp nháy co giãn theo biên độ sóng âm giọng nói.
  5. `sleeping`: Mắt nhắm, bong bóng `Zzz`, tỏa hào quang sáng dịu (`aura-pulse`).
  6. `wakeup`: Giật mình nảy nhẹ (`startle`), mắt chớp nảy dấu `!`, vung tay nhẹ rồi hạ mượt về `idle`.
- **Quy tắc Tự động**:
  - Không tương tác trong **30 giây** -> Tự chuyển sang `sleeping`.
  - Chạm vào màn hình khi `sleeping` -> Kích hoạt `wakeup` (2.2s) rồi về `idle`.

---

## 🛠️ 3. Tech Stack & Cấu trúc Tệp
- **Mobile Framework**: React Native (Expo SDK) + TypeScript.
- **WebView Engine**: `react-native-webview` (Render EVE UI Canvas 60fps).
- **Audio & Voice**: `expo-av` (Play audio TTS & Record micro STT).
- **Push Notification**: `expo-notifications` -> Kết nối API n8n & Expo Push API.

### 📌 Push Notification Payload & Lifecycle Contract
Cấu hình chuẩn của `data` gửi từ Push Notification / n8n:
```json
{
  "action": "speak_notification",
  "text": "Có thông báo mới từ hệ thống.",
  "emotion": "idle",
  "audio_url": "https://..."
}
```
**Quy tắc giao diện & Cơ chế chờ đọc thông báo (Notification Lifecycle)**:
1. **Giao diện Notification HĐH & In-App Card UI**:
   - **HĐH Notification**: Icon EVE (`./assets/icon.png`), màu Cyan Neon (`#00f0ff`), Android Channel `EVE AI Assistant` (không tự ý chèn icon emoji vào title làm xấu chuỗi).
   - **In-App Notification Banner Card**: Khi ứng dụng nhận notification hoặc mở từ notification, một thẻ thông báo viền Cyan dạng Neon sẽ hiển thị trực quan ở trên giao diện EVE, liệt kê đầy đủ Tiêu đề & Nội dung văn bản thông báo.
   - **Nút "Nghe lại thông báo"**: Thẻ thông báo tích hợp sẵn nút `🔊 Nghe lại thông báo`. Khi chạm vào, EVE sẽ phát lại giọng nói đọc toàn bộ câu văn bản của thông báo đó.
2. **Cơ chế Hàng đợi Nhiều Thông Báo & Đọc Giọng Nói Tự Nhiên (Multi-Notification Queue & Natural Speech)**:
   - **Hàng đợi đĩa cứng (Queue Storage)**: Nếu có 2 hay nhiều thông báo gửi tới khi ứng dụng chưa mở, `notificationStorage` lưu toàn bộ thành danh sách đĩa cứng (`@eve_pending_notification_queue`).
   - **Mở đầu ngẫu nhiên tự nhiên**:
     - 1 thông báo: Pick ngẫu nhiên *"Anh ơi, em vừa nhận được 1 thông báo từ hệ thống là:"*, *"Em xin phép báo cáo đến anh 1 thông báo em mới nhận được:"*...
     - N thông báo: Pick ngẫu nhiên và đếm chính xác N: *"Anh ơi, có N thông báo được gửi đến anh hôm nay:"*, *"Em xin phép báo cáo đến anh N thông báo em mới nhận được:"*...
   - **Nội dung liệt kê chuẩn tiếng Việt**:
     - 1 thông báo: Đọc trực tiếp nội dung.
     - N thông báo: Đọc đếm theo thứ tự: *"Một là: [Nội dung 1]. Hai là: [Nội dung 2]. Ba là: [Nội dung 3]..."*
   - **Câu kết thúc ngẫu nhiên**: Pick ngẫu nhiên *"Hết ạ"*, *"Anh có chỉ thị gì không ạ?"*, *"Dạ thế thôi ạ"*, *"Em xin hết ạ, chúc anh một ngày làm việc hiệu quả!"*.
3. **Tương thích Expo Go (SDK 53+)**:
   - `registerForPushNotifications` được bọc kiểm tra `Constants.appOwnership === 'expo'` để không gây lỗi Console Error đỏ màn hình khi test ứng dụng trong Expo Go client.

### Sơ đồ Thư mục Chính:
```
eve-app/
├── docs/                      # Bộ tài liệu kiến trúc chuẩn (overview, architecture, api-contracts...)
├── eve_robot_interface.html    # Giao diện mẫu EVE HTML/CSS/SVG
├── .env                       # Chứa EXPO_PUBLIC_N8N_WEBHOOK_URL
├── CONTEXT.md                 # File tóm tắt chỉ dẫn này
├── App.tsx                    # Core app entry point
└── src/
    ├── components/            # EVEAvatarWebView.tsx, ControlPanel.tsx, StatusBadge.tsx
    ├── services/              # n8nService.ts, audioService.ts, notificationService.ts
    ├── hooks/                 # useEVEState.ts, useSpeechRecognize.ts
    └── types/                 # api.ts
```

---

## 📚 4. Tham khảo Tài liệu Chi tiết
- [Mục tiêu & Kịch bản Sử dụng](file:///Users/namtran/Local%20Apps/eve-app/docs/overview.md)
- [Sơ đồ Kiến trúc & State Machine](file:///Users/namtran/Local%20Apps/eve-app/docs/architecture.md)
- [Hợp đồng API & Formats JSON](file:///Users/namtran/Local%20Apps/eve-app/docs/api-contracts.md)
- [Thư viện & Quy chuẩn Code](file:///Users/namtran/Local%20Apps/eve-app/docs/conventions.md)
- [Danh sách Roadmap Tasks](file:///Users/namtran/Local%20Apps/eve-app/docs/tasks.md)

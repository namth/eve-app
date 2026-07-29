# 5. Danh sách Task Triển khai (Development Tasks Roadmap)

Danh sách công việc được chia nhỏ thành từng phiên làm việc gọn gàng (mỗi Task < 100k tokens) để đảm bảo tiến độ triển khai chính xác và dễ kiểm thử.

---

## 📋 Task 1: Khởi tạo Dự án Expo & Cấu hình Biến môi trường
- [ ] Khởi tạo dự án React Native (Expo SDK) với TypeScript.
- [ ] Cài đặt các phụ thuộc bắt buộc: `react-native-webview`, `expo-av`, `expo-notifications`, `expo-device`.
- [ ] Tạo file `.env` chứa URL `EXPO_PUBLIC_N8N_WEBHOOK_URL` và cấu hình `app.json`.
- [ ] Kiểm tra build cơ bản thành công.

---

## 📋 Task 2: Xây dựng Component `EVEAvatarWebView.tsx` & Bridge Biểu cảm
- [ ] Nhúng toàn bộ file `eve_robot_interface.html` vào WebView.
- [ ] Viết hàm Bridge `sendWebViewMessage` trong React Native để điều khiển biểu cảm EVE (`idle`, `happy`, `thinking`, `speaking`, `sleeping`, `wakeup`).
- [ ] Test thử 6 trạng thái biểu cảm hoạt động mượt mà trên WebView qua Bảng điều khiển.

---

## 📋 Task 3: Phát triển Custom Hook `useEVEState` & Bộ đếm 30s Idle -> Sleeping
- [ ] Xây dựng State machine quản lý trạng thái hiện tại của EVE.
- [ ] Cấu hình bộ đếm thời gian tự động: Khi người dùng không tương tác trong 30 giây -> Chuyển sang `sleeping`.
- [ ] Bắt sự kiện chạm vào màn hình khi EVE đang `sleeping` -> Kích hoạt `wakeup` (giật mình nảy người với dấu `!`) -> Chuyển về `idle`.

---

## 📋 Task 4: Xây dựng Audio Service, STT & Kết nối n8n Webhook
- [ ] Cấu hình `expo-av` để ghi âm giọng nói người dùng qua nút Micro.
- [ ] Chuyển giọng nói thành văn bản (STT).
- [ ] Viết `n8nService.ts` để gửi POST request chứa văn bản lên n8n Webhook.
- [ ] Xử lý phản hồi từ n8n: tự động phát file audio TTS trả về và cập nhật nhãn cảm xúc tương ứng cho EVE.

---

## 📋 Task 5: Tích hợp Expo Push Notification & Tự động Đọc Thông báo
- [ ] Khởi tạo `notificationService.ts` lấy `ExpoPushToken` của thiết bị và đăng ký với n8n.
- [ ] Đăng ký Listener nhận sự kiện Push Notification.
- [ ] Bắt sự kiện người dùng bấm vào thông báo -> Mở App -> EVE chuyển từ `idle` sang `speaking` -> Tự động phát âm thanh thông báo.

---

## 📋 Task 6: Tích hợp Luồng Xác nhận 2 Bước (Confirmation) & Kiểm thử
- [ ] Xử lý UI/UX hiển thị câu hỏi xác nhận khi n8n trả về `require_confirm: true`.
- [ ] Hỗ trợ nút chọn nhanh ("Đồng ý" / "Hủy") hoặc nói trực tiếp qua Micro.
- [ ] Kiểm thử toàn bộ luồng hoạt động (End-to-End) trên thiết bị thật / giả lập.

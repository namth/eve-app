# 5. Danh sách Task Triển khai (Development Tasks Roadmap)

Danh sách công việc được chia nhỏ thành từng phiên làm việc gọn gàng (mỗi Task < 100k tokens) để đảm bảo tiến độ triển khai chính xác và dễ kiểm thử.

---

## 📋 Task 1: Khởi tạo Dự án Expo & Cấu hình Biến môi trường
- [x] Khởi tạo dự án React Native (Expo SDK) với TypeScript.
- [x] Cài đặt các phụ thuộc bắt buộc: `react-native-webview`, `expo-av`, `expo-notifications`, `expo-device`.
- [x] Tạo file `.env` chứa URL `EXPO_PUBLIC_N8N_WEBHOOK_URL` và cấu hình `app.json`.
- [x] Kiểm tra build cơ bản thành công.

---

## 📋 Task 2: Xây dựng Component `EVEAvatarWebView.tsx` & Bridge Biểu cảm
- [x] Nhúng toàn bộ file `eve_robot_interface.html` vào WebView.
- [x] Viết hàm Bridge `sendWebViewMessage` trong React Native để điều khiển biểu cảm EVE (`idle`, `happy`, `thinking`, `speaking`, `sleeping`, `wakeup`).
- [x] Test thử 6 trạng thái biểu cảm hoạt động mượt mà trên WebView qua Bảng điều khiển.

---

## 📋 Task 3: Phát triển Custom Hook `useEVEState` & Bộ đếm 30s Idle -> Sleeping
- [x] Xây dựng State machine quản lý trạng thái hiện tại của EVE.
- [x] Cấu hình bộ đếm thời gian tự động: Khi người dùng không tương tác trong 30 giây -> Chuyển sang `sleeping`.
- [x] Bắt sự kiện chạm vào màn hình khi EVE đang `sleeping` -> Kích hoạt `wakeup` (giật mình nảy người với dấu `!`) -> Chuyển về `idle`.

---

## 📋 Task 4: Xây dựng Audio Service, STT & Kết nối n8n Webhook
- [x] Cấu hình `expo-av` để ghi âm giọng nói người dùng qua nút Micro.
- [x] Chuyển giọng nói thành văn bản (STT).
- [x] Viết `n8nService.ts` để gửi POST request chứa văn bản lên n8n Webhook.
- [x] Xử lý phản hồi từ n8n: tự động phát file audio TTS trả về và cập nhật nhãn cảm xúc tương ứng cho EVE.

---

## 📋 Task 5: Tích hợp Expo Push Notification & Tự động Đọc Thông báo
- [x] Khởi tạo `notificationService.ts` lấy `ExpoPushToken` của thiết bị và đăng ký với n8n.
- [x] Đăng ký Listener nhận sự kiện Push Notification.
- [x] Bắt sự kiện người dùng bấm vào thông báo -> Mở App -> EVE chuyển từ `idle` sang `speaking` -> Tự động phát âm thanh thông báo.

---

## 📋 Task 6: Tích hợp Luồng Xác nhận 2 Bước (Confirmation) & Kiểm thử
- [x] Xử lý UI/UX hiển thị câu hỏi xác nhận khi n8n trả về `require_confirm: true`.
- [x] Hỗ trợ nút chọn nhanh ("Đồng ý" / "Hủy") hoặc nói trực tiếp qua Micro.
- [x] Kiểm thử toàn bộ luồng hoạt động (End-to-End) trên thiết bị thật / giả lập.

---

## 📋 Task 9: Chuyển sang Chế độ Pure Voice Chat Mode (Tắt hoàn toàn Camera Vision)
- [x] Xóa bỏ bộ đếm timer quét camera ngầm 3s trong `App.tsx`.
- [x] Gỡ bỏ `<CameraPreviewPiP />` khỏi cây JSX `App.tsx`.
- [x] Xóa bỏ bộ lọc Gaze check trong `handleSendAudio`.
- [x] Đơn giản hóa `handleSendMessage` gửi trực tiếp text đính kèm `current_person` (`image_base64: null`).
- [x] Khôi phục `current_person` từ `peopleDatabaseService.getCurrentUser()` khi ứng dụng khởi chạy và tự động phát báo cáo thông báo nếu là Admin.

---

## 📋 Task 10: Xây dựng Tính năng Hybrid Real-Human Talking Avatar (FEAT-HUMAN-AVATAR)
- [x] **Task 10.1**: Thu thập & Chuẩn bị 2 ảnh mẫu AI Girl chuẩn nét trong `assets/avatars/`.
- [x] **Task 10.2**: Viết WebGL Mesh Morphing & Viseme Audio Analyser Engine trong `human_avatar_interface.html` (sử dụng Google MediaPipe Face Mesh).
- [x] **Task 10.3**: Xây dựng UI Modal `AvatarSelectorModal.tsx` chọn Avatar & upload ảnh người thật (`expo-image-picker`).
- [x] **Task 10.4**: Thêm Nút Switch `AvatarSwitchToggle` trên `ControlPanel.tsx` để chuyển đổi mượt 🤖 Robot ↔️ 👩 Người thật.
- [x] **Task 10.5**: Tích hợp hook `useAvatarMode` lưu cấu hình `@eve_avatar_mode` & `@eve_human_avatar_config` vào `AsyncStorage`.
- [x] **Task 10.6**: Kiểm thử chuyển đổi Avatar, mấp máy môi khớp âm thanh tiếng Việt khi EVE đọc thông báo Push Notification.



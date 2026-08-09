# 1. Tổng quan Dự án (Project Overview) - EVE Mobile Voice AI Assistant

## 1.1. Mục tiêu Dự án (Project Goal)
Dự án **EVE Mobile Voice AI Assistant** là một ứng dụng di động thông minh tích hợp bộ não **n8n AI Agent**, cho phép tương tác 2 chiều với người dùng qua giọng nói chân thật và giao diện Robot EVE sống động với các biểu cảm cảm xúc.

Ứng dụng giúp người dùng:
1. **Nhận thông báo chủ động qua giọng nói**: Khi hệ thống quản lý đẩy sự kiện đến n8n, n8n bắn Push Notification tới điện thoại. Mở app ra, EVE sẽ tự động chuyển từ trạng thái `idle` sang `speaking` và đọc nội dung thông báo cho người dùng nghe bằng giọng nói sinh động.
2. **Ra lệnh bằng giọng nói**: Người dùng trò chuyện/ra lệnh trực tiếp cho EVE. EVE gửi văn bản (STT) lên n8n Webhook, n8n AI Agent phân loại lệnh (Direct vs High-Impact Require Confirmation) và trả về câu trả lời cùng chất giọng biểu cảm phù hợp.
3. **Cơ chế Xác nhận 2 bước (Two-Step Confirmation)**: Với các lệnh ảnh hưởng đến dữ liệu/tài chính, EVE sẽ chủ động hỏi lại và chờ người dùng xác nhận giọng nói ("Đồng ý" / "Hủy") trước khi n8n thực thi.

---

## 1.2. Đối tượng Người dùng & Kịch bản Sử dụng (Target Persona & Core Use Cases)

* **Đối tượng**: Quản lý hệ thống, cá nhân làm việc với quy trình tự động hóa n8n cần một Trợ lý ảo giao tiếp giọng nói 2 chiều rảnh tay.
* **Kịch bản 1: Nhận Notification chủ động (Push Notification -> Auto-Voice)**
  - Hệ thống n8n phát hiện thông báo/sự kiện mới -> Bắn Expo Push Notification.
  - Người dùng bấm vào thông báo trên điện thoại -> Màn hình EVE mở lên -> EVE chuyển từ `idle` sang `speaking` và phát âm thanh báo cáo.
* **Kịch bản 2: Giao tiếp & Ra lệnh giọng nói 2 chiều (Voice Command)**
  - Người dùng giữ/bấm nút Micro và nói lệnh.
  - Client ghi nhận và chuyển thành văn bản (STT).
  - Gửi POST sang n8n Webhook.
  - n8n xử lý và trả về phản hồi audio/text cùng nhãn cảm xúc (`happy`, `thinking`, `speaking`, `idle`, `sleeping`).
* **Kịch bản 3: Tự động Ngủ & Thức dậy (Idle / Sleep / Wakeup State)**
  - Không tương tác trong 30 giây -> EVE chuyển sang trạng thái `sleeping` (Visor tỏa sáng dịu, bong bóng `Zzz`).
  - Người dùng chạm vào màn hình -> EVE thực hiện hiệu ứng `wakeup` (giật mình nhẹ, đôi mắt chớp nảy với dấu `!`) rồi trở về `idle`.

---

## 1.3. Giao diện Robot EVE & Chế độ Avatar Người thật (EVE Expressions & Hybrid Avatar)
Ứng dụng hỗ trợ chuyển đổi linh hoạt giữa 2 chế độ hiển thị:
1. **Chế độ EVE Robot 🤖 (`eve_robot_interface.html`)**:
   - Đầu hình quả trứng bóng bẩy, thân hình giọt nước nhẵn nhụi với hiệu ứng lơ lửng `hover`.
   - Mắt SVG Neon glowing cyan (`#00f0ff`) trên nền Visor tối màu.
2. **Chế độ Người thật Nói chuyện 👩 (`human_avatar_interface.html`)**:
   - Nhân vật người thật (Mẫu AI Girl có sẵn hoặc Ảnh tải lên từ thư viện máy).
   - Sử dụng **Google MediaPipe Face Mesh (WebGL Canvas)** biến dạng khuôn mặt real-time: Mấp máy môi khớp giọng nói tiếng Việt (Lip-sync), chớp mắt tự nhiên 3-5s/lần và nghiêng đầu nhẹ theo trạng thái.

- **6 Trạng thái Cảm xúc đồng bộ cả 2 chế độ**:
  1. `idle`: Trạng thái chờ, chớp mắt chu kỳ 3-6s.
  2. `happy`: Cười khúc khích (`giggle-anim`) / Nụ cười rạng rỡ.
  3. `thinking`: Visor trượt xuống / Nghiêng đầu suy nghĩ, hiển thị `?`.
  4. `speaking`: Mắt co giãn / Môi mấp máy khớp theo biên độ sóng âm giọng nói.
  5. `sleeping`: Mắt nhắm, hào quang dịu, hiển thị `Zzz`.
  6. `wakeup`: Giật mình nảy người nhẹ (`startle`), hiển thị `!`, hạ chậm về `idle`.


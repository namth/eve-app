# 👁️ Feature Spec: Smart Vision AI & Dynamic Face Re-Learning System

Tài liệu nâng cấp tính năng **Vòng đời Ảnh Avatar đại diện & Cơ chế Tự học Gương mặt Mới khi Đính chính bằng Giọng nói (Dynamic Face Re-Learning)**.

---

## 📸 1. Vòng đời Chụp Ảnh Avatar Đại Diện (Avatar Lifecycle)

1. **Thời điểm Chụp Ảnh Lần Đầu (Initial Enrollment Avatar)**:
   - Ngay khi người dùng hoàn tất đăng ký thông tin (`enrollNewPerson`), EVE tự động chụp 1 tấm ảnh snapshot Base64 nhẹ từ camera phần cứng và lưu làm `avatar_base64` trong CSDL local (`@eve_people_db`).
2. **Truyền Avatar làm Chuẩn So sánh cho n8n**:
   - Khi gửi mảng `registered_people` lên n8n Webhook, đính kèm `avatar_base64` của từng người quen.
   - n8n Gemini 1.5 Flash Vision nhận tấm ảnh mới chụp và đối chiếu trực tiếp với `avatar_base64` trong mảng để chốt danh tính chuẩn xác 99.9%.

---

## 🧠 2. Cơ chế Tự Học Gương Mặt Mới khi Đính chính Giọng nói (Dynamic Face Re-Learning)

Khi người dùng thay đổi ngoại hình (cắt tóc, đeo kính, góc tối) khiến EVE chưa nhận ra:
1. **Người dùng cất giọng đính chính**: *"Anh Nam đây mà"* hoặc *"Sửa tên thành Thảo"*.
2. **n8n LLM phát hiện câu đính chính**: Trả về `update_person: { "name": "Nam", "role": "admin" }`.
3. **EVE tự động Chụp & Đè Ảnh Mới (Dynamic Face Re-Learning)**:
   - App lập tức chụp 1 tấm ảnh snapshot góc mặt hiện tại và **đè trực tiếp vào trường `avatar_base64`** trong CSDL local (`peopleDatabaseService.savePersonProfile()`).
   - Lần sau khi bạn xuất hiện với ngoại hình mới, EVE sẽ nhận diện ngay lập tức với tấm ảnh vừa học được!

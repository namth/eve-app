# 👁️ Feature Spec: 3-Second Local Pre-Check & Message Pre-Flight Vision Trigger

Tài liệu thiết kế quy trình **Quét Sơ bộ Local 3s/lần & Kiểm tra Trước khi Gửi Message (Local Pre-Flight Inspection)**.

---

## 🎯 1. Chi tiết Kiến trúc Hoạt động

1. **Vòng lặp Quét Sơ bộ Local 3s/lần (100% Offline trên chip điện thoại)**:
   - Khi EVE ở trạng thái `idle`, một bộ đếm ngầm sẽ chạy mỗi **3.0 giây**.
   - Mỗi 3s, ứng dụng chạy hàm **Kiểm tra Sơ bộ Local** trên thiết bị (< 10ms, không tốn chút mạng hay chi phí nào):
     - `isFaceDetected === true`: Kiểm tra có khuôn mặt người trong ảnh không.
     - `isLookingAtEVE === true`: Kiểm tra góc quay mặt hướng về EVE.
2. **Quyết định Gửi Server (Network Trigger Decision)**:
   - 🔴 **NẾU KHÔNG THẤY KHUÔN MẶT / QUAY MẶT ĐI / BỊT CAMERA**:
     - Kết quả local check = `false`.
     - App **KHÔNG gửi bất kỳ dữ liệu nào lên server n8n**! EVE giữ im lặng ở trạng thái `idle`.
   - 🟢 **NẾU PHÁT HIỆN THẤY KHUÔN MẶT ĐANG NHÌN VÀO EVE**:
     - Kết quả local check = `true`.
     - App mới chụp 1 tấm ảnh nén **width: 320px (~15KB)** và gửi request lên n8n Webhook `/webhook/eve-chat`.
3. **Kiểm tra Sơ bộ Local Trước khi Gửi Message (Pre-Flight Inspection)**:
   - Khi bạn cất tiếng nói STT hay gõ message ➔ App cũng chạy Kiểm tra Sơ bộ Local trước.
   - Nếu không có người hoặc quay mặt đi ➔ App **ngay lập tức HỦY gửi lên server**, bảo vệ băng thông và tiết kiệm token!
   - Nếu đạt điều kiện ➔ Mới chụp ảnh 320px và đính kèm `registered_people` + `current_person` gửi n8n AI Vision!

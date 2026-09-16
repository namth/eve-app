# 📚 Bộ Tài Liệu Kiến Trúc & Vận Hành Hệ Thống EVE AI Assistant

Chào mừng bạn đến với bộ tài liệu kỹ thuật toàn diện của hệ thống **EVE AI Assistant (Face Detector & Voice AI Robot)**. Hệ thống kết hợp giữa thị giác máy tính on-device (CameraX + FaceNet), trợ lý giọng nói tương tác 2 chiều (VAD + STT + TTS), bộ não tự động hóa n8n và **Local AI Agent (OpenRouter `gpt-4o-mini`)** với cơ chế bảo mật thị giác sinh trắc học.

---

## 🗂️ Danh Mục Tài Liệu Chi Tiết

| Tài liệu | Mô tả nội dung |
| :--- | :--- |
| **[1. Kiến Trúc Hệ Thống (SYSTEM_ARCHITECTURE.md)](./SYSTEM_ARCHITECTURE.md)** | Bức tranh tổng thể, kiến trúc 5 tầng thành phần, sơ đồ khối phần cứng & phần mềm, chi tiết cấu trúc mã nguồn. |
| **[2. Các Luồng Hoạt Động (SYSTEM_FLOWS.md)](./SYSTEM_FLOWS.md)** | Chi tiết 6 luồng nghiệp vụ cốt lõi: Nhận Push Notification, Nhận diện Admin/Người lạ, Gộp thông báo ngữ nghĩa, Đàm thoại giọng nói, Phân loại ý định, và Fallback an toàn. |
| **[3. Hợp Đồng Dữ Liệu & API (API_CONTRACTS.md)](./API_CONTRACTS.md)** | Định dạng JSON chi tiết cho OpenRouter LLM REST API, Firebase Cloud Messaging (FCM), n8n Webhook và Cấu trúc SQLite Database. |
| **[4. Hướng Dẫn Cấu Hình & Tích Hợp (SETUP_GUIDE.md)](./SETUP_GUIDE.md)** | Hướng dẫn từng bước kết nối Firebase Console, n8n Workflow, thiết lập API Key OpenRouter và kiểm thử thực tế. |

---

## 🌟 Điểm Nhấn Kiến Trúc

1. **Visual Privacy Gatekeeper (Bảo vệ Bảo mật Thị giác)**:
   - Dù có hàng chục thông báo quan trọng gửi về máy, nếu người đứng trước camera là **Người lạ / Khách**, EVE chỉ chào hỏi xã giao thông thường và **giữ bí mật tuyệt đối** về mọi thông báo.
   - Chỉ khi **Admin (Nam)** xuất hiện trước camera, hệ thống mới mở khóa báo cáo.
2. **Local AI Agent (Semantic Aggregator & Classifier)**:
   - Sử dụng mô hình `openai/gpt-4o-mini` qua OpenRouter REST API với tốc độ phản hồi tức thì (~300 - 600ms).
   - Phân tích ngữ nghĩa để gom nhóm các thông báo có cùng bản chất (dù câu chữ khác nhau do AI sinh ra), tóm tắt thành 1 bài báo cáo quản gia chuẩn mực tiếng Việt.
   - Phân loại ý định xác nhận (hiểu tiếng lóng tiếng Việt) và trích xuất danh tính tự nhiên.
3. **Event-Driven Push Notification (n8n ➔ FCM ➔ SQLite)**:
   - Đẩy thông báo tức thì từ n8n qua Google Firebase Cloud Messaging v1.
   - Hoạt động ổn định ngay cả khi app đang tắt, chạy nền hoặc màn hình khóa.
   - Lưu trữ hàng đợi thông báo chưa đọc vào SQLite nội bộ trên thiết bị.

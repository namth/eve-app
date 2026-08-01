# 🔄 Feature Spec: Smart Person Sync & n8n Array Response Parser

Tài liệu thiết kế quy trình **Xử lý Mảng JSON Response `[ { output: { person: ... } } ]` & Kiểm tra Khác biệt (Diff Check) khi Đồng bộ Người dùng**.

---

## 🎯 1. Chi tiết Nâng cấp Kỹ thuật

1. **Parser Phản hồi Mảng Array linh hoạt (`n8nService.ts`)**:
   - Tự động bóc tách kể cả khi n8n server trả về mảng array `[ { output: { status: "success", person: { ... }, reply_text: ..., emotion: ... } } ]` hay JSON Object trực tiếp `{ person: { ... } }`.
2. **Đồng bộ Người dùng & Kiểm tra Khác biệt (Diff Check - `peopleDatabaseService.syncPersonFromResponse`)**:
   - Khi n8n trả về đối tượng `person` hoặc `update_person`:
     - App đối chiếu tên người dùng với CSDL local (`@eve_people_db`).
     - **Nếu ĐÃ CÓ trong CSDL local**:
       - So sánh tất cả các trường (`name`, `age`, `gender`, `preferred_pronoun`, `role`).
       - **NẾU KHÔNG CÓ THAY ĐỔI (No diff)** ➔ Bỏ qua việc ghi đĩa đè (`Skipping disk write!`), giữ nguyên CSDL và cập nhật `currentPerson`.
       - **NẾU CÓ THAY ĐỔI** ➔ Ghi đè thông tin mới vào CSDL local và cập nhật `currentPerson`.
     - **Nếu CHƯA CÓ trong CSDL local**:
       - Tự động thêm mới vào Danh sách Bạn bè với `role: 'friend'` (hoặc `admin` nếu tên là Nam).
       - Đặt người đó làm `currentPerson` và lưu vào đĩa cứng (`@eve_current_person`).

---

## 🧪 2. Kết quả Kiểm thử (Verification Results)

- **TypeScript Compilation Check**:
  - Command: `npx tsc --noEmit`
  - Result: **Clean build, 0 errors**.

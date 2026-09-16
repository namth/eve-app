# 🛠️ Hướng Dẫn Cài Đặt & Vận Hành Hệ Thống (Setup Guide)

Tài liệu này hướng dẫn chi tiết từng bước để cài đặt, cấu hình và kiểm thử hệ thống **EVE AI Assistant** kết nối với **Firebase Cloud Messaging** và **n8n Workflow**.

---

## 📋 Bước 1: Cấu hình Firebase & Lấy Service Account Private Key

Dự án Android đã tích hợp sẵn file `app/google-services.json` thuộc Firebase Project: **`eve-agent-7c30d`**.

Để cho phép máy chủ n8n của bạn có quyền đẩy thông báo tới Google Firebase:
1. Truy cập vào [Firebase Console](https://console.firebase.google.com/).
2. Đăng nhập và chọn Project **`eve-agent-7c30d`**.
3. Bấm vào biểu tượng **Bánh răng (Project Settings)** ở góc trên thanh menu bên trái.
4. Chuyển sang tab **Service accounts**.
5. Bấm nút **Generate new private key** (Tạo khóa riêng tư mới) và xác nhận.
6. Trình duyệt sẽ tải về một file JSON có tên dạng `eve-agent-7c30d-firebase-adminsdk-....json`.
   > ⚠️ **Lưu ý bảo mật:** Giữ file này an toàn vì nó chứa quyền gửi tin nhắn FCM từ server của bạn.

---

## 📱 Bước 2: Build & Khởi Chạy Ứng Dụng EVE trên Thiết Bị

1. Mở dự án trong **Android Studio** (thư mục `/face-detector`).
2. Kết nối điện thoại hoặc máy tính bảng Android qua dây cáp USB (hoặc Wifi ADB Debugging).
3. Bấm nút **Run (Tam giác xanh)** để biên dịch và cài đặt ứng dụng lên máy.
4. Khi ứng dụng mở lần đầu:
   - Cấp quyền **Camera** (để EVE quét khuôn mặt).
   - Cấp quyền **Micro** (để EVE nghe giọng nói).
   - Cấp quyền **Thông báo (Notification)** (cho Android 13+).

---

## 🔑 Bước 3: Lấy FCM Registration Token trên Thiết Bị

1. Trên màn hình ứng dụng EVE, bấm vào biểu tượng **Cài đặt (⚙️)** ở góc trên.
2. Cuộn xuống thẻ **Firebase Cloud Messaging**:
   - Bạn sẽ thấy dòng: `Token: c...:APA91bF...`
3. Bấm nút **"Sao chép Token"**.
4. Gửi hoặc lưu chuỗi token này lại để cấu hình vào n8n.

---

## ⚡ Bước 4: Cấu Hình n8n Gửi Thông Báo Về EVE

Bạn có thể cấu hình trong n8n bằng 1 trong 2 cách sau:

### Cách A: Dùng Node "Google Firebase Cloud Messaging" trong n8n
1. Trong giao diện n8n, thêm node **Firebase Cloud Messaging**.
2. Chọn **Resource:** `Message`, **Operation:** `Send`.
3. Bấm tạo Credential mới:
   - Dán toàn bộ nội dung file JSON Service Account tải ở Bước 1 vào mục **Service Account Key**.
4. Thiết lập tham số gửi:
   - **Target:** `Token`
   - **To:** Dán **FCM Token** của máy EVE lấy ở Bước 3.
   - **Title:** `Cảnh báo hệ thống`
   - **Body:** `Camera sân trước phát hiện có shipper bấm chuông cửa`

---

### Cách B: Test Nhanh Bằng cURL Từ Terminal Hoặc Postman

Nếu bạn muốn kiểm tra ngay lập tức xem EVE có nhận được thông báo không, bạn có thể chạy script python nhỏ gọn sử dụng Service Account:

```python
import json
import requests
from google.oauth2 import service_account
import google.auth.transport.requests

# 1. Đường dẫn file Service Account tải ở Bước 1
SERVICE_ACCOUNT_FILE = 'eve-agent-7c30d-firebase-adminsdk.json'
FCM_TOKEN = 'DÁN_FCM_TOKEN_CỦA_MÁY_EVE_Ở_BƯỚC_3'

# 2. Lấy Google OAuth2 Access Token
credentials = service_account.Credentials.from_service_account_file(
    SERVICE_ACCOUNT_FILE,
    scopes=['https://www.googleapis.com/auth/firebase.messaging']
)
request = google.auth.transport.requests.Request()
credentials.refresh(request)
access_token = credentials.token

# 3. Gửi thông báo qua FCM v1
url = 'https://fcm.googleapis.com/v1/projects/eve-agent-7c30d/messages:send'
headers = {
    'Authorization': f'Bearer {access_token}',
    'Content-Type': 'application/json; UTF-8'
}
payload = {
    "message": {
        "token": FCM_TOKEN,
        "notification": {
            "title": "Cảnh báo cảm biến",
            "body": "Nhiệt độ phòng làm việc vừa tăng lên 32 độ C"
        },
        "data": {
            "priority": "high"
        }
    }
}

response = requests.post(url, headers=headers, json=payload)
print("FCM Response:", response.status_code, response.text)
```

---

## 🧪 Bước 5: Kịch Bản Kiểm Thử Hoạt Động Thực Tế (End-to-End Testing)

Để kiểm chứng toàn bộ các tiêu chí:

### Kịch bản 1: Thử nghiệm Bảo mật Thị giác (Người lạ)
1. Dùng n8n gửi 2 thông báo bất kỳ về thiết bị EVE (ví dụ: *"Có người bấm chuông"* và *"Bưu phẩm đã giao"*).
2. Thiết bị phát ra tiếng ting chuông báo và hiển thị 2 thông báo trên thanh trạng thái Android.
3. Cho một người lạ (chưa đăng ký khuôn mặt) đứng trước camera.
4. **Kết quả đạt chuẩn:** EVE mỉm cười chào hỏi lịch sự: *"Em chào anh/chị ạ! Cho em biết tên của anh/chị có được không?"*. EVE **tuyệt đối không đọc bất kỳ thông tin nào** về 2 thông báo kia.

### Kịch bản 2: Thử nghiệm Báo cáo Quản gia (Admin Nam) & Gộp Ngữ Nghĩa
1. Tiếp tục gửi thêm 1 thông báo tương tự: *"Camera phát hiện shipper đang đứng trước cửa"*.
   *(Lúc này trong SQLite có 3 thông báo: 2 thông báo về shipper/chuông cửa và 1 thông báo bưu phẩm)*.
2. Bạn (Admin Nam) bước tới trước camera.
3. **Kết quả đạt chuẩn:**
   - Camera nhận diện: `👤 Anh Nam (94%)`.
   - EVE đổi biểu cảm sang `thinking`, thanh trạng thái hiển thị: *"EVE đang tổng hợp thông báo cho Anh Nam..."*.
   - Chỉ sau ~0.4 giây, Local AI Agent (`gpt-4o-mini`) phân tích ngữ nghĩa, gộp 2 thông báo chuông cửa/shipper làm một và EVE mỉm cười phát giọng nói:
     > *"Dạ anh Nam ơi, em xin phép báo cáo: Cổng trước có 2 cảnh báo về người bấm chuông và shipper vừa tới, đồng thời bưu phẩm của anh cũng đã được giao. Hết ạ!"*
   - Thanh thông báo trên điện thoại tự động được dọn sạch và các thông báo trong SQLite được đánh dấu `is_read = 1`.

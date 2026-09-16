# 🔄 Các Luồng Hoạt Động Cốt Lõi Của Hệ Thống (System Flows)

Tài liệu này mô tả chi tiết 6 luồng hoạt động nghiệp vụ (Business & Technical Flows) của hệ thống **EVE AI Assistant**, bao gồm sơ đồ tuần tự (Sequence Diagram), các điều kiện kích hoạt, cơ chế chuyển trạng thái và cách xử lý ngoại lệ.

---

## 📥 Luồng 1: Nhận Push Notification từ n8n qua FCM & Lưu trữ Hàng Đợi

### Sơ đồ tuần tự:
```mermaid
sequenceDiagram
    autonumber
    participant Event as Hệ thống bên ngoài (IoT/Sensor/Camera)
    participant n8n as n8n Workflow Automation
    participant FCM as Google Firebase Cloud Messaging
    participant FcmSrv as EveFirebaseMessagingService (Android)
    participant SQLite as EveDatabaseHelper (notifications)
    participant SysTray as Thanh Trạng Thái Android

    Event->>n8n: Kích hoạt sự kiện (ví dụ: cảnh báo cửa mở, shipper tới)
    n8n->>n8n: AI Agent xử lý nội dung & tạo thông báo
    n8n->>FCM: Gửi HTTP POST FCM v1 (kèm FCM Token máy EVE)
    FCM->>FcmSrv: Đẩy Push Notification (High Priority)
    Note over FcmSrv: Đánh thức App ngầm nếu đang ngủ
    FcmSrv->>FcmSrv: Trích xuất title, body, data
    FcmSrv->>SQLite: insertNotification(id, title, body, data, is_read=0)
    FcmSrv->>SysTray: Hiển thị Android Notification Banner (Ringtone + Icon)
```

### Các bước xử lý:
1. Khi có sự kiện (cảm biến phát hiện chuyển động, camera AI nhận diện người lạ, chuông cửa reo, máy chủ quá nhiệt...), n8n sinh ra một thông báo.
2. n8n gọi Firebase Cloud Messaging API v1 đẩy tin nhắn trực tiếp đến FCM Registration Token của máy EVE.
3. `EveFirebaseMessagingService` trên thiết bị Android nhận gói tin ngay cả khi ứng dụng đang đóng hoàn toàn (Cold) hoặc màn hình đang tắt.
4. Thông báo được lưu ngay lập tức vào bảng `notifications` trong SQLite nội bộ với cờ `is_read = 0`.
5. Tạo một System Notification trên thanh trạng thái với PendingIntent trỏ về `MainActivity` (kèm cờ `EXTRA_FROM_NOTIFICATION = true`).

---

## 👁️ Luồng 2: Nhận Diện Khuôn Mặt & Bảo Vệ Bảo Mật Thị Giác (Visual Privacy Gatekeeper)

Đây là cơ chế bảo mật sinh trắc học cốt lõi: **Ai đứng trước camera quyết định EVE được phép nói những gì**.

```mermaid
flowchart TD
    START([Camera phát hiện khuôn mặt]) --> DETECT[ML Kit trích xuất BoundingBox & Căn chỉnh góc xoay]
    DETECT --> EXTRACT[MobileFaceNet tạo vector đặc trưng 192 chiều]
    EXTRACT --> MATCH{So khớp Cosine Sim với DB SQLite}

    MATCH -->|Sim < 0.78| UNKNOWN[Khuôn mặt Người Lạ / Chưa đăng ký]
    MATCH -->|Sim >= 0.78| KNOWN[Nhận diện Người Quen trong danh bạ]

    UNKNOWN --> CHECK_GENDER[GenderClassifier dự đoán Nam hay Nữ]
    CHECK_GENDER --> GREET_STRANGER["👋 Chào hỏi xã giao lịch sự:
    'Em chào anh/chị ạ! Cho em biết tên được không?'"]
    GREET_STRANGER --> STRANGER_PRIVACY["🔒 BẢO MẬT TUYỆT ĐỐI:
    • KHÔNG đọc thông báo nào
    • Giữ nguyên hàng đợi PENDING trong SQLite"]

    KNOWN --> CHECK_ROLE{Kiểm tra vai trò: person.role}
    CHECK_ROLE -->|role == 'friend'| GREET_FRIEND["👋 Chào bạn bè bình thường:
    'Dạ em chào bạn/chị [Tên]!'"]
    GREET_FRIEND --> STRANGER_PRIVACY

    CHECK_ROLE -->|role == 'admin'| ADMIN_TRIGGER["👑 NHẬN DIỆN ĐÚNG ADMIN (Nam):
    Kích hoạt Luồng 3: Báo cáo Quản gia (Executive Briefing)"]
```

---

## 👑 Luồng 3: Báo Cáo Quản Gia (Executive Briefing) & Gộp Ngữ Nghĩa Bằng Local AI Agent

Luồng này kích hoạt khi **Admin** xuất hiện trước camera (bất kể Admin mở app từ việc bấm vào Notification hay mở thẳng app trực tiếp từ màn hình chính).

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Admin (Nam)
    participant Main as MainActivity
    participant Vision as EveVisionTracker
    participant SQLite as EveDatabaseHelper
    participant Agent as LocalAiAgentService
    participant OpenRouter as OpenRouter (openai/gpt-4o-mini)
    participant UI as EveWebViewHelper (Canvas Robot)
    participant TTS as VoiceAssistantManager (Google TTS)

    Admin->>Vision: Xuất hiện trước camera
    Vision->>Main: onPersonGreeted(person: role = 'admin')
    Main->>SQLite: getPendingNotifications()
    SQLite-->>Main: Trả về danh sách N thông báo chưa đọc

    alt N = 0 (Không có thông báo mới)
        Main->>TTS: "Dạ em chào anh Nam!" (Emotion: happy)
    else N = 1 (Chỉ có 1 thông báo duy nhất)
        Main->>TTS: "Dạ em chào anh Nam! Anh có một thông báo mới là: [Nội dung]. Hết ạ!"
        Main->>SQLite: markNotificationsAsRead([id])
        Main->>Main: NotificationManager.cancelAll()
    else N >= 2 (Có từ 2 thông báo trở lên -> Cần gộp ngữ nghĩa)
        Main->>UI: setEmotion('thinking')
        Main->>Main: Hiển thị trạng thái: "EVE đang tổng hợp thông báo cho anh Nam..."
        Main->>Agent: summarizeNotifications(adminName, pronoun, notifications, dbHelper)
        Agent->>OpenRouter: POST /chat/completions (System Prompt ngữ nghĩa + JSON danh sách thô)
        OpenRouter-->>Agent: Trả về câu tóm tắt đã gộp việc (latency ~400ms)
        Agent-->>Main: "Dạ anh Nam ơi, em xin phép báo cáo: Cổng trước có 2 cảnh báo về người bấm chuông và shipper giao đồ ăn lúc 18h, ngoài ra hệ thống sao lưu đã hoàn tất. Hết ạ!"
        Main->>UI: setEmotion('happy')
        Main->>TTS: Phát giọng đọc to toàn văn bản tóm tắt
        Main->>SQLite: markNotificationsAsRead([tất cả ID đã gộp])
        Main->>Main: NotificationManager.cancelAll()
    end
```

---

## 🎙️ Luồng 4: Đàm Thoại Giọng Nói Hai Chiều (Hands-Free Voice Interaction)

```mermaid
sequenceDiagram
    autonumber
    actor User as Người dùng
    participant Mic as VoiceAssistantManager (Microphone)
    participant Main as MainActivity
    participant N8n as n8n AI Chat Webhook
    participant Agent as LocalAiAgentService (Fallback)
    participant UI as EveWebViewHelper
    participant TTS as Google TTS Speaker

    Note over Mic: VAD (Voice Activity Detection) lắng nghe liên tục
    User->>Mic: Cất giọng nói (ví dụ: "EVE ơi thời tiết hôm nay thế nào?")
    Mic->>UI: setEmotion('thinking')
    Mic->>Main: onSpeechResult(transcript)
    
    Main->>N8n: Gửi POST webhook kèm context, personProfile, sessionId
    alt n8n phản hồi thành công
        N8n-->>Main: N8nChatResponse (replyText, emotion, action, audioUrl)
        Main->>UI: setEmotion(response.emotion)
        Main->>TTS: speak(response.replyText)
    else n8n timeout hoặc lỗi mạng
        Main->>Agent: parseSystemAction(transcript, currentPerson, dbHelper)
        Agent-->>Main: LocalAiActionResult (replyText, emotion, action)
        Main->>UI: setEmotion(action.emotion)
        Main->>TTS: speak(action.replyText)
    end

    Note over TTS,User: Tính năng Barge-in: Nếu người dùng nói chen ngang khi EVE đang phát TTS, EVE lập tức dừng loa và quay lại lắng nghe!
```

---

## 🤝 Luồng 5: Phân Loại Ý Định Xác Nhận Bằng Ngữ Nghĩa (Semantic Confirmation)

Áp dụng khi hệ thống cần xác nhận người trùng tên (Same-name Disambiguation) hoặc phát hiện xung đột danh tính (Identity Conflict).

```mermaid
flowchart TD
    START[EVE hỏi người dùng: 'Có phải là Anh Nam em gặp hôm qua không?'] --> USER_REPLY[Người dùng trả lời câu nói tự nhiên]
    USER_REPLY --> LOCAL_AI[LocalAiAgentService.classifyConfirmationIntent]
    LOCAL_AI --> CALL_OPENROUTER[Gửi Prompt ngữ cảnh + câu nói tới gpt-4o-mini]

    CALL_OPENROUTER --> CLASSIFY{Phân loại ngữ nghĩa}

    CLASSIFY -->|CONFIRMED| DO_CONFIRM["✅ CONFIRMED:
    • Ví dụ: 'Chuẩn rồi', 'Chuẩn cơm mẹ nấu', 'Anh chứ ai', 'Không, đúng đấy'
    • Nạp thêm góc mặt vào bộ 9 vector của hồ sơ
    • EVE: 'Dạ em nhận ra anh rồi! Em đã ghi nhớ thêm góc mặt này ạ.'"]

    CLASSIFY -->|DENIED| DO_DENY["❌ DENIED:
    • Ví dụ: 'Sai bét rồi', 'Nhầm to rồi bé ơi', 'Không phải anh đâu'
    • Kiểm tra ứng viên tiếp theo hoặc tạo hồ sơ người mới cùng tên
    • EVE: 'A hóa ra là một anh Nam mới! Em đã tạo hồ sơ riêng cho anh ạ.'"]

    CLASSIFY -->|AMBIGUOUS| DO_AMBIGUOUS["❓ AMBIGUOUS:
    • Người dùng nói lạc đề hoặc tiếng ồn
    • EVE hỏi lại nhẹ nhàng: 'Dạ em chưa nghe rõ lắm, có phải là anh...'"]
```

---

## 🛡️ Luồng 6: Cơ Chế Dự Phòng An Toàn (Fail-Safe & Offline Mode)

Hệ thống được thiết kế theo nguyên tắc **Zero-Block / Never Crash**:

| Sự cố phát sinh | Cách hệ thống tự động xử lý (Graceful Fallback) |
| :--- | :--- |
| **Mất kết nối Internet khi Admin xuất hiện** | `LocalAiAgentService` phát hiện lỗi kết nối ➔ Lập tức kích hoạt `fallbackLocalNotificationSummary`: EVE tự động đếm số lượng thông báo và liệt kê *"Một là: ... Hai là: ... Hết ạ!"* bằng giọng đọc offline của máy. Không bao giờ bị im lặng. |
| **OpenRouter API hết hạn ngạch hoặc lỗi HTTP 429/500** | Bắt ngoại lệ trong `try-catch`, tự động chuyển sang bộ xử lý nội bộ cục bộ trong vòng 0.1 giây. |
| **n8n Server bảo trì hoặc offline** | Khi người dùng ra lệnh bằng giọng nói, app kích hoạt `LocalAiAgentService.parseSystemAction` để nhận diện các lệnh khẩn cấp (tắt app, cập nhật mặt, cử chỉ) mà không phụ thuộc vào n8n. |
| **Người dùng đứng trước camera nhưng không nói gì (No Speech)** | Lần 1: EVE nhắc nhở nhẹ nhàng: *"Anh hãy nói gì đi, em đang nghe đây ạ"*. Lần 2: EVE hoàn toàn im lặng, tự động dừng mic để không làm phiền người dùng. |

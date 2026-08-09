# 2. Kiến trúc Hệ thống (System Architecture) - EVE Mobile Voice AI

## 2.1. Sơ đồ Kiến trúc Tổng quan (System Diagram)

```mermaid
flowchart TD
    subgraph MobileApp ["📱 Mobile App (React Native / Expo)"]
        UI["Avatar UI Canvas / WebView (Robot SVG / Human WebGL MediaPipe Engine)"]
        STT["Mobile STT (Voice to Text)"]
        TTSPlayer["Expo Audio Player (TTS Audio Stream)"]
        PushHandler["Expo Notifications Listener"]
        AvatarConfig["AsyncStorage (@eve_avatar_mode, @eve_human_avatar_config)"]
        StateManager["App State & Timeout Controller (30s Idle->Sleep)"]
    end

    subgraph N8N_Backend ["🧠 n8n Automation & AI Agent Workflows"]
        PushTriggerWorkflow["Workflow 1: Event Listener & Push Trigger"]
        ChatWebhookWorkflow["Workflow 2: Voice Chat & Command Executor"]
        
        subgraph N8N_Nodes ["n8n Internal Nodes"]
            IntentClassifier["Intent & Severity Classifier Node"]
            MemoryNode["Window Buffer / Redis Session Memory"]
            AIAgentNode["AI Agent (LLM Node - OpenAI / Claude / Gemini)"]
            TTSNode["TTS Synthesizer Node (ElevenLabs / OpenAI Voice / Native Fallback)"]
            ExpoPushNode["HTTP Request Node (Expo Push API)"]
        end
    end

    %% Notification Flow
    PushTriggerWorkflow -->|Send Push Payload| ExpoPushNode
    ExpoPushNode -->|Push Notification| PushHandler
    PushHandler -->|User Taps Push| StateManager
    StateManager -->|Set Expression: speaking| UI
    StateManager -->|Play Audio| TTSPlayer

    %% Voice Interaction Flow
    STT -->|User Speech Text| ChatWebhookWorkflow
    ChatWebhookWorkflow --> IntentClassifier
    IntentClassifier -->|Direct Query| AIAgentNode
    IntentClassifier -->|High Impact Action| MemoryNode
    MemoryNode -->|Save Pending Action & Token| AIAgentNode
    AIAgentNode --> TTSNode
    TTSNode -->|Return Text + Emotion + Audio URL| UI
    TTSNode -->|Play Voice| TTSPlayer
```

---

## 2.2. Máy Trạng thái Giao diện EVE (UI State Machine)

```mermaid
stateDiagram-v2
    [*] --> Idle: App Launch / System Init

    Idle --> Speaking: Nhận Push Notification & Tap vào
    Idle --> Thinking: Người dùng ra lệnh bằng Giọng nói (STT gửi n8n)
    Idle --> Sleeping: Sau 30s không có tương tác người dùng

    Thinking --> Speaking: n8n trả về kết quả Audio + Emotion
    Thinking --> Happy: n8n hoàn thành tác vụ thành công
    Thinking --> Idle: Lỗi kết nối / Hủy lệnh

    Speaking --> Idle: Phát hết âm thanh (Audio ended)
    Speaking --> Happy: Phản hồi tin vui / Cảm xúc tích cực

    Happy --> Idle: Sau 3s hiển thị biểu cảm vui

    Sleeping --> Wakeup: Người dùng chạm màn hình / Ra lệnh mới
    Wakeup --> Idle: Sau 2.2s hoàn thành animation thức dậy (startle + !)
```

---

## 2.3. Luồng Xác nhận 2 Bước (Two-Step Confirmation Architecture)

```mermaid
sequenceDiagram
    autonumber
    actor User as Người dùng
    participant App as Mobile App (EVE)
    participant n8n as n8n Webhook
    participant Agent as n8n AI Agent
    participant Storage as Session Memory

    User->>App: Ra lệnh bằng giọng nói (Ví dụ: "Gửi báo cáo cho sếp")
    App->>n8n: POST /webhook/chat { user_id, message }
    n8n->>Agent: Phân tích Intent & Severity Level
    Agent-->>Storage: Nhận diện lệnh High-Impact -> Lưu session_id + pending_action
    n8n-->>App: Res: { reply_text: "Tôi chuẩn bị gửi báo cáo cho sếp. Bạn có chắc chắn không?", emotion: "thinking", require_confirm: true, session_id: "xyz" }
    App->>User: EVE đọc câu hỏi xác nhận (Biểu cảm thinking + ?)

    alt Người dùng xác nhận
        User->>App: Nói: "Đồng ý" / "Xác nhận"
        App->>n8n: POST /webhook/chat { user_id, message: "Đồng ý", session_id: "xyz" }
        n8n->>Storage: Lấy pending_action -> Thực thi tác vụ thực sự
        n8n-->>App: Res: { reply_text: "Đã gửi báo cáo thành công!", emotion: "happy", require_confirm: false }
        App->>User: EVE đọc kết quả (Biểu cảm happy)
    else Người dùng hủy
        User->>App: Nói: "Hủy" / "Không cần nữa"
        App->>n8n: POST /webhook/chat { user_id, message: "Hủy", session_id: "xyz" }
        n8n->>Storage: Xóa pending_action
        n8n-->>App: Res: { reply_text: "Đã hủy lệnh thành công.", emotion: "idle", require_confirm: false }
        App->>User: EVE trở về trạng thái idle
    end
```

---

## 2.4. Luồng Thông báo Đẩy (Expo Push Notification Workflow)

1. **Khởi tạo Token**: Khi mở App lần đầu, App dùng `expo-notifications` lấy `ExpoPushToken` của thiết bị.
2. **Đăng ký với n8n**: App gửi POST sang n8n `/webhook/register-device` lưu `user_id` và `push_token`.
3. **Trigger Thông báo**: Khi n8n phát hiện sự kiện mới từ hệ thống quản lý, n8n gọi POST tới `https://exp.host/--/api/v2/push/send` với payload:
   ```json
   {
     "to": "ExponentPushToken[xxxxxxxx]",
     "title": "EVE Notification",
     "body": "Có báo cáo mới cần duyệt",
     "data": {
       "action": "speak_notification",
       "audio_url": "https://your-server.com/audio/notif_123.mp3",
       "text": "Có báo cáo doanh thu mới cần duyệt, bạn có muốn xem không?"
     }
   }
   ```
4. **App Response**: Người dùng bấm thông báo -> App bắt `data.audio_url` & `data.text` -> Chuyển EVE sang `speaking` và phát audio ngay lập tức.

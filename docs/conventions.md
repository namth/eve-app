# 4. Quy chuẩn Cấu trúc & Tech Stack (Conventions & Stack Specification)

Tài liệu này quy định cấu trúc mã nguồn, công nghệ sử dụng và các thư viện bắt buộc trong dự án **EVE Mobile Voice AI Assistant**.

---

## 4.1. Chi tiết Tech Stack & Thư viện (Technology Stack)

* **Framework**: **React Native (Expo)** với **TypeScript** (Strict Mode).
* **UI Engine cho EVE Robot**: **WebView Engine (`react-native-webview`)**
  * Tích hợp trực tiếp file HTML/CSS/SVG từ `eve_robot_interface.html`.
  * Đảm bảo hiệu ứng sóng âm LED, chuyển động lơ lửng 60fps, mắt SVG Neon và bóng chiếu mượt mà chuẩn xác 100% như bản gốc thiết kế.
  * Giao tiếp giữa React Native và WebView thông qua `postMessage` / `onMessage`.
* **Âm thanh & Thu âm (Audio Service)**: `expo-av` (hỗ trợ Recording microphone & Audio Playback URL/file).
* **Nhận diện Giọng nói (STT)**: `expo-speech-recognition` / `@react-native-voice/voice` hoặc Web Speech API bridge.
* **Thông báo Đẩy (Push Notifications)**: `expo-notifications` & `expo-device`.
* **Quản lý Cấu hình (.env)**: `react-native-dotenv` hoặc Expo public env (`EXPO_PUBLIC_N8N_WEBHOOK_URL`).

---

## 4.2. Quy chuẩn Cấu trúc Thư mục (Directory Structure)

```
eve-app/
├── docs/                      # Tài liệu kiến trúc dự án
│   ├── overview.md
│   ├── architecture.md
│   ├── api-contracts.md
│   ├── conventions.md
│   └── tasks.md
├── assets/                    # Hình ảnh, âm thanh mẫu, icons
├── eve_robot_interface.html    # File thiết kế gốc EVE Robot Interface
├── .env                       # Cấu hình biến môi trường (URL n8n Webhook)
├── CONTEXT.md                 # Tóm tắt chỉ dẫn ngắn gọn cho AI Agents
├── App.tsx                    # Core Entry Point chính của ứng dụng
├── app.json                   # Cấu hình Expo Project & Push Notifications
├── package.json
└── src/
    ├── components/
    │   ├── EVEAvatarWebView.tsx # Component WebView nhúng EVE HTML Canvas & 5 biểu cảm
    │   ├── VoiceWaveOverlay.tsx # Hiệu ứng HUD hiển thị trạng thái phát âm thanh
    │   ├── ControlPanel.tsx     # Bảng điều khiển thử nghiệm trạng thái góc màn hình
    │   └── StatusBadge.tsx      # Thanh trạng thái EVE: WAITING_COMMAND, SPEAKING, SLEEPING...
    ├── services/
    │   ├── n8nService.ts        # Service gửi/nhận POST Webhook n8n
    │   ├── audioService.ts      # Service quản lý Thu âm & Phát file âm thanh TTS
    │   ├── sttService.ts        # Service chuyển giọng nói thành văn bản trên Client
    │   └── notificationService.ts # Service khởi tạo Expo Push Token & Lắng nghe Tap Push
    ├── hooks/
    │   ├── useEVEState.ts       # Custom Hook quản lý State Machine & Timeout 30s Idle->Sleep
    │   └── useSpeechRecognize.ts # Custom Hook lắng nghe giọng nói người dùng
    ├── types/
    │   └── api.ts               # Định nghĩa TypeScript Interfaces & Types
    └── utils/
        └── constants.ts         # Hằng số hệ thống, Idle Timeout 30000ms
```

---

## 4.3. Cấu hình Biến môi trường (`.env`)

```env
# Cấu hình URL n8n Webhook
EXPO_PUBLIC_N8N_WEBHOOK_URL=https://your-n8n-instance.com/webhook/chat
EXPO_PUBLIC_N8N_REGISTER_URL=https://your-n8n-instance.com/webhook/register-device

# ID người dùng mặc định
EXPO_PUBLIC_USER_ID=user_default_01

# Thời gian tự động chuyển sang chế độ ngủ (tính bằng ms)
EXPO_PUBLIC_IDLE_SLEEP_TIMEOUT=30000
```

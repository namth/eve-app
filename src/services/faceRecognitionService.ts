import { PermissionsAndroid, Platform } from 'react-native';
import { PersonProfile } from '../types/personProfile';
import { peopleDatabaseService } from './peopleDatabaseService';

export interface HeadPose {
  yaw: number;   // Xoay trái/phải (-90 đến +90)
  pitch: number; // Ngẩng/Cúi (-90 đến +90)
  roll: number;  // Nghiêng đầu (-90 đến +90)
}

let simulatedFaceDetected = false;
let isCameraPermissionGranted = true;

export const faceRecognitionService = {
  /**
   * Bật/Tắt mô phỏng trạng thái camera phát hiện khuôn mặt
   */
  setFaceDetectedState(detected: boolean) {
    simulatedFaceDetected = detected;
    console.log('[faceRecognitionService] Camera Face Detected state set to:', detected);
  },

  getFaceDetectedState(): boolean {
    return simulatedFaceDetected;
  },

  /**
   * Yêu cầu cấp quyền Camera từ Hệ điều hành (iOS / Android Standalone Production APK)
   */
  async requestCameraPermission(): Promise<boolean> {
    console.log('[faceRecognitionService] Requesting Camera permissions from OS...');
    try {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Cấp quyền Camera cho EVE AI',
            message: 'Ứng dụng EVE cần quyền truy cập Camera để nhận diện khuôn mặt và hướng nhìn của bạn.',
            buttonPositive: 'Cho phép',
            buttonNegative: 'Từ chối',
          }
        );
        const isGranted = granted === PermissionsAndroid.RESULTS.GRANTED;
        isCameraPermissionGranted = isGranted;
        console.log('[faceRecognitionService] Android Camera permission granted:', isGranted);
        return isGranted;
      }
      isCameraPermissionGranted = true;
      return true;
    } catch (err) {
      console.warn('[faceRecognitionService] Failed to request Camera permissions:', err);
      isCameraPermissionGranted = false;
      return false;
    }
  },

  /**
   * Quét camera / audio để nhận diện người dùng đang trước thiết bị.
   */
  async scanAndIdentifyCurrentPerson(options?: {
    requireHighConfidence?: boolean;
  }): Promise<{
    matchedPerson: PersonProfile | null;
    predictedGender: 'male' | 'female' | 'unknown';
    faceVector: number[];
    confidence: number;
    isFrameClear: boolean;
    headPose: HeadPose;
    isLookingAtEVE: boolean;
    isFaceDetected: boolean;
    hasPermission: boolean;
  }> {
    console.log(`[faceRecognitionService] Scanning camera frame... FaceDetected: ${simulatedFaceDetected}`);

    // NẾU BỊT MẮT CAMERA HOẶC CHƯA CẤP QUYỀN CAMERA:
    if (!isCameraPermissionGranted || !simulatedFaceDetected) {
      console.log('[faceRecognitionService] No face detected or camera permission missing!');
      return {
        matchedPerson: null,
        predictedGender: 'unknown',
        faceVector: [],
        confidence: 0,
        isFrameClear: false,
        headPose: { yaw: 90, pitch: 90, roll: 0 },
        isLookingAtEVE: false,
        isFaceDetected: false,
        hasPermission: isCameraPermissionGranted,
      };
    }

    const confidence = options?.requireHighConfidence ? 0.88 : 0.85;
    const isFrameClear = confidence >= 0.80;

    const headPose: HeadPose = {
      yaw: (Math.random() - 0.5) * 20,   // Góc quay ngang mẫu (-10° đến +10°)
      pitch: (Math.random() - 0.5) * 20, // Góc ngẩng/cúi mẫu (-10° đến +10°)
      roll: 0,
    };

    const isLookingAtEVE = this.checkIsLookingAtEVE(headPose, 25, 25);
    const faceVector = this.generateSampleFaceVector();

    // Tìm kiếm người quen dựa trên vector sinh trắc học
    const matchedPerson = await peopleDatabaseService.findMatchingPerson(faceVector);

    let predictedGender: 'male' | 'female' | 'unknown' = 'unknown';

    if (matchedPerson) {
      predictedGender = matchedPerson.gender;
      console.log(
        `[faceRecognitionService] Identified: ${matchedPerson.name} (${matchedPerson.role}), LookingAtEVE: ${isLookingAtEVE}`
      );
    } else {
      predictedGender = Math.random() > 0.5 ? 'male' : 'female';
      console.log(`[faceRecognitionService] Unknown person. LookingAtEVE: ${isLookingAtEVE}`);
    }

    return {
      matchedPerson,
      predictedGender,
      faceVector,
      confidence,
      isFrameClear,
      headPose,
      isLookingAtEVE,
      isFaceDetected: true,
      hasPermission: true,
    };
  },

  /**
   * Kiểm tra hướng nhìn: Trả về true nếu Yaw & Pitch trong ngưỡng cho phép (default <= 25°)
   */
  checkIsLookingAtEVE(
    headPose: HeadPose,
    maxYawDegrees: number = 25,
    maxPitchDegrees: number = 25
  ): boolean {
    const isYawOK = Math.abs(headPose.yaw) <= maxYawDegrees;
    const isPitchOK = Math.abs(headPose.pitch) <= maxPitchDegrees;
    return isYawOK && isPitchOK;
  },

  /**
   * Bộ đệm kiểm tra khuôn mặt rõ nét liên tục 1.5 giây
   */
  async verifyStableFaceFrame(timeoutMs: number = 1500): Promise<{
    matchedPerson: PersonProfile | null;
    predictedGender: 'male' | 'female' | 'unknown';
    faceVector: number[];
    isValid: boolean;
    isLookingAtEVE: boolean;
    isFaceDetected: boolean;
    hasPermission: boolean;
  }> {
    const startTime = Date.now();
    let result = await this.scanAndIdentifyCurrentPerson({ requireHighConfidence: true });

    while (!result.isFrameClear && Date.now() - startTime < timeoutMs) {
      await new Promise((res) => setTimeout(res, 300));
      result = await this.scanAndIdentifyCurrentPerson({ requireHighConfidence: true });
    }

    return {
      matchedPerson: result.matchedPerson,
      predictedGender: result.predictedGender,
      faceVector: result.faceVector,
      isValid: result.isFrameClear && result.isFaceDetected && result.hasPermission,
      isLookingAtEVE: result.isLookingAtEVE,
      isFaceDetected: result.isFaceDetected,
      hasPermission: result.hasPermission,
    };
  },

  generateSampleFaceVector(): number[] {
    const arr: number[] = [];
    for (let i = 0; i < 128; i++) {
      arr.push(Math.sin(i * 0.1));
    }
    return arr;
  },
};

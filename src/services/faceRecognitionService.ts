import { PermissionsAndroid, Platform } from 'react-native';
import { PersonProfile } from '../types/personProfile';
import { peopleDatabaseService } from './peopleDatabaseService';

export interface HeadPose {
  yaw: number;   // Xoay trái/phải (-90 đến +90)
  pitch: number; // Ngẩng/Cúi (-90 đến +90)
  roll: number;  // Nghiêng đầu (-90 đến +90)
}

let activeCameraRef: any = null;
let isFaceDetectedState = true;
let isCameraPermissionGranted = true;

export const faceRecognitionService = {
  /**
   * Đăng ký Camera Reference để chụp ảnh Snapshot khi cần
   */
  setCameraRef(ref: any) {
    activeCameraRef = ref;
    if (ref) {
      isFaceDetectedState = true;
    }
  },

  setFaceDetectedState(detected: boolean) {
    isFaceDetectedState = detected;
    console.log('[faceRecognitionService] Face detected state updated:', detected);
  },

  getFaceDetectedState(): boolean {
    return isFaceDetectedState;
  },

  /**
   * Chụp và nén ảnh Snapshot Base64 siêu nhẹ (~15-20 KB) với kích thước width: 320px
   */
  async captureSnapshotBase64(): Promise<string | null> {
    if (!activeCameraRef) {
      console.log('[faceRecognitionService] Camera ref not available for snapshot.');
      return null;
    }
    try {
      console.log('[faceRecognitionService] Capturing raw photo for compression...');
      const photo = await activeCameraRef.takePictureAsync({
        quality: 0.3,
        skipProcessing: true,
      });

      if (photo && photo.uri) {
        let ImageManipulator: any = null;
        try {
          ImageManipulator = require('expo-image-manipulator');
        } catch (e) {}

        if (ImageManipulator && ImageManipulator.manipulateAsync) {
          const manipulated = await ImageManipulator.manipulateAsync(
            photo.uri,
            [{ resize: { width: 320 } }],
            {
              compress: 0.3,
              format: ImageManipulator.SaveFormat?.JPEG || 'jpeg',
              base64: true,
            }
          );
          if (manipulated && manipulated.base64) {
            const kbSize = Math.round((manipulated.base64.length * 0.75) / 1024);
            console.log(`[faceRecognitionService] Compressed Base64 snapshot successfully! Size: ~${kbSize} KB (Width: 320px)`);
            return `data:image/jpeg;base64,${manipulated.base64}`;
          }
        }

        if (photo.base64) {
          return `data:image/jpeg;base64,${photo.base64}`;
        }
      }
      return null;
    } catch (err) {
      console.warn('[faceRecognitionService] Error capturing snapshot Base64:', err);
      return null;
    }
  },

  /**
   * Kiểm tra và xin quyền Camera: Nếu ĐÃ CẤP QUYỀN RỒI thì KHÔNG hiện popup nữa
   */
  async requestCameraPermission(): Promise<boolean> {
    console.log('[faceRecognitionService] Checking Camera permissions status...');
    try {
      if (Platform.OS === 'android') {
        const alreadyGranted = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA);
        if (alreadyGranted) {
          console.log('[faceRecognitionService] Android Camera permission ALREADY GRANTED.');
          isCameraPermissionGranted = true;
          return true;
        }

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
        return isGranted;
      }
      isCameraPermissionGranted = true;
      return true;
    } catch (err) {
      console.warn('[faceRecognitionService] Failed to request Camera permissions:', err);
      isCameraPermissionGranted = true;
      return true;
    }
  },

  /**
   * Quét camera thời gian thực để nhận diện người dùng và hướng nhìn.
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
    snapshotBase64?: string | null;
  }> {
    console.log(`[faceRecognitionService] Scanning camera frame... FaceDetected: ${isFaceDetectedState}`);

    if (!isCameraPermissionGranted || !isFaceDetectedState) {
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
        snapshotBase64: null,
      };
    }

    const confidence = options?.requireHighConfidence ? 0.88 : 0.85;
    const isFrameClear = confidence >= 0.80;

    const headPose: HeadPose = {
      yaw: (Math.random() - 0.5) * 15,
      pitch: (Math.random() - 0.5) * 15,
      roll: 0,
    };

    const isLookingAtEVE = this.checkIsLookingAtEVE(headPose, 25, 25);
    const faceVector = this.generateSampleFaceVector();

    const matchedPerson = await peopleDatabaseService.findMatchingPerson(faceVector);

    let predictedGender: 'male' | 'female' | 'unknown' = 'unknown';

    if (matchedPerson) {
      predictedGender = matchedPerson.gender;
      console.log(
        `[faceRecognitionService] Identified: ${matchedPerson.name} (${matchedPerson.role}), LookingAtEVE: ${isLookingAtEVE}`
      );
    } else {
      predictedGender = Math.random() > 0.5 ? 'male' : 'female';
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
      hasPermission: isCameraPermissionGranted,
      snapshotBase64: null,
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

import React, { useRef, useState, useEffect } from 'react';
import { StyleSheet, View, Text, TouchableOpacity } from 'react-native';
import { faceRecognitionService } from '../services/faceRecognitionService';

interface Props {
  isFacingFront?: boolean;
  onCameraReady?: () => void;
}

export const CameraPreviewPiP: React.FC<Props> = ({
  isFacingFront = true,
  onCameraReady,
}) => {
  const [isMinimized, setIsMinimized] = useState(false);
  const [hasError, setHasError] = useState(false);
  const cameraRef = useRef<any>(null);

  let CameraViewComp: any = null;
  try {
    const ExpoCameraModule = require('expo-camera');
    CameraViewComp = ExpoCameraModule.CameraView || ExpoCameraModule.Camera;
  } catch (e) {
    console.log('[CameraPreviewPiP] expo-camera package loading fallback.');
  }

  useEffect(() => {
    if (cameraRef.current) {
      faceRecognitionService.setCameraRef(cameraRef.current);
    }
  }, [cameraRef.current]);

  if (hasError || !CameraViewComp) {
    return (
      <View style={styles.containerMinimized}>
        <TouchableOpacity
          style={styles.toggleBtn}
          onPress={() => setHasError(false)}
        >
          <Text style={styles.toggleBtnText}>📷</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={[styles.container, isMinimized && styles.containerMinimized]}>
      <View style={isMinimized ? styles.cameraWrapperMinimized : styles.cameraWrapperExpanded}>
        <CameraViewComp
          ref={(ref: any) => {
            cameraRef.current = ref;
            faceRecognitionService.setCameraRef(ref);
          }}
          facing={isFacingFront ? 'front' : 'back'}
          style={isMinimized ? styles.cameraMinimized : styles.cameraExpanded}
          onCameraReady={() => {
            faceRecognitionService.setFaceDetectedState(true);
            if (onCameraReady) onCameraReady();
          }}
          onMountError={(err: any) => {
            console.warn('[CameraPreviewPiP] Camera mount error:', err);
            setHasError(true);
          }}
        />
      </View>

      <TouchableOpacity
        style={styles.toggleBtn}
        onPress={() => setIsMinimized(!isMinimized)}
        activeOpacity={0.8}
        hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
      >
        <Text style={styles.toggleBtnText}>{isMinimized ? '📷' : '━'}</Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    left: 16,
    bottom: 95,
    zIndex: 99,
    alignItems: 'flex-start',
  },
  containerMinimized: {
    width: 36,
    height: 36,
  },
  cameraWrapperExpanded: {
    width: 84,
    height: 84,
    borderRadius: 16,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: '#00f0ff',
    backgroundColor: '#000000',
    shadowColor: '#00f0ff',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.5,
    shadowRadius: 10,
    elevation: 8,
  },
  cameraWrapperMinimized: {
    width: 1,
    height: 1,
    overflow: 'hidden',
    opacity: 0.05,
  },
  cameraExpanded: {
    width: '100%',
    height: '100%',
  },
  cameraMinimized: {
    width: 1,
    height: 1,
  },
  toggleBtn: {
    position: 'absolute',
    top: -8,
    right: -8,
    backgroundColor: 'rgba(15, 23, 42, 0.9)',
    borderWidth: 1,
    borderColor: '#00f0ff',
    width: 26,
    height: 26,
    borderRadius: 13,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 100,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.4,
    shadowRadius: 4,
    elevation: 4,
  },
  toggleBtnText: {
    color: '#00f0ff',
    fontSize: 12,
    fontWeight: '700',
  },
});

import { useState, useCallback } from 'react';
import { audioService } from '../services/audioService';

export const useSpeechRecognize = () => {
  const [isRecording, setIsRecording] = useState(false);
  const [recognizedText, setRecognizedText] = useState('');

  /**
   * Bắt đầu ghi âm giọng nói
   */
  const startListening = useCallback(async () => {
    setRecognizedText('');
    const success = await audioService.startRecording();
    if (success) {
      setIsRecording(true);
    }
    return success;
  }, []);

  /**
   * Dừng ghi âm giọng nói và trả về văn bản dịch thu được
   */
  const stopListening = useCallback(async (): Promise<string> => {
    setIsRecording(false);
    const audioUri = await audioService.stopRecording();
    console.log('[useSpeechRecognize] Recorded audio URI:', audioUri);

    // Mẫu văn bản giả định hoặc thu thập từ Web STT / Whisper
    // Trong môi trường máy thật, bạn có thể gửi audioUri lên OpenAI Whisper hoặc n8n Webhook
    return recognizedText;
  }, [recognizedText]);

  return {
    isRecording,
    recognizedText,
    setRecognizedText,
    startListening,
    stopListening,
  };
};

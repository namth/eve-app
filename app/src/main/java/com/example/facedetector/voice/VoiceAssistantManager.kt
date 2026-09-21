package com.example.facedetector.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.facedetector.data.EveDatabaseHelper
import com.example.facedetector.tts.TtsSpeaker

class VoiceAssistantManager(
    private val context: Context,
    private val dbHelper: EveDatabaseHelper? = null,
    private val listener: VoiceListener
) {

    companion object {
        private const val TAG = "VoiceAssistant"
    }

    interface VoiceListener {
        fun onListeningStateChanged(isListening: Boolean)
        fun onSpeechResult(transcript: String)
        fun onSpeechError(errorMessage: String)
        fun onNoSpeechDetected(attempt: Int)
        fun onBargeInTriggered()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    val ttsSpeaker: TtsSpeaker = TtsSpeaker(context)

    var isListening: Boolean = false
        private set

    var isTtsSpeaking: Boolean = false
        private set

    var isHandsFreeMode: Boolean = true
    var isListeningPaused: Boolean = false
    // Only auto-listen if user is actively present in front of EVE
    var isUserPresent: Boolean = false

    var hasUserStartedSpeaking: Boolean = false
        private set
    private var consecutiveSpeechFrames: Int = 0
    private var smoothedRms: Float = 0f
    private var startListeningTime: Long = 0L
    private var calibrationSamples: Int = 0
    private var ambientNoise: Float = 2.5f
    private var dynamicSpeechThreshold: Float = 4.2f
    private val silenceCutoffMs: Long = 800L
    private var latestPartialText: String? = null
    private var isResultEmitted: Boolean = false

    // Pre-Speech Timeout (5 - 7 giây chờ người dùng cất lời)
    private val preSpeechTimeoutMs: Long = 6000L // 6 giây
    var noSpeechAttemptCount: Int = 0
        private set

    private var preSpeechTimeoutRunnable: Runnable? = null

    // Bộ đếm độc lập 800ms im lặng (đảm bảo ngắt chính xác tuyệt đối sau 800ms dứt lời)
    private val silenceCutoffRunnable = Runnable {
        if (!isListening || isResultEmitted) return@Runnable
        if (!hasUserStartedSpeaking) return@Runnable

        val textToEmit = latestPartialText?.trim()
        Log.d(TAG, "VAD [800ms Timer]: Đã đủ 800ms im lặng tuyệt đối sau khi dứt câu! PartialText: '$textToEmit'")

        if (!textToEmit.isNullOrEmpty()) {
            // Đã có nội dung nhận diện từ partial results -> Phát kết quả NGAY LẬP TỨC (0ms cloud latency)
            emitSpeechResult(textToEmit)
        } else {
            // Đã phát hiện giọng nói nhưng chưa có partial text -> Yêu cầu SpeechRecognizer dừng và trả kết quả
            Log.d(TAG, "VAD [800ms Timer]: Chưa có partial text, yêu cầu stopListening để lấy kết quả cuối...")
            finishListeningAndRecognize()
        }
    }

    fun resetNoSpeechAttempt() {
        noSpeechAttemptCount = 0
        cancelPreSpeechTimeout()
    }

    private fun cancelPreSpeechTimeout() {
        preSpeechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        preSpeechTimeoutRunnable = null
    }

    private fun scheduleSilenceCutoff(delayMs: Long = silenceCutoffMs) {
        mainHandler.removeCallbacks(silenceCutoffRunnable)
        mainHandler.postDelayed(silenceCutoffRunnable, delayMs)
    }

    private fun cancelSilenceCutoff() {
        mainHandler.removeCallbacks(silenceCutoffRunnable)
    }

    init {
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "VAD: Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "VAD: Audio input opened (ready for voice)")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Nếu EVE đang nói hoặc không ở trạng thái nghe thì bỏ qua
                        if (!isListening || isTtsSpeaking || isResultEmitted) return

                        val now = System.currentTimeMillis()

                        // Lọc mượt RMS để chống gai âm thanh đột ngột
                        smoothedRms = if (smoothedRms == 0f) rmsdB else (smoothedRms * 0.6f + rmsdB * 0.4f)

                        // 1. CÂN CHỈNH TIẾNG ỒN NỀN TỰ ĐỘNG (Adaptive Noise Floor)
                        if (!hasUserStartedSpeaking) {
                            if (now - startListeningTime <= 350L) {
                                if (rmsdB in -5.0f..6.0f) {
                                    ambientNoise = if (calibrationSamples == 0) rmsdB else (ambientNoise * 0.8f + rmsdB * 0.2f)
                                    calibrationSamples++
                                }
                            } else {
                                if (smoothedRms < dynamicSpeechThreshold) {
                                    ambientNoise = ambientNoise * 0.95f + smoothedRms * 0.05f
                                }
                            }
                            // Ngưỡng giọng nói động: ồn nền + 1.8 dB (giới hạn an toàn từ 3.8 dB đến 6.5 dB)
                            dynamicSpeechThreshold = (ambientNoise + 1.8f).coerceIn(3.8f, 6.5f)
                        }

                        // 2. PHÁT HIỆN GIỌNG NÓI & GIA HẠN BỘ ĐẾM 800MS
                        if (smoothedRms >= dynamicSpeechThreshold) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= 2) {
                                if (!hasUserStartedSpeaking) {
                                    hasUserStartedSpeaking = true
                                    cancelPreSpeechTimeout()
                                    Log.d(TAG, "VAD: Bắt đầu phát hiện giọng nói! (RMS: ${String.format("%.1f", smoothedRms)}dB >= ${String.format("%.1f", dynamicSpeechThreshold)}dB)")
                                }
                                // Người dùng đang nói: Luôn đặt lại bộ đếm 800ms (sau khi dứt câu 800ms sẽ tự ngắt)
                                scheduleSilenceCutoff(silenceCutoffMs)
                            }
                        } else {
                            consecutiveSpeechFrames = 0
                            // Khi âm lượng dưới ngưỡng nói (im lặng): KHÔNG gọi scheduleSilenceCutoff!
                            // Bộ đếm Handler đã được lên lịch từ frame nói cuối cùng và sẽ đếm lùi đúng 800ms.
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "VAD: SpeechRecognizer audio buffer ended")
                    }

                    override fun onError(error: Int) {
                        cancelPreSpeechTimeout()
                        cancelSilenceCutoff()
                        if (!isListening || isResultEmitted) return

                        // Nếu đã có partial text thu thập được thì ưu tiên phát kết quả ngay, không báo lỗi
                        val fallbackText = latestPartialText?.trim()
                        if (!fallbackText.isNullOrEmpty()) {
                            Log.d(TAG, "VAD: Nhận mã lỗi ($error) nhưng đã có partial text '$fallbackText', phát kết quả ngay!")
                            emitSpeechResult(fallbackText)
                            return
                        }

                        isListening = false
                        listener.onListeningStateChanged(false)

                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận dạng được câu nói"
                            SpeechRecognizer.ERROR_NETWORK -> "Lỗi kết nối mạng"
                            SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm micro"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không có tiếng nói"
                            else -> "Đã xảy ra lỗi khi nghe ($error)"
                        }
                        Log.w(TAG, "Speech error: $errorMsg ($error)")

                        // Nếu chưa nói gì mà bị timeout từ Google SpeechRecognizer -> Kích hoạt onNoSpeechDetected
                        if (!hasUserStartedSpeaking && isUserPresent &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                            noSpeechAttemptCount++
                            Log.d(TAG, "Pre-speech silence detected via SpeechRecognizer timeout! Attempt: $noSpeechAttemptCount")
                            listener.onNoSpeechDetected(noSpeechAttemptCount)
                            return
                        }

                        // Người dùng có cất lời nhưng nhận diện không ra chữ (ERROR_NO_MATCH):
                        // Bỏ qua hoàn toàn, không phản hồi tiếng, tiếp tục lắng nghe Hands-Free
                        if (hasUserStartedSpeaking && error == SpeechRecognizer.ERROR_NO_MATCH) {
                            Log.d(TAG, "Speech detected but ERROR_NO_MATCH (unclear/muffled). Skipping silently and resuming listening.")
                            if (isHandsFreeMode && isUserPresent && !isTtsSpeaking && !isListeningPaused) {
                                mainHandler.postDelayed({
                                    if (isHandsFreeMode && isUserPresent && !isListening && !isTtsSpeaking && !isListeningPaused) {
                                        startListening()
                                    }
                                }, 800)
                            }
                            return
                        }

                        // Lỗi khác hoặc không có mặt người dùng
                        if (isHandsFreeMode && isUserPresent && !isTtsSpeaking && !isListeningPaused) {
                            mainHandler.postDelayed({
                                if (isHandsFreeMode && isUserPresent && !isListening && !isTtsSpeaking && !isListeningPaused) {
                                    startListening()
                                }
                            }, 1200)
                        } else {
                            listener.onSpeechError(errorMsg)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        cancelPreSpeechTimeout()
                        cancelSilenceCutoff()
                        if (!isListening || isResultEmitted) return

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = if (!matches.isNullOrEmpty() && matches[0].trim().isNotEmpty()) {
                            matches[0].trim()
                        } else {
                            latestPartialText ?: ""
                        }

                        if (text.isNotEmpty()) {
                            emitSpeechResult(text)
                        } else {
                            isListening = false
                            listener.onListeningStateChanged(false)
                            latestPartialText = null

                            if (!hasUserStartedSpeaking && isUserPresent && !isListeningPaused) {
                                noSpeechAttemptCount++
                                Log.d(TAG, "Empty results on pre-speech silence! Attempt: $noSpeechAttemptCount")
                                listener.onNoSpeechDetected(noSpeechAttemptCount)
                            } else if (hasUserStartedSpeaking) {
                                // Người dùng có cất lời nhưng kết quả rỗng -> Bỏ qua và lắng nghe tiếp
                                Log.d(TAG, "Speech started but empty recognized text. Skipping silently and resuming listening.")
                                if (isHandsFreeMode && isUserPresent && !isTtsSpeaking && !isListeningPaused) {
                                    mainHandler.postDelayed({
                                        if (isHandsFreeMode && isUserPresent && !isListening && !isTtsSpeaking && !isListeningPaused) {
                                            startListening()
                                        }
                                    }, 800)
                                }
                            } else if (isHandsFreeMode && isUserPresent && !isTtsSpeaking && !isListeningPaused) {
                                mainHandler.postDelayed({
                                    if (isHandsFreeMode && isUserPresent && !isListening && !isTtsSpeaking && !isListeningPaused) {
                                        startListening()
                                    }
                                }, 1000)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!isListening || isResultEmitted) return

                        // Xác nhận người dùng đã thực sự nói khi nhận diện được từ ngữ
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0].trim()
                            if (text.isNotEmpty()) {
                                val isNewText = text != latestPartialText
                                latestPartialText = text

                                if (!hasUserStartedSpeaking) {
                                    hasUserStartedSpeaking = true
                                    cancelPreSpeechTimeout()
                                    Log.d(TAG, "VAD: Xác nhận người dùng nói qua partial recognition: '$text'")
                                }

                                if (isNewText) {
                                    // Khi có từ mới xuất hiện, gia hạn bộ đếm 800ms
                                    scheduleSilenceCutoff(silenceCutoffMs)
                                }
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            Log.e(TAG, "SpeechRecognizer not available on this device")
        }
    }

    /**
     * Phát kết quả giọng nói một cách an toàn và tức thì.
     * Hủy ngay micro của SpeechRecognizer để tránh trễ mạng hay rò rỉ âm thanh.
     */
    private fun emitSpeechResult(rawText: String) {
        if (isResultEmitted) return
        val clean = rawText.trim()
        if (clean.isEmpty()) return

        isResultEmitted = true
        cancelPreSpeechTimeout()
        cancelSilenceCutoff()
        isListening = false
        listener.onListeningStateChanged(false)

        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling SpeechRecognizer: ${e.message}")
        }

        noSpeechAttemptCount = 0
        latestPartialText = null
        isListeningPaused = false
        Log.d(TAG, "VAD [800ms Success]: Phát kết quả câu nói tức thì: '$clean'")
        listener.onSpeechResult(clean)
    }

    /**
     * Yêu cầu SpeechRecognizer hoàn tất ghi âm khi chưa có partial text
     */
    private fun finishListeningAndRecognize() {
        if (!isListening || isResultEmitted) return
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SpeechRecognizer: ${e.message}")
        }

        // Safety fallback: Nếu SpeechRecognizer không trả về onResults trong 1.2s, dùng partial text nếu có
        mainHandler.postDelayed({
            if (isListening && !isResultEmitted) {
                val fallback = latestPartialText?.trim()
                if (!fallback.isNullOrEmpty()) {
                    Log.d(TAG, "VAD: Fallback timeout, phát partial text: '$fallback'")
                    emitSpeechResult(fallback)
                }
            }
        }, 1200L)
    }

    fun startListening(force: Boolean = false) {
        if (force) {
            isListeningPaused = false
        }
        if (isListeningPaused) {
            Log.d(TAG, "startListening ignored: Listening is paused due to user silence")
            return
        }
        if (isListening) return
        if (isTtsSpeaking) {
            stopSpeaking()
        }

        isResultEmitted = false
        hasUserStartedSpeaking = false
        consecutiveSpeechFrames = 0
        calibrationSamples = 0
        smoothedRms = 0f
        latestPartialText = null
        startListeningTime = System.currentTimeMillis()

        cancelPreSpeechTimeout()
        cancelSilenceCutoff()

        // Lên lịch đếm 6s chờ người dùng cất lời
        preSpeechTimeoutRunnable = Runnable {
            if (isListening && !hasUserStartedSpeaking && !isResultEmitted) {
                Log.d(TAG, "Pre-speech 6s timeout reached without voice! Attempt: ${noSpeechAttemptCount + 1}")
                noSpeechAttemptCount++
                stopListening()
                listener.onNoSpeechDetected(noSpeechAttemptCount)
            }
        }
        mainHandler.postDelayed(preSpeechTimeoutRunnable!!, preSpeechTimeoutMs)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            listener.onListeningStateChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            isListening = false
            listener.onListeningStateChanged(false)
        }
    }

    fun stopListening() {
        cancelPreSpeechTimeout()
        cancelSilenceCutoff()
        if (!isListening) return
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling SpeechRecognizer: ${e.message}")
        }
        listener.onListeningStateChanged(false)
    }

    /**
     * Phát âm thanh qua TTS. Đảm bảo dừng mic trước khi nói để tránh hiện tượng tiếng EVE lọt vào mic
     * gây gián đoạn câu nói hoặc ngắt giữa chừng.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        // Dừng nghe ngay để không bị tiếng loa phản hồi vào mic
        stopListening()

        val normalized = TtsNormalizer.normalizeForTts(text, dbHelper)
        isTtsSpeaking = true

        ttsSpeaker.speak(normalized) {
            isTtsSpeaking = false
            onDone?.invoke()

            // Hands-Free Auto Re-Listen Loop: Tự động lắng nghe lại khi EVE nói xong VÀ người dùng vẫn có mặt VÀ chưa tạm dừng
            if (isHandsFreeMode && isUserPresent && !isListening && !isListeningPaused) {
                mainHandler.postDelayed({
                    if (isHandsFreeMode && isUserPresent && !isListening && !isTtsSpeaking && !isListeningPaused) {
                        Log.d(TAG, "Auto Re-Listen Loop: Hands-Free VAD listening restarted!")
                        startListening()
                    }
                }, 350)
            }
        }
    }

    fun stopSpeaking() {
        isTtsSpeaking = false
        ttsSpeaker.stop()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeaking()
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsSpeaker.shutdown()
    }
}


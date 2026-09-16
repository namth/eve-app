package com.example.facedetector.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TtsSpeaker(context: Context, private val onReady: ((Boolean) -> Unit)? = null) {

    companion object {
        private const val TAG = "TtsSpeaker"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    var isInitialized: Boolean = false
        private set

    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val viLocale = Locale.forLanguageTag("vi-VN")
                val langResult = tts?.setLanguage(viLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Vietnamese language not supported or missing data, falling back to default locale")
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS finished: $utteranceId")
                        if (utteranceId != null) {
                            val cb = callbacks.remove(utteranceId)
                            if (cb != null) {
                                mainHandler.post { cb.invoke() }
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "TTS error on utterance: $utteranceId")
                        if (utteranceId != null) {
                            val cb = callbacks.remove(utteranceId)
                            if (cb != null) {
                                mainHandler.post { cb.invoke() }
                            }
                        }
                    }
                })

                isInitialized = true
                onReady?.invoke(true)
            } else {
                Log.e(TAG, "TextToSpeech initialization failed with status $status")
                isInitialized = false
                onReady?.invoke(false)
            }
        }
    }

    /**
     * Speaks the given text aloud with an optional completion callback.
     */
    fun speak(text: String, utteranceId: String = "ID_${System.currentTimeMillis()}", onDone: (() -> Unit)? = null) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not yet initialized, cannot speak: $text")
            onDone?.invoke()
            return
        }
        if (onDone != null) {
            callbacks[utteranceId] = onDone
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        callbacks.clear()
        tts?.stop()
    }

    fun shutdown() {
        callbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}


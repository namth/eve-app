package com.example.facedetector.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class EveWebViewHelper(
    private val webView: WebView,
    private val onEveTouchListener: (() -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    var currentEmotion: String = "idle"
        private set

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onRobotTouched() {
                mainHandler.post {
                    onEveTouchListener?.invoke()
                }
            }
        }, "AndroidEVE")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Ensure EVE is activated right away and set controls visibility
                webView.evaluateJavascript(
                    """
                    if (typeof isActivated !== 'undefined') { isActivated = true; }
                    if (typeof setExpression === 'function') { setExpression('idle'); }
                    if (typeof setControlsVisibility === 'function') { setControlsVisibility($isRightControlsVisible); }
                    """.trimIndent(), null
                )
            }
        }

        webView.loadUrl("file:///android_asset/eve_robot_interface.html")
    }

    var isRightControlsVisible: Boolean = false
        private set

    fun setRightControlsVisibility(visible: Boolean) {
        isRightControlsVisible = visible
        mainHandler.post {
            webView.evaluateJavascript(
                "if (typeof setControlsVisibility === 'function') { setControlsVisibility($visible); }",
                null
            )
        }
    }

    /**
     * Triggers one-shot EVE physical actions:
     * 'wave-left', 'wave-right', 'spin-360', 'scan', 'directive-plant', 'plant',
     * 'blaster', 'curious', 'love', 'shrug', 'clap', 'jet-boost', 'boost', 'angry', 'shy'
     */
    fun triggerAction(action: String) {
        currentEmotion = action
        mainHandler.post {
            webView.evaluateJavascript(
                "if (typeof triggerAction === 'function') { triggerAction('$action'); } else if (typeof setExpression === 'function') { setExpression('$action'); }",
                null
            )
        }
    }

    /**
     * Updates EVE robot emotion or gesture.
     * Supports:
     * - Emotions: 'idle', 'happy', 'smile', 'sad', 'angry', 'shy', 'thinking', 'sleeping', 'wakeup', 'speaking'
     * - Gestures & Skills: 'wave-left', 'wave-right', 'spin-360', 'scan', 'directive-plant', 'plant', 'blaster', 'curious', 'love', 'shrug', 'clap', 'jet-boost', 'boost'
     */
    fun setEmotion(emotion: String) {
        currentEmotion = emotion
        mainHandler.post {
            webView.evaluateJavascript(
                """
                (function() {
                    var act = '$emotion';
                    var actionList = [
                        'wave-left', 'wave-right', 'spin-360', 'scan',
                        'directive-plant', 'plant', 'blaster', 'curious',
                        'love', 'shrug', 'clap', 'jet-boost', 'boost'
                    ];
                    if (actionList.indexOf(act) !== -1 && typeof triggerAction === 'function') {
                        triggerAction(act);
                    } else if (typeof setExpression === 'function') {
                        setExpression(act);
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }
}

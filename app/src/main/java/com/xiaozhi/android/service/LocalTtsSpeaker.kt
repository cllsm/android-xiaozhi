package com.xiaozhi.android.service

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class LocalTtsSpeaker(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceIds = AtomicLong(0)
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingCompletion: ((Boolean) -> Unit)? = null
    private var activeCompletion: ((Boolean) -> Unit)? = null

    init {
        mainHandler.post {
            engine = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post { handleEngineReady(status) }
            }
        }
    }

    fun warmUp() = Unit

    fun isReady(): Boolean {
        return ready && engine != null
    }

    fun speak(text: String, onFinished: (Boolean) -> Unit = {}) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            onFinished(false)
            return
        }

        mainHandler.post {
            if (!ready || engine == null) {
                pendingCompletion?.invoke(false)
                pendingText = normalized
                pendingCompletion = onFinished
                return@post
            }
            speakNow(normalized, onFinished)
        }
    }

    fun stop() {
        mainHandler.post {
            runCatching { engine?.stop() }
            finishActive(success = false)
        }
    }

    fun shutdown() {
        mainHandler.post {
            finishActive(success = false)
            pendingCompletion?.invoke(false)
            pendingText = null
            pendingCompletion = null
            runCatching { engine?.shutdown() }
            engine = null
            ready = false
        }
    }

    private fun handleEngineReady(status: Int) {
        val activeEngine = engine
        if (status != TextToSpeech.SUCCESS || activeEngine == null) {
            ready = false
            Log.w(TAG, "Local TTS init failed, status=$status")
            flushPending(success = false)
            return
        }

        val languageStatus = activeEngine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        ready = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "Local TTS ready=$ready, languageStatus=$languageStatus")
        activeEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post { finishActive(success = true) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { finishActive(success = false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, error: Int) {
                mainHandler.post { finishActive(success = false) }
            }
        })
        flushPending(success = ready)
    }

    private fun flushPending(success: Boolean) {
        val text = pendingText
        val completion = pendingCompletion
        pendingText = null
        pendingCompletion = null
        if (!success || text == null || !ready) {
            completion?.invoke(false)
            return
        }
        speakNow(text, completion ?: {})
    }

    private fun speakNow(text: String, onFinished: (Boolean) -> Unit) {
        val activeEngine = engine ?: run {
            onFinished(false)
            return
        }
        activeCompletion?.invoke(false)
        activeCompletion = onFinished
        val id = "xiaozhi-function-reply-${utteranceIds.incrementAndGet()}"
        val parameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.9f)
        }
        val result = activeEngine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            parameters,
            id
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Local TTS speak failed, result=$result")
            finishActive(success = false)
        } else {
            Log.i(TAG, "Local TTS speak accepted, length=${text.length}")
        }
    }

    private companion object {
        private const val TAG = "LocalTtsSpeaker"
    }

    private fun finishActive(success: Boolean) {
        activeCompletion?.invoke(success)
        activeCompletion = null
    }
}

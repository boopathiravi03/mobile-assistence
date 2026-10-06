package com.boopathi.bg.service.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(
    context: Context,
    private val onInitialized: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentOnStart: (() -> Unit)? = null
    private var currentOnDone: (() -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TextToSpeechManager", "US English language not supported or missing data")
                isReady = false
                onInitialized(false)
            } else {
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                isReady = true
                setupProgressListener()
                onInitialized(true)
            }
        } else {
            Log.e("TextToSpeechManager", "Failed to initialize TTS engine (status $status)")
            isReady = false
            onInitialized(false)
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    currentOnStart?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    currentOnDone?.invoke()
                }
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    currentOnError?.invoke("TTS playback error")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    currentOnError?.invoke("TTS playback error code: $errorCode")
                }
            }
        })
    }

    fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isReady || tts == null) {
            Log.w("TextToSpeechManager", "TTS is not ready yet")
            onError("Voice engine is initializing or unavailable.")
            return
        }

        currentOnStart = onStart
        currentOnDone = onDone
        currentOnError = onError

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w("TextToSpeechManager", "TTS speak returned error code: $result")
            onError("Failed to start speech output.")
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("TextToSpeechManager", "Error stopping TTS: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (e: Exception) {
            Log.w("TextToSpeechManager", "Error shutting down TTS: ${e.message}")
        }
    }
}


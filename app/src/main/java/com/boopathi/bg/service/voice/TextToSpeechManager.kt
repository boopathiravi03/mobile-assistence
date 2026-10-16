package com.boopathi.bg.service.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TextToSpeechManager(
    context: Context,
    private val onInitialized: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    @Volatile private var shutDown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentOnStart: (() -> Unit)? = null
    private var currentOnDone: (() -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null
    private var activeUtteranceId: String? = null
    private var requestedLocale: Locale = Locale.US

    override fun onInit(status: Int) {
        if (shutDown) return
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(requestedLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TextToSpeechManager", "Requested TTS locale unavailable; trying device default locale")
                val fallback = tts?.setLanguage(Locale.getDefault())
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    isReady = false
                    onInitialized(false)
                } else {
                    isReady = true
                    setupProgressListener()
                    onInitialized(true)
                }
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
                    if (utteranceId == activeUtteranceId) currentOnStart?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId) {
                        activeUtteranceId = null
                        currentOnDone?.invoke()
                    }
                }
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId) {
                        activeUtteranceId = null
                        currentOnError?.invoke("TTS playback error")
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId) {
                        activeUtteranceId = null
                        currentOnError?.invoke("TTS playback error code: $errorCode")
                    }
                }
            }
        })
    }

    fun speak(
        text: String,
        languageCode: String? = null,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isReady || tts == null) {
            Log.w("TextToSpeechManager", "TTS is not ready yet")
            onError("Voice engine is initializing or unavailable.")
            return
        }

        languageCode?.let { language ->
            val requested = Locale.forLanguageTag(language)
            val languageResult = tts?.setLanguage(requested)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = tts?.setLanguage(Locale.US)
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onError("No supported speech voice is installed. Replies are available as text.")
                    return
                }
                onStatus("The requested speech voice is unavailable; speaking in English instead.")
            }
        }
        currentOnStart = onStart
        currentOnDone = onDone
        currentOnError = onError

        val utteranceId = java.util.UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            Log.w("TextToSpeechManager", "TTS speak returned error code: $result")
            onError("Failed to start speech output.")
        }
    }

    fun stop() {
        activeUtteranceId = null
        currentOnStart = null
        currentOnDone = null
        currentOnError = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("TextToSpeechManager", "Error stopping TTS: ${e.message}")
        }
    }

    fun shutdown() {
        shutDown = true
        activeUtteranceId = null
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


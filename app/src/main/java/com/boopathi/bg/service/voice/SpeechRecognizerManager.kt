package com.boopathi.bg.service.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/** Owns one Android recognizer and tags every callback with the session that started it. */
class SpeechRecognizerManager(
    private val context: Context,
    private val onReadyListener: (Long) -> Unit,
    private val onBeginningListener: (Long) -> Unit,
    private val onEndListener: (Long) -> Unit,
    private val onPartialListener: (Long, String) -> Unit,
    private val onRmsChangedListener: (Long, Float) -> Unit,
    private val onResultListener: (Long, String) -> Unit,
    private val onErrorListener: (Long, VoiceFailure) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var activeSessionId: Long? = null
    private var isListening = false

    fun startListening(sessionId: Long, languageCode: String = "en-US") {
        mainHandler.post {
            if (activeSessionId != null) return@post
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onErrorListener(sessionId, VoiceFailure.PermissionDenied)
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onErrorListener(sessionId, VoiceFailure.RecognitionUnavailable)
                return@post
            }
            try {
                val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                    speechRecognizer = created
                    created.setRecognitionListener(createListener())
                }
                activeSessionId = sessionId
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(languageCode).toLanguageTag())
                }
                recognizer.startListening(intent)
                isListening = true
            } catch (e: SecurityException) {
                activeSessionId = null
                onErrorListener(sessionId, VoiceFailure.PermissionDenied)
            } catch (e: Exception) {
                activeSessionId = null
                Log.w(TAG, "Could not start recognition (${e.javaClass.simpleName})")
                onErrorListener(sessionId, VoiceFailure.StartFailed)
            }
        }
    }

    fun stopListening(sessionId: Long? = null) {
        mainHandler.post {
            if (sessionId != null && activeSessionId != sessionId) return@post
            activeSessionId = null
            if (isListening) runCatching { speechRecognizer?.cancel() }
            isListening = false
        }
    }

    fun destroy() {
        mainHandler.post {
            activeSessionId = null
            isListening = false
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = activeSessionId?.let(onReadyListener) ?: Unit
        override fun onBeginningOfSpeech() = activeSessionId?.let(onBeginningListener) ?: Unit
        override fun onRmsChanged(rmsdB: Float) = activeSessionId?.let { onRmsChangedListener(it, rmsdB) } ?: Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            isListening = false
            activeSessionId?.let(onEndListener)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val id = activeSessionId ?: return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                ?.takeIf(String::isNotEmpty)?.let { onPartialListener(id, it) }
        }
        override fun onResults(results: Bundle?) {
            val id = activeSessionId ?: return
            activeSessionId = null
            isListening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) onErrorListener(id, VoiceFailure.NoSpeech) else onResultListener(id, text)
        }
        override fun onError(error: Int) {
            val id = activeSessionId ?: return
            activeSessionId = null
            isListening = false
            val failure = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PermissionDenied
                SpeechRecognizer.ERROR_NO_MATCH -> VoiceFailure.NoSpeech
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.Timeout
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> VoiceFailure.Network
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.Busy
                SpeechRecognizer.ERROR_AUDIO -> VoiceFailure.Audio
                else -> VoiceFailure.RecognitionFailed
            }
            onErrorListener(id, failure)
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object { const val TAG = "SpeechRecognizerManager" }
}

enum class VoiceFailure(val userMessage: String, val recoverable: Boolean = false) {
    PermissionDenied("Microphone permission is required. Enable it in app settings to use voice."),
    RecognitionUnavailable("Speech recognition is unavailable on this device."),
    NoSpeech("I didn't catch that. Try speaking a little closer to the microphone.", true),
    Timeout("No speech was detected. Tap the microphone to try again.", true),
    Network("Speech recognition could not reach its service. Check your connection and retry.", true),
    Busy("Speech recognition is busy. Please try again.", true),
    Audio("Microphone capture failed. Check microphone access and try again."),
    StartFailed("Unable to start speech recognition. Please try again.", true),
    RecognitionFailed("Speech recognition failed. Please try again.", true),
    Cancelled("Voice session cancelled."),
    Processing("I couldn't process that request. Please try again.", true)
}

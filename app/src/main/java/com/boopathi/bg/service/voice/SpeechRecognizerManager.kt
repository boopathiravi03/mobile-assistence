package com.boopathi.bg.service.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerManager(
    private val context: Context,
    private val onReadyListener: () -> Unit,
    private val onBeginningListener: () -> Unit,
    private val onRmsChangedListener: (Float) -> Unit,
    private val onResultListener: (String) -> Unit,
    private val onErrorListener: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    init {
        mainHandler.post {
            initRecognizer()
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("SpeechRecognizerManager", "Speech recognition not available on this device")
            onErrorListener("Speech recognition service is not available on this device.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        } catch (e: Exception) {
            Log.e("SpeechRecognizerManager", "Failed to create SpeechRecognizer: ${e.message}")
            onErrorListener("Failed to initialize speech recognition: ${e.localizedMessage}")
        }
    }

    fun startListening(languageCode: String = "en-US") {
        mainHandler.post {
            if (speechRecognizer == null) {
                initRecognizer()
            }

            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                Log.e("SpeechRecognizerManager", "Error starting listening: ${e.message}")
                isListening = false
                onErrorListener("Unable to start listening: ${e.localizedMessage}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.w("SpeechRecognizerManager", "Error stopping listening: ${e.message}")
                }
                isListening = false
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            } catch (e: Exception) {
                Log.w("SpeechRecognizerManager", "Error destroying speech recognizer: ${e.message}")
            }
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onReadyListener()
            }

            override fun onBeginningOfSpeech() {
                onBeginningListener()
            }

            override fun onRmsChanged(rmsdB: Float) {
                onRmsChangedListener(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Check your microphone."
                    SpeechRecognizer.ERROR_CLIENT -> "Client error. Please try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue encountered during speech recognition."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Tap to try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy. Please try again."
                    SpeechRecognizer.ERROR_SERVER -> "Speech server error."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap to speak."
                    else -> "Speech recognition error ($error)."
                }
                onErrorListener(message)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull()?.trim() ?: ""
                if (recognizedText.isNotEmpty()) {
                    onResultListener(recognizedText)
                } else {
                    onErrorListener("No speech recognized.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}

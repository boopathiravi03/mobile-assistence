package com.boopathi.bg.service.voice

import android.content.Context
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.service.command.CommandProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceManager(
    context: Context,
    private val commandProcessor: CommandProcessor,
    private val scope: CoroutineScope
) {

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private var autoResetJob: Job? = null

    private val speechRecognizerManager: SpeechRecognizerManager = SpeechRecognizerManager(
        context = context,
        onReadyListener = {
            _assistantState.value = AssistantState.LISTENING
            _errorMessage.value = null
        },
        onBeginningListener = {
            _assistantState.value = AssistantState.LISTENING
        },
        onRmsChangedListener = { rms ->
            _audioRmsDb.value = rms
        },
        onResultListener = { recognizedText ->
            processUserSpeech(recognizedText)
        },
        onErrorListener = { error ->
            handleVoiceError(error)
        }
    )

    private val textToSpeechManager: TextToSpeechManager = TextToSpeechManager(
        context = context,
        onInitialized = { success ->
            _ttsReady.value = success
        }
    )

    fun startListening() {
        autoResetJob?.cancel()
        textToSpeechManager.stop()
        _errorMessage.value = null
        _assistantState.value = AssistantState.LISTENING
        speechRecognizerManager.startListening()
    }

    fun stopListening() {
        speechRecognizerManager.stopListening()
        if (_assistantState.value == AssistantState.LISTENING) {
            _assistantState.value = AssistantState.IDLE
        }
    }

    fun processUserSpeech(userSpeech: String) {
        autoResetJob?.cancel()
        speechRecognizerManager.stopListening()
        _assistantState.value = AssistantState.PROCESSING
        _audioRmsDb.value = 0f

        scope.launch(Dispatchers.Main) {
            val result = commandProcessor.processCommand(userSpeech)
            speakResponse(result.spokenResponse)
        }
    }

    fun speakResponse(response: String) {
        if (!_ttsReady.value) {
            // If TTS is not yet ready, still display response and return to IDLE
            _assistantState.value = AssistantState.IDLE
            return
        }

        _assistantState.value = AssistantState.SPEAKING
        textToSpeechManager.speak(
            text = response,
            onStart = {
                _assistantState.value = AssistantState.SPEAKING
            },
            onDone = {
                _assistantState.value = AssistantState.IDLE
            },
            onError = { err ->
                _assistantState.value = AssistantState.IDLE
            }
        )
    }

    fun stopAll() {
        autoResetJob?.cancel()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
        _audioRmsDb.value = 0f
        _assistantState.value = AssistantState.IDLE
    }

    private fun handleVoiceError(error: String) {
        _errorMessage.value = error
        _assistantState.value = AssistantState.ERROR
        _audioRmsDb.value = 0f

        // Auto-revert from error to IDLE after 3.5 seconds
        autoResetJob?.cancel()
        autoResetJob = scope.launch {
            delay(3500)
            if (_assistantState.value == AssistantState.ERROR) {
                _assistantState.value = AssistantState.IDLE
                _errorMessage.value = null
            }
        }
    }

    fun destroy() {
        autoResetJob?.cancel()
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }
}


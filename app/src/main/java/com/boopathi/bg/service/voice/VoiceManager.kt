package com.boopathi.bg.service.voice

import android.content.Context
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.service.command.CommandProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates a single recognition, command, and speech response session. */
class VoiceManager(
    context: Context,
    private val commandProcessor: CommandProcessor,
    private val scope: CoroutineScope,
    private val onCommandProcessed: ((CommandResult) -> Unit)? = null
) {
    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()
    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val sessionGate = VoiceSessionGate()
    private var activeLanguageCode: String = "en-US"
    private var commandJob: Job? = null
    private var autoResetJob: Job? = null

    private val speechRecognizerManager = SpeechRecognizerManager(
        context = context.applicationContext,
        onReadyListener = { id -> if (isCurrent(id)) transitionTo(AssistantState.LISTENING) },
        onBeginningListener = { id -> if (isCurrent(id)) transitionTo(AssistantState.LISTENING) },
        onEndListener = { id -> if (isCurrent(id)) transitionTo(AssistantState.RECOGNIZING) },
        onPartialListener = { id, text -> if (isCurrent(id)) _transcript.value = text },
        onRmsChangedListener = { id, rms -> if (isCurrent(id)) _audioRmsDb.value = rms },
        onResultListener = { id, text -> if (sessionGate.claimFinalResult(id)) processUserSpeech(text, id) },
        onErrorListener = { id, error -> if (sessionGate.claimFinalResult(id)) handleVoiceError(error) }
    )
    private val textToSpeechManager = TextToSpeechManager(
        context.applicationContext,
        onInitialized = { success ->
            _ttsReady.value = success
            if (!success && _errorMessage.value == null) {
                _errorMessage.value = "Text to speech is unavailable. BG will show replies on screen."
            }
        },
        onStatus = { status -> _errorMessage.value = status }
    )

    fun startListening(languageCode: String = "en-US") {
        if (_assistantState.value == AssistantState.LISTENING || _assistantState.value == AssistantState.PROCESSING || _assistantState.value == AssistantState.SPEAKING) return
        cancelCurrentSession(showCancelled = false)
        val id = sessionGate.begin()
        activeLanguageCode = when (languageCode.lowercase()) {
            "ta", "ta-in", "tamil" -> "ta-IN"
            else -> "en-US" // Tanglish is Latin-script Tamil; use English recognition and preserve the transcript.
        }
        _errorMessage.value = null
        _transcript.value = ""
        _audioRmsDb.value = 0f
        transitionTo(AssistantState.LISTENING)
        speechRecognizerManager.startListening(id, activeLanguageCode)
    }

    fun stopListening() {
        if (_assistantState.value == AssistantState.LISTENING) cancelCurrentSession(showCancelled = true)
    }

    fun processUserSpeech(userSpeech: String) {
        cancelCurrentSession(showCancelled = false)
        val id = sessionGate.begin()
        _transcript.value = userSpeech.trim()
        processUserSpeech(userSpeech, id)
    }

    private fun processUserSpeech(userSpeech: String, sessionId: Long) {
        if (!isCurrent(sessionId) || userSpeech.isBlank()) {
            if (isCurrent(sessionId)) handleVoiceError(VoiceFailure.NoSpeech)
            return
        }
        _transcript.value = userSpeech.trim()
        speechRecognizerManager.stopListening(sessionId)
        transitionTo(AssistantState.PROCESSING)
        _audioRmsDb.value = 0f
        commandJob?.cancel()
        commandJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = commandProcessor.processCommand(userSpeech)
                if (!isCurrent(sessionId)) return@launch
                onCommandProcessed?.invoke(result)
                speakResponse(result.spokenResponse, sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(sessionId)) handleVoiceError(VoiceFailure.Processing)
            }
        }
    }

    fun speakResponse(response: String) {
        val id = sessionGate.begin()
        speakResponse(response, id)
    }

    private fun speakResponse(response: String, sessionId: Long) {
        if (!isCurrent(sessionId)) return
        if (!_ttsReady.value || response.isBlank()) {
            transitionTo(AssistantState.IDLE)
            sessionGate.invalidate(sessionId)
            return
        }
        transitionTo(AssistantState.SPEAKING)
        textToSpeechManager.speak(
            text = response,
            languageCode = activeLanguageCode,
            onStart = { if (isCurrent(sessionId)) transitionTo(AssistantState.SPEAKING) },
            onDone = { if (isCurrent(sessionId)) finishSession(sessionId) },
            onError = { if (isCurrent(sessionId)) { _errorMessage.value = it; finishSession(sessionId) } }
        )
    }

    fun stopAll() = cancelCurrentSession(showCancelled = true)

    private fun cancelCurrentSession(showCancelled: Boolean) {
        val cancelledDuringProcessing = _assistantState.value == AssistantState.PROCESSING ||
            _assistantState.value == AssistantState.EXECUTING
        sessionGate.invalidate()
        autoResetJob?.cancel()
        commandJob?.cancel()
        commandJob = null
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
        // Cancellation is local: it does not undo an external action already dispatched.
        commandProcessor.multiStepAgent?.cancelCurrentTask("Voice operation cancelled")
        commandProcessor.crossAppWorkflowExecutor?.cancelWorkflow("Voice operation cancelled")
        _audioRmsDb.value = 0f
        transitionTo(if (showCancelled) AssistantState.CANCELLED else AssistantState.IDLE)
        if (showCancelled && cancelledDuringProcessing) {
            _errorMessage.value = "Stopped locally. An external action may already have completed; check before retrying."
        }
        if (showCancelled) {
            scope.launch { kotlinx.coroutines.delay(1200); if (_assistantState.value == AssistantState.CANCELLED) transitionTo(AssistantState.IDLE) }
        }
    }

    private fun finishSession(id: Long) {
        if (!isCurrent(id)) return
        sessionGate.invalidate(id)
        transitionTo(AssistantState.IDLE)
    }

    private fun transitionTo(next: AssistantState) {
        val current = _assistantState.value
        if (VoiceStateTransitions.allows(current, next)) _assistantState.value = next
    }

    private fun isCurrent(id: Long) = sessionGate.isCurrent(id)

    private fun handleVoiceError(error: VoiceFailure) {
        sessionGate.invalidate()
        _errorMessage.value = error.userMessage
        transitionTo(AssistantState.ERROR)
        _audioRmsDb.value = 0f
    }

    fun destroy() {
        cancelCurrentSession(showCancelled = false)
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }
}

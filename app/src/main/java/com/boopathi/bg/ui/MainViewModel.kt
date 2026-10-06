package com.boopathi.bg.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.InMemoryMemoryManager
import com.boopathi.bg.service.memory.MemoryManager
import com.boopathi.bg.service.voice.VoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val memoryManager: MemoryManager = InMemoryMemoryManager()
    private val commandProcessor: CommandProcessor = CommandProcessor(memoryManager)

    private var voiceManager: VoiceManager? = null

    val messages: StateFlow<List<ChatMessage>> = memoryManager.getConversationFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val providerStatus: String = "● " + commandProcessor.getActiveProviderBadge()

    fun initVoiceManager(context: Context) {
        if (voiceManager == null) {
            val vm = VoiceManager(
                context = context.applicationContext,
                commandProcessor = commandProcessor,
                scope = viewModelScope
            )
            voiceManager = vm

            // Collect internal voice manager flows
            viewModelScope.launchWhenCreatedCompat {
                vm.assistantState.collect { _assistantState.value = it }
            }
            viewModelScope.launchWhenCreatedCompat {
                vm.audioRmsDb.collect { _audioRmsDb.value = it }
            }
            viewModelScope.launchWhenCreatedCompat {
                vm.errorMessage.collect { _errorMessage.value = it }
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWhenCreatedCompat(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) {
        this.launch(block = block)
    }

    fun onMicPermissionResult(isGranted: Boolean) {
        _hasMicPermission.value = isGranted
        if (!isGranted) {
            _errorMessage.value = "Microphone permission is required to listen to voice commands."
        } else {
            _errorMessage.value = null
        }
    }

    fun onOrbClicked(requestPermission: () -> Unit) {
        if (!_hasMicPermission.value) {
            requestPermission()
            return
        }

        val vm = voiceManager ?: return
        when (_assistantState.value) {
            AssistantState.IDLE -> vm.startListening()
            AssistantState.LISTENING -> vm.stopListening()
            AssistantState.SPEAKING -> vm.stopAll()
            AssistantState.PROCESSING -> Unit
            AssistantState.ERROR -> vm.startListening()
        }
    }

    fun executeCommand(commandText: String) {
        val vm = voiceManager ?: return
        vm.processUserSpeech(commandText)
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
        voiceManager = null
    }
}


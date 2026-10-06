package com.boopathi.bg.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.MemoryManager
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.voice.VoiceManager
import com.boopathi.bg.service.tool.FlashlightTool
import com.boopathi.bg.service.tool.GoHomeTool
import com.boopathi.bg.service.tool.OpenAppTool
import com.boopathi.bg.service.tool.OpenSettingsTool
import com.boopathi.bg.service.tool.ToolRegistry
import com.boopathi.bg.service.tool.ToolResult
import com.boopathi.bg.service.tool.VolumeTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel @JvmOverloads constructor(
    application: Application,
    customMemoryManager: MemoryManager? = null,
    customToolRegistry: ToolRegistry? = null
) : AndroidViewModel(application) {

    // Clean Architecture: Tool Registry with Phase 3 Android Tools
    val toolRegistry: ToolRegistry = customToolRegistry ?: ToolRegistry().apply {
        register(OpenAppTool(application))
        register(FlashlightTool(application))
        register(VolumeTool(application))
        register(OpenSettingsTool(application))
        register(GoHomeTool(application))
    }

    // Dependency injection / Clean architecture
    val memoryManager: MemoryManager = customMemoryManager ?: RoomMemoryManager(
        repository = RoomBGRepository(BGDatabase.getInstance(application)),
        scope = viewModelScope
    )
    private val commandProcessor: CommandProcessor = CommandProcessor(
        memoryManager = memoryManager,
        toolRegistry = toolRegistry
    )

    private var voiceManager: VoiceManager? = null

    val messages: StateFlow<List<ChatMessage>> = memoryManager.getConversationFlow()

    private val _lastToolResult = MutableStateFlow<ToolResult?>(null)
    val lastToolResult: StateFlow<ToolResult?> = _lastToolResult.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Owner Profile & Memory Inspection State
    private val _ownerProfile = MutableStateFlow(OwnerProfile())
    val ownerProfile: StateFlow<OwnerProfile> = _ownerProfile.asStateFlow()

    private val _showProfileDialog = MutableStateFlow(false)
    val showProfileDialog: StateFlow<Boolean> = _showProfileDialog.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _noteCount = MutableStateFlow(0)
    val noteCount: StateFlow<Int> = _noteCount.asStateFlow()

    private val _taskCount = MutableStateFlow(0)
    val taskCount: StateFlow<Int> = _taskCount.asStateFlow()

    val providerStatus: String = "● " + commandProcessor.getActiveProviderBadge()

    init {
        refreshProfileAndStats()
    }

    fun refreshProfileAndStats() {
        viewModelScope.launch {
            _ownerProfile.value = memoryManager.getOwnerProfile()
            val allMemories = memoryManager.getMemories()
            _memoryCount.value = allMemories.filter { it.type != MemoryEntity.TYPE_NOTE }.size
            _noteCount.value = allMemories.filter { it.type == MemoryEntity.TYPE_NOTE }.size
            _taskCount.value = memoryManager.getTasks(completed = false).size
        }
    }

    fun openProfile() {
        refreshProfileAndStats()
        _showProfileDialog.value = true
    }

    fun closeProfile() {
        _showProfileDialog.value = false
    }

    fun updateLanguage(langCode: String) {
        viewModelScope.launch {
            val current = _ownerProfile.value
            val updated = current.copy(preferredLanguage = langCode)
            memoryManager.updateOwnerProfile(updated)
            _ownerProfile.value = updated
        }
    }

    fun initVoiceManager(context: Context) {
        if (voiceManager == null) {
            val vm = VoiceManager(
                context = context.applicationContext,
                commandProcessor = commandProcessor,
                scope = viewModelScope,
                onCommandProcessed = { result ->
                    _lastToolResult.value = result.toolResult
                    refreshProfileAndStats()
                }
            )
            voiceManager = vm

            // Collect internal voice manager flows
            viewModelScope.launch {
                vm.assistantState.collect {
                    _assistantState.value = it
                    if (it == AssistantState.IDLE) {
                        refreshProfileAndStats()
                    }
                }
            }
            viewModelScope.launch {
                vm.audioRmsDb.collect { _audioRmsDb.value = it }
            }
            viewModelScope.launch {
                vm.errorMessage.collect { _errorMessage.value = it }
            }
        }
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
        val vm = voiceManager
        if (vm != null) {
            vm.processUserSpeech(commandText)
        } else {
            viewModelScope.launch {
                val result = commandProcessor.processCommand(commandText)
                _lastToolResult.value = result.toolResult
                refreshProfileAndStats()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
        voiceManager = null
    }
}

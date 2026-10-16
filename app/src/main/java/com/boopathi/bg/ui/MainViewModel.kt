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
import com.boopathi.bg.service.accessibility.AndroidAccessibilityBridge
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.AppActionResult
import com.boopathi.bg.service.agent.AppAgentState
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.service.reminder.ReminderManager
import com.boopathi.bg.service.reminder.ReminderNotifications

class MainViewModel @JvmOverloads constructor(
    application: Application,
    customMemoryManager: MemoryManager? = null,
    customToolRegistry: ToolRegistry? = null,
    customAccessibilityBridge: AccessibilityBridge? = null,
    customOwnerAuthGate: com.boopathi.bg.service.security.OwnerAuthGate? = null
) : AndroidViewModel(application) {

    @Volatile
    var ownerAuthGate: com.boopathi.bg.service.security.OwnerAuthGate =
        customOwnerAuthGate ?: com.boopathi.bg.service.security.UnavailableOwnerAuthGate()
        private set

    private val confirmationMutex = Mutex()
    private val deviceCredentialOwnerAuthGate = com.boopathi.bg.service.security.DeviceCredentialOwnerAuthGate(application)

    internal fun attachDeviceCredentialAuthLauncher(launch: (Intent) -> Unit) {
        deviceCredentialOwnerAuthGate.attachLauncher(launch)
        ownerAuthGate = deviceCredentialOwnerAuthGate
    }

    internal fun onDeviceCredentialAuthResult(resultCode: Int) {
        deviceCredentialOwnerAuthGate.onActivityResult(resultCode)
    }

    // Clean Architecture: Tool Registry with Phase 3 Android Tools
    val toolRegistry: ToolRegistry = customToolRegistry ?: ToolRegistry().apply {
        register(OpenAppTool(application))
        register(FlashlightTool(application))
        register(VolumeTool(application))
        register(OpenSettingsTool(application))
        register(GoHomeTool(application))
    }

    // Phase 4 & Phase 5A Controlled App Agent & Bounded Multi-Step Engine
    val accessibilityBridge: AccessibilityBridge = customAccessibilityBridge ?: AndroidAccessibilityBridge(application)
    val appAgent: AppAgent = AppAgent(bridge = accessibilityBridge, toolRegistry = toolRegistry)
    val multiStepAgent: com.boopathi.bg.service.agent.MultiStepAgent = com.boopathi.bg.service.agent.MultiStepAgent(appAgent = appAgent)

    // Phase 5B Notification Access Manager & Safe Messaging
    val notificationAccessManager: com.boopathi.bg.service.notification.NotificationAccessManager =
        com.boopathi.bg.service.notification.NotificationAccessManager.getInstance(application)
    val notificationStatus = notificationAccessManager.status
    val recentNotifications = notificationAccessManager.repository.notificationFlow

    // Phase 6C Cross-App Workflow Orchestration
    val crossAppWorkflowPlanner: com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner =
        com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner()
    val crossAppWorkflowExecutor: com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor =
        com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor(
            appAgent = appAgent,
            multiStepAgent = multiStepAgent,
            toolRegistry = toolRegistry
        )

    val currentWorkflow = crossAppWorkflowExecutor.currentWorkflow
    val workflowStatus = crossAppWorkflowExecutor.workflowStatus
    val workflowProgressMessage = crossAppWorkflowExecutor.progressMessage

    // Dependency injection / Clean architecture
    private val bgRepository = RoomBGRepository(BGDatabase.getInstance(application))
    val memoryManager: MemoryManager = customMemoryManager ?: RoomMemoryManager(repository = bgRepository)
    private val reminderManager = ReminderManager(application, bgRepository)
    val reminderTasks: Flow<List<TaskEntity>> = reminderManager.reminderTasks

    private val _reminderMessage = MutableStateFlow<String?>(null)
    val reminderMessage: StateFlow<String?> = _reminderMessage.asStateFlow()
    private val _reminderBusy = MutableStateFlow(false)
    val reminderBusy: StateFlow<Boolean> = _reminderBusy.asStateFlow()

    private val _notificationPermission = MutableStateFlow(ReminderNotifications.hasPermission(application))
    val notificationPermission: StateFlow<Boolean> = _notificationPermission.asStateFlow()

    private val commandProcessor: CommandProcessor = CommandProcessor(
        memoryManager = memoryManager,
        toolRegistry = toolRegistry,
        appAgent = appAgent,
        multiStepAgent = multiStepAgent,
        notificationAccessManager = notificationAccessManager,
        crossAppWorkflowPlanner = crossAppWorkflowPlanner,
        crossAppWorkflowExecutor = crossAppWorkflowExecutor,
        scheduledTaskManager = reminderManager
    )

    private var voiceManager: VoiceManager? = null

    val messages: StateFlow<List<ChatMessage>> = memoryManager.getConversationFlow()

    private val _lastToolResult = MutableStateFlow<ToolResult?>(null)
    val lastToolResult: StateFlow<ToolResult?> = _lastToolResult.asStateFlow()

    val appAgentState: StateFlow<AppAgentState> = appAgent.state
    val lastAppActionResult: StateFlow<AppActionResult?> = appAgent.lastResult

    val currentTask: StateFlow<com.boopathi.bg.service.agent.AgentTask?> = multiStepAgent.currentTask
    val taskState: StateFlow<com.boopathi.bg.service.agent.AgentTaskState> = multiStepAgent.taskState

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _currentForegroundPackage = MutableStateFlow("")
    val currentForegroundPackage: StateFlow<String> = _currentForegroundPackage.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _voiceTranscript = MutableStateFlow("")
    val voiceTranscript: StateFlow<String> = _voiceTranscript.asStateFlow()

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
        viewModelScope.launch { reminderManager.reconcile() }
        viewModelScope.launch {
            (memoryManager as? RoomMemoryManager)?.loadPersistedMessages()
        }
        refreshProfileAndStats()
    }

    fun createReminder(text: String) {
        viewModelScope.launch {
            _reminderBusy.value = true
            try {
                val result = runCatching { reminderManager.createFromText(text) }
                _reminderMessage.value = result.getOrNull()?.message ?: "I couldn't save that reminder. Please try again."
            } finally { _reminderBusy.value = false }
        }
    }

    fun reminderAction(action: suspend (ReminderManager) -> String) {
        viewModelScope.launch {
            _reminderBusy.value = true
            try {
                _reminderMessage.value = runCatching { action(reminderManager) }
                    .getOrElse { "The reminder could not be updated. Please try again." }
            } finally { _reminderBusy.value = false }
        }
    }

    fun clearReminderMessage() { _reminderMessage.value = null }
    fun onNotificationPermissionResult() { _notificationPermission.value = ReminderNotifications.hasPermission(getApplication()) }

    fun openReminderNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, getApplication<Application>().packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    fun refreshProfileAndStats() {
        viewModelScope.launch {
            _ownerProfile.value = memoryManager.getOwnerProfile()
            val allMemories = memoryManager.getMemories()
            _memoryCount.value = allMemories.filter { it.type != MemoryEntity.TYPE_NOTE }.size
            _noteCount.value = allMemories.filter { it.type == MemoryEntity.TYPE_NOTE }.size
            _taskCount.value = memoryManager.getTasks(completed = false).size
            refreshAccessibilityStatus()
        }
    }

    fun refreshAccessibilityStatus() {
        _isAccessibilityEnabled.value = accessibilityBridge.isServiceEnabled()
        _isAccessibilityConnected.value = accessibilityBridge.isConnected()
        _currentForegroundPackage.value = accessibilityBridge.getCurrentPackage()
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
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
            viewModelScope.launch {
                vm.transcript.collect { _voiceTranscript.value = it }
            }
        }
    }

    fun onMicPermissionResult(isGranted: Boolean) {
        _hasMicPermission.value = isGranted
        if (!isGranted) {
            _errorMessage.value = "Microphone permission was denied. Enable it in Android Settings > Apps > BG > Permissions to use voice."
            _assistantState.value = AssistantState.ERROR
        } else {
            _errorMessage.value = null
            _assistantState.value = AssistantState.IDLE
        }
    }

    fun onOrbClicked(requestPermission: () -> Unit) {
        if (!_hasMicPermission.value) {
            _assistantState.value = AssistantState.REQUESTING_PERMISSION
            requestPermission()
            return
        }

        val vm = voiceManager ?: return
        when (_assistantState.value) {
            AssistantState.IDLE -> vm.startListening(_ownerProfile.value.preferredLanguage)
            AssistantState.LISTENING -> vm.stopListening()
            AssistantState.SPEAKING -> vm.stopAll()
            AssistantState.PROCESSING, AssistantState.RECOGNIZING, AssistantState.EXECUTING,
            AssistantState.WAITING_FOR_CONFIRMATION, AssistantState.REQUESTING_PERMISSION -> vm.stopAll()
            AssistantState.ERROR, AssistantState.CANCELLED, AssistantState.PAUSED -> vm.startListening(_ownerProfile.value.preferredLanguage)
        }
    }

    fun startListening(languageCode: String = _ownerProfile.value.preferredLanguage) {
        voiceManager?.startListening(languageCode)
    }

    fun cancelVoiceSession() {
        voiceManager?.stopAll()
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

    fun openNotificationSettings() {
        val intent = notificationAccessManager.getSettingsIntent()
        getApplication<Application>().startActivity(intent)
    }

    fun refreshNotificationStatus() {
        notificationAccessManager.checkStatus()
    }

    fun cancelTask() {
        multiStepAgent.cancelCurrentTask("Cancelled by user via UI")
        crossAppWorkflowExecutor.cancelWorkflow("Cancelled by user via UI")
    }

    fun confirmAction() {
        viewModelScope.launch {
            confirmationMutex.withLock {
                val task = multiStepAgent.currentTask.value ?: return@withLock
                if (task.state != com.boopathi.bg.service.agent.AgentTaskState.WAITING_FOR_CONFIRMATION) return@withLock

                suspend fun reject(message: String, blocked: Boolean = false) {
                    task.state = if (blocked) com.boopathi.bg.service.agent.AgentTaskState.BLOCKED else com.boopathi.bg.service.agent.AgentTaskState.FAILED
                    task.failureReason = message
                    multiStepAgent.publishCurrentTaskState(task)
                    memoryManager.logInteraction("Confirm", message)
                }

                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isConfirmationFresh(task.confirmationCreatedAt)) {
                    reject("That confirmation expired. Please request again.")
                    return@withLock
                }
                val draft = task.draft
                if (draft == null || !com.boopathi.bg.service.messaging.MessagingPolicy.isValidRecipient(draft.recipient) ||
                    draft.messageText.isBlank() || draft.messageText.length > com.boopathi.bg.service.messaging.MessagingPolicy.MAX_MESSAGE_LENGTH ||
                    draft.sourceApp !in com.boopathi.bg.service.messaging.MessagingPolicy.SUPPORTED_APPS) {
                    reject("Draft details are missing or unsupported.", blocked = true)
                    return@withLock
                }
                val request = task.confirmationRequest
                if (request == null) {
                    reject("The reviewed message changed. Prepare and review it again.", blocked = true)
                    return@withLock
                }
                val expectedActionFingerprint = com.boopathi.bg.service.security.MessageDispatchAuthorization.fingerprint(task.taskId, draft)
                val expectedConfirmationFingerprint = com.boopathi.bg.service.security.MessageDispatchAuthorization.confirmationFingerprint(task.taskId, draft, request)
                if (task.confirmationFingerprint != expectedConfirmationFingerprint) {
                    reject("The reviewed message changed. Prepare and review it again.", blocked = true)
                    return@withLock
                }
                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isContentSafeToSend(draft.messageText)) {
                    reject("I can't send messages containing sensitive authentication or financial information.", blocked = true)
                    return@withLock
                }
                if (task.sendExecuted) {
                    reject("A send was already attempted. Check WhatsApp before trying again.")
                    return@withLock
                }
                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isSendingPermitted()) {
                    reject("Message sending is currently disabled by policy.")
                    return@withLock
                }

                val authResult = ownerAuthGate.authenticate(
                    title = "Confirm Message Dispatch",
                    subtitle = "Authenticate to send this message to ${draft.recipient}."
                )
                if (!authResult.isSuccess) {
                    val authFailMsg = if (authResult.status == com.boopathi.bg.service.security.AuthResultStatus.CANCELLED)
                        "Authentication was cancelled. I didn't send the message."
                    else "Device authentication failed or is unavailable. I didn't send the message."
                    reject(authFailMsg)
                    return@withLock
                }

                // Revalidate the exact active task after returning from the system authentication UI.
                val active = multiStepAgent.currentTask.value
                if (active !== task || task.state != com.boopathi.bg.service.agent.AgentTaskState.WAITING_FOR_CONFIRMATION ||
                    task.sendExecuted || !com.boopathi.bg.service.messaging.MessagingPolicy.isConfirmationFresh(task.confirmationCreatedAt) ||
                    task.draft?.let { com.boopathi.bg.service.security.MessageDispatchAuthorization.fingerprint(task.taskId, it) } != expectedActionFingerprint ||
                    task.confirmationRequest?.let { com.boopathi.bg.service.security.MessageDispatchAuthorization.confirmationFingerprint(task.taskId, draft, it) } != expectedConfirmationFingerprint ||
                    task.confirmationFingerprint != expectedConfirmationFingerprint) {
                    reject("The confirmation no longer matches the active message. Review it again.", blocked = true)
                    return@withLock
                }

                val action = com.boopathi.bg.service.agent.AppAction.SendMessage(draft.recipient, draft.messageText, draft.sourceApp)
                val authorization = com.boopathi.bg.service.security.MessageDispatchAuthorization.issue(task.taskId, draft, System.currentTimeMillis())
                task.sendExecuted = true // Claim before dispatch to prevent duplicate taps or callbacks.
                val result = appAgent.executeAuthorizedSend(task.taskId, action, authorization)
                if (result.isSuccess) {
                    task.state = com.boopathi.bg.service.agent.AgentTaskState.SUCCESS
                    multiStepAgent.publishCurrentTaskState(task)
                    memoryManager.logInteraction("Confirm", "Message sent and verified.")
                } else {
                    task.state = com.boopathi.bg.service.agent.AgentTaskState.FAILED
                    task.failureReason = if (result.reason?.contains("clicked", ignoreCase = true) == true || result.reason?.contains("could not be confirmed", ignoreCase = true) == true)
                        "The send may have occurred, but BG couldn't verify it. Check WhatsApp before retrying."
                    else result.reason ?: "The message was not sent."
                    multiStepAgent.publishCurrentTaskState(task)
                    memoryManager.logInteraction("Confirm", task.failureReason!!)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
        voiceManager = null
    }
}

package com.boopathi.bg.service.command

import android.util.Log
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.ai.GeminiProvider
import com.boopathi.bg.service.ai.GroqProvider
import com.boopathi.bg.service.ai.LocalFallbackProvider
import com.boopathi.bg.service.memory.MemoryManager
import com.boopathi.bg.service.security.DefaultOwnerVerificationService
import com.boopathi.bg.service.security.OwnerVerificationService
import com.boopathi.bg.service.tool.ToolIntentDetector
import com.boopathi.bg.service.tool.ToolRegistry

class CommandProcessor(
    private val memoryManager: MemoryManager,
    private val localProvider: LocalFallbackProvider = LocalFallbackProvider(),
    private val geminiProvider: AIProvider = GeminiProvider(),
    private val groqProvider: AIProvider = GroqProvider(),
    private val ownerVerificationService: OwnerVerificationService = DefaultOwnerVerificationService(),
    val toolRegistry: ToolRegistry? = null,
    val appAgent: com.boopathi.bg.service.agent.AppAgent? = null,
    val multiStepAgent: com.boopathi.bg.service.agent.MultiStepAgent? = null,
    val notificationAccessManager: com.boopathi.bg.service.notification.NotificationAccessManager? = null,
    val messagingTargetResolver: com.boopathi.bg.service.messaging.MessagingTargetResolver = com.boopathi.bg.service.messaging.MessagingTargetResolver(),
    val agentPlanner: com.boopathi.bg.service.planner.AgentPlanner? = null,
    val agentPlanExecutor: com.boopathi.bg.service.planner.AgentPlanExecutor? = null,
    val crossAppWorkflowPlanner: com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner? = null,
    val crossAppWorkflowExecutor: com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor? = null,
    private val multimodalProvider: com.boopathi.bg.service.multimodal.MultimodalPerceptionProvider? = null,
    private val multimodalSessionManager: com.boopathi.bg.service.multimodal.MultimodalSessionManager? = null
) {

    companion object {
        private const val TAG = "CommandProcessor"
    }

    fun getActiveProviderBadge(): String {
        return when {
            geminiProvider.isAvailable -> "GEMINI"
            groqProvider.isAvailable -> "GROQ"
            else -> "LOCAL"
        }
    }

    suspend fun processCommand(userSpeech: String): CommandResult {
        val trimmed = userSpeech.trim()
        val startTime = System.currentTimeMillis()

        if (trimmed.isEmpty()) {
            return CommandResult(
                textResponse = "I didn't catch that. Tap the microphone when you're ready.",
                providerName = "System",
                isSuccess = false
            )
        }

        val isAuthorized = ownerVerificationService.verifySpeaker()
        val owner = memoryManager.getOwnerProfile()

        if (!isAuthorized) {
            val response = "Access denied. I only respond to my owner, ${owner.name}."
            memoryManager.logInteraction(trimmed, response)
            return CommandResult(
                textResponse = response,
                providerName = "Security",
                isSuccess = false
            )
        }

        // Priority 0: Explicit Cancellation Command ("Stop", "Cancel", "BG stop", "Cancel task", "Stop pannu", "BG niruthu")
        val lowerClean = trimmed.lowercase().removeSuffix(".").removeSuffix("!").trim()
        if (lowerClean in listOf("stop", "cancel", "bg stop", "cancel task", "stop task", "stop pannu", "cancel pannu", "bg niruthu", "niruthu")) {
            multimodalSessionManager?.clearSession()
            val agentCancelled = multiStepAgent?.cancelCurrentTask("Cancelled by user request") ?: false
            val workflowCancelled = crossAppWorkflowExecutor?.cancelWorkflow("Cancelled by user request") ?: false
            val cancelMsg = if (agentCancelled || workflowCancelled) "Task stopped." else "No active task running."
            return CommandResult(
                textResponse = cancelMsg,
                providerName = "MultiStepAgent",
                isSuccess = true
            )
        }

        // While an image session is active, follow-up questions remain scoped to
        // that image and its ephemeral conversation history.
        val activeImageSession = multimodalSessionManager?.getActiveSession()
        if (activeImageSession != null && multimodalProvider != null) {
            val analysis = multimodalProvider.analyzeImage(
                image = activeImageSession.image,
                prompt = trimmed,
                conversationHistory = activeImageSession.conversationTurns.toList()
            )
            val result = analysis.fold(
                onSuccess = { perception ->
                    val response = buildList {
                        add(perception.summary)
                        perception.extractedText?.takeIf { it.isNotBlank() }?.let { add("Text: $it") }
                        perception.visualElements.takeIf { it.isNotEmpty() }?.let { add("I can see: ${it.joinToString()}") }
                        perception.suggestedActions.takeIf { it.isNotEmpty() }?.let { add("Suggestions: ${it.joinToString()}") }
                    }.joinToString("\n")
                    multimodalSessionManager.recordTurn(trimmed, response)
                    CommandResult(textResponse = response, providerName = perception.providerName, isSuccess = true)
                },
                onFailure = { error ->
                    CommandResult(
                        textResponse = "I couldn't analyze that image: ${error.message ?: "analysis failed"}",
                        providerName = multimodalProvider.name,
                        isSuccess = false
                    )
                }
            )
            memoryManager.logInteraction(trimmed, result.textResponse)
            return result
        }

        if (isImageRelatedQuery(trimmed)) {
            val result = CommandResult(
                textResponse = "Please select or attach an image first, then ask me about it.",
                providerName = "Multimodal",
                isSuccess = false
            )
            memoryManager.logInteraction(trimmed, result.textResponse)
            return result
        }

        // Priority 0.5: Cross-App Workflow Orchestration (Phase 6C)
        val wfPlanner = crossAppWorkflowPlanner ?: if (appAgent != null) com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner() else null
        val wfExecutor = crossAppWorkflowExecutor ?: if (appAgent != null) com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor(
            appAgent = appAgent,
            multiStepAgent = multiStepAgent,
            toolRegistry = toolRegistry
        ) else null

        val plannedWorkflow = wfPlanner?.createDeterministicWorkflow(trimmed)
        if (plannedWorkflow != null && wfExecutor != null) {
            Log.d(TAG, "Router: Matched CrossAppWorkflow -> ${plannedWorkflow.goalDescription} (${plannedWorkflow.totalSteps} steps)")
            val wfResult = wfExecutor.executeWorkflow(plannedWorkflow, owner)
            return wfResult
        }

        // Routing Hierarchy:
        // Priority 1: Deterministic Memory, Note, or Task commands
        val memoryCommandResult = handleMemoryOrTaskCommand(trimmed, owner)

        // Priority 1.2: Notification Awareness Commands
        val notificationCommandResult = handleNotificationCommand(trimmed, owner)

        // Priority 1.3: Safe Messaging Preparation Commands
        val messagingPreparationResult = handleMessagingPreparationCommand(trimmed, owner)

        // Priority 1.5: Multi-step App Agent task decomposition & execution
        val planner = multiStepAgent?.appAgent?.planner ?: appAgent?.planner
        val agentTask = planner?.createTask(trimmed)
        val toolIntent = ToolIntentDetector.detectIntent(trimmed)

        val result = if (memoryCommandResult != null) {
            Log.d(TAG, "Router: Executed via [${memoryCommandResult.providerName}]")
            memoryCommandResult
        } else if (notificationCommandResult != null) {
            Log.d(TAG, "Router: Executed via [${notificationCommandResult.providerName}]")
            notificationCommandResult
        } else if (messagingPreparationResult != null) {
            Log.d(TAG, "Router: Executed via [${messagingPreparationResult.providerName}]")
            messagingPreparationResult
        } else if (localProvider.canHandleLocally(trimmed)) {
            // Priority 1 (continued): Standard local conversational commands (time, date, greeting, identity)
            Log.d(TAG, "Router: Matched local deterministic command -> LocalProvider")
            localProvider.processCommand(trimmed, owner)
        } else if (agentTask != null && multiStepAgent != null) {
            // Priority 2: Bounded Multi-Step Controlled App Agent
            Log.d(TAG, "Router: Matched MultiStepAgent task -> ${agentTask.normalizedGoal} (${agentTask.totalSteps} steps)")
            val executedTask = multiStepAgent.executeTask(agentTask)
            val responseText = formatAgentTaskResponse(executedTask, owner)
            CommandResult(
                textResponse = responseText,
                providerName = "MultiStepAgent",
                isSuccess = executedTask.state == com.boopathi.bg.service.agent.AgentTaskState.SUCCESS,
                agentTask = executedTask
            )
        } else if (agentTask != null && appAgent != null) {
            // Priority 2 (fallback): Single plan execution via AppAgent
            val appPlan = planner?.createPlan(trimmed)
            if (appPlan != null) {
                val agentResult = appAgent.executePlan(appPlan)
                val responseText = formatAppAgentResponse(agentResult, appPlan, owner)
                CommandResult(
                    textResponse = responseText,
                    providerName = "AppAgent",
                    isSuccess = agentResult.isSuccess,
                    appActionResult = agentResult
                )
            } else {
                CommandResult(
                    textResponse = "Unable to plan action.",
                    providerName = "AppAgent",
                    isSuccess = false
                )
            }
        } else if (toolIntent != null) {
            // Priority 3: Direct Android Tool command
            Log.d(TAG, "Command: \"$trimmed\" | Intent: ${toolIntent.toolName} | Params: ${toolIntent.parameters}")
            if (toolRegistry != null) {
                val toolResult = toolRegistry.execute(toolIntent.toolName, toolIntent.parameters)
                Log.i(TAG, "Command: \"$trimmed\" | Intent: ${toolIntent.toolName} | Tool: ${toolIntent.toolName} | Result: ${toolResult.status}")
                CommandResult(
                    textResponse = toolResult.message,
                    providerName = "ToolRegistry",
                    isSuccess = toolResult.isSuccess,
                    toolResult = toolResult
                )
            } else {
                Log.w(TAG, "ToolRegistry unavailable for detected intent ${toolIntent.toolName}")
                CommandResult(
                    textResponse = "I can't control device functions right now, ${owner.name}.",
                    providerName = "ToolRegistry",
                    isSuccess = false
                )
            }
        } else {
            // Priority 3: AI Provider with contextual memory retrieval
            val history = memoryManager.getRecentContext(maxTurns = 5)
            val relevantMemories = memoryManager.findRelevantMemories(trimmed)

            // Priority 4: Dynamic Agent Planner & Task Planning Engine
            val dynamicPlanner = agentPlanner ?: com.boopathi.bg.service.planner.AgentPlanner(
                aiProvider = if (geminiProvider.isAvailable) geminiProvider else if (groqProvider.isAvailable) groqProvider else null
            )
            val dynamicExecutor = agentPlanExecutor ?: com.boopathi.bg.service.planner.AgentPlanExecutor(
                appAgent = appAgent,
                multiStepAgent = multiStepAgent,
                toolRegistry = toolRegistry,
                notificationAccessManager = notificationAccessManager
            )

            val proposedPlan = dynamicPlanner.createDeterministicLocalPlan(trimmed)
            if (proposedPlan != null) {
                Log.d(TAG, "Router: Executing dynamic plan via AgentPlanExecutor: ${proposedPlan.goal.normalizedGoal}")
                dynamicExecutor.executePlan(proposedPlan, owner)
            } else if (geminiProvider.isAvailable) {
                Log.d(TAG, "Router: Routing natural language query to Gemini AI (context turns: ${history.size / 2}, relevant memories: ${relevantMemories.size})")
                val geminiResult = geminiProvider.processCommand(trimmed, owner, history, relevantMemories)
                if (geminiResult.isSuccess) {
                    geminiResult
                } else if (groqProvider.isAvailable) {
                    Log.d(TAG, "Router: Gemini failed; falling back to Groq AI")
                    groqProvider.processCommand(trimmed, owner, history, relevantMemories)
                } else {
                    Log.w(TAG, "Router: Gemini failed; checking relevant memories fallback")
                    fallbackWithMemoriesOrSafeMessage(relevantMemories, owner, "Gemini")
                }
            } else if (groqProvider.isAvailable) {
                Log.d(TAG, "Router: Routing query to Groq AI")
                groqProvider.processCommand(trimmed, owner, history, relevantMemories)
            } else {
                Log.d(TAG, "Router: No remote AI keys available; checking relevant memories fallback")
                fallbackWithMemoriesOrSafeMessage(relevantMemories, owner, "Local")
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Router completed command via [${result.providerName}] in ${duration}ms, success: ${result.isSuccess}")

        // Save turn to conversational memory
        memoryManager.logInteraction(trimmed, result.textResponse)

        return result
    }

    private fun isImageRelatedQuery(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "image", "picture", "photo", "screenshot", "this error", "this warning",
            "indha screen", "indha screenshot", "indha image", "indha error"
        ).any(lower::contains)
    }

    private suspend fun handleMemoryOrTaskCommand(text: String, owner: OwnerProfile): CommandResult? {
        val lower = text.lowercase().trim()
        val cleanText = text.trim()

        // 1. Remember command
        if (lower.startsWith("remember that ") || lower.startsWith("remember ")) {
            val payload = if (lower.startsWith("remember that ")) {
                cleanText.substring("remember that ".length).trim()
            } else {
                cleanText.substring("remember ".length).trim()
            }

            // Pattern: "my project is called BG" or "X is called Y"
            val isCalledMatch = Regex("(.+)\\s+is called\\s+(.+)", RegexOption.IGNORE_CASE).matchEntire(payload)
            if (isCalledMatch != null) {
                val rawSubject = isCalledMatch.groupValues[1].trim()
                val subject = if (rawSubject.startsWith("my ", ignoreCase = true)) {
                    rawSubject.substring(3).trim()
                } else {
                    rawSubject
                }
                val title = if (subject.equals("project", ignoreCase = true)) "Project name" else subject.replaceFirstChar { it.uppercase() }
                val content = isCalledMatch.groupValues[2].trim().removeSuffix(".")
                memoryManager.saveMemory(title = title, content = content, type = MemoryEntity.TYPE_FACT, importance = 2)
                return CommandResult(
                    textResponse = "Got it, ${owner.name}. I'll remember that.",
                    providerName = "Memory",
                    isSuccess = true
                )
            }

            // Pattern: "my favorite programming language is Kotlin" or "X is Y"
            val isMatch = Regex("(.+)\\s+is\\s+(.+)", RegexOption.IGNORE_CASE).matchEntire(payload)
            if (isMatch != null) {
                val rawSubject = isMatch.groupValues[1].trim()
                val cleanSubject = if (rawSubject.startsWith("my ", ignoreCase = true)) {
                    rawSubject.substring(3).trim()
                } else {
                    rawSubject
                }
                val content = isMatch.groupValues[2].trim().removeSuffix(".")
                val isPreference = cleanSubject.contains("favorite", ignoreCase = true) ||
                    cleanSubject.contains("like", ignoreCase = true) ||
                    cleanSubject.contains("prefer", ignoreCase = true)
                val type = if (isPreference) MemoryEntity.TYPE_PREFERENCE else MemoryEntity.TYPE_FACT
                val title = cleanSubject.replaceFirstChar { it.uppercase() }

                memoryManager.saveMemory(title = title, content = content, type = type, importance = 2)
                return CommandResult(
                    textResponse = "Got it, ${owner.name}. I'll remember that.",
                    providerName = "Memory",
                    isSuccess = true
                )
            }

            // General "remember that ..." fallback
            memoryManager.saveMemory(title = "Fact", content = payload.removeSuffix("."), type = MemoryEntity.TYPE_FACT, importance = 1)
            return CommandResult(
                textResponse = "Got it, ${owner.name}. I'll remember that.",
                providerName = "Memory",
                isSuccess = true
            )
        }

        // 2. Forget command: "Forget that my favorite language is Kotlin."
        if (lower.startsWith("forget that ") || lower.startsWith("forget ")) {
            val keyword = if (lower.startsWith("forget that ")) {
                cleanText.substring("forget that ".length).trim()
            } else {
                cleanText.substring("forget ".length).trim()
            }
            val cleanKeyword = keyword.removePrefix("my ").removeSuffix(".")
            val deleted = memoryManager.forgetMemory(cleanKeyword)
            return if (deleted) {
                CommandResult(
                    textResponse = "I've removed that from my memory, ${owner.name}.",
                    providerName = "Memory",
                    isSuccess = true
                )
            } else {
                CommandResult(
                    textResponse = "I couldn't find that memory to forget, ${owner.name}.",
                    providerName = "Memory",
                    isSuccess = true
                )
            }
        }

        // 3. "What do you remember about me?"
        if (lower.contains("what do you remember") || lower == "what do you know about me") {
            val memories = memoryManager.getMemories().filter { it.type != MemoryEntity.TYPE_NOTE }
            if (memories.isEmpty()) {
                return CommandResult(
                    textResponse = "I don't have any stored memories about you yet, ${owner.name}.",
                    providerName = "Memory",
                    isSuccess = true
                )
            }
            val summary = memories.joinToString("; ") { "${it.title}: ${it.content}" }
            return CommandResult(
                textResponse = "Here is what I remember about you, ${owner.name}: $summary.",
                providerName = "Memory",
                isSuccess = true
            )
        }

        // 4. Notes: "Note that I have a meeting tomorrow.", "Create a note: submit DBMS assignment."
        if (lower.startsWith("note that ") || lower.startsWith("note: ") ||
            lower.startsWith("create a note: ") || lower.startsWith("create a note ") ||
            lower.startsWith("create note: ") || lower.startsWith("create note ") ||
            lower.startsWith("make a note: ") || lower.startsWith("make a note ")
        ) {
            val content = cleanText
                .replaceFirst(Regex("^(note that|note:|create a note:|create a note|create note:|create note|make a note:|make a note)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .removeSuffix(".")

            memoryManager.saveNote(content)
            return CommandResult(
                textResponse = "Note saved, ${owner.name}.",
                providerName = "Notes",
                isSuccess = true
            )
        }

        // 5. "What are my notes?" / "Show my notes"
        if (lower.contains("what are my notes") || lower.contains("show my notes") || lower.contains("list my notes")) {
            val notes = memoryManager.getNotes()
            if (notes.isEmpty()) {
                return CommandResult(
                    textResponse = "You don't have any saved notes, ${owner.name}.",
                    providerName = "Notes",
                    isSuccess = true
                )
            }
            val formatted = notes.mapIndexed { idx, n -> "${idx + 1}. ${n.content}" }.joinToString("; ")
            return CommandResult(
                textResponse = "Here are your notes, ${owner.name}: $formatted.",
                providerName = "Notes",
                isSuccess = true
            )
        }

        // 6. Tasks: "Remind me to study DBMS.", "Add task..."
        if (lower.startsWith("remind me to ") || lower.startsWith("remind me ") ||
            lower.startsWith("add task ") || lower.startsWith("create task ")
        ) {
            val taskTitle = cleanText
                .replaceFirst(Regex("^(remind me to|remind me|add task|create task)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .removeSuffix(".")
                .replaceFirstChar { it.uppercase() }

            memoryManager.createTask(title = taskTitle)
            return CommandResult(
                textResponse = "Okay, ${owner.name}. I've added that task.",
                providerName = "Tasks",
                isSuccess = true
            )
        }

        // 7. "What tasks do I have?" / "Show my tasks"
        if (lower.contains("what tasks do i have") || lower.contains("what are my tasks") ||
            lower.contains("show my tasks") || lower.contains("list my tasks")
        ) {
            val tasks = memoryManager.getTasks(completed = false)
            if (tasks.isEmpty()) {
                return CommandResult(
                    textResponse = "You have no pending tasks, ${owner.name}.",
                    providerName = "Tasks",
                    isSuccess = true
                )
            }
            val formatted = tasks.mapIndexed { idx, t -> "${idx + 1}. ${t.title}" }.joinToString("; ")
            return CommandResult(
                textResponse = "Here are your tasks, ${owner.name}: $formatted.",
                providerName = "Tasks",
                isSuccess = true
            )
        }

        // 8. "Mark ... as complete"
        if (lower.startsWith("mark ") && (lower.endsWith(" as complete") || lower.endsWith(" complete"))) {
            val taskKeyword = cleanText
                .replaceFirst(Regex("^mark\\s+", RegexOption.IGNORE_CASE), "")
                .replaceFirst(Regex("\\s+as complete$", RegexOption.IGNORE_CASE), "")
                .replaceFirst(Regex("\\s+complete$", RegexOption.IGNORE_CASE), "")
                .replaceFirst(Regex("^task\\s+", RegexOption.IGNORE_CASE), "")
                .trim()

            val marked = memoryManager.markTaskComplete(taskKeyword)
            return if (marked) {
                CommandResult(
                    textResponse = "Okay, ${owner.name}. I've marked that task as complete.",
                    providerName = "Tasks",
                    isSuccess = true
                )
            } else {
                CommandResult(
                    textResponse = "I couldn't find a pending task matching that description, ${owner.name}.",
                    providerName = "Tasks",
                    isSuccess = true
                )
            }
        }

        // 9. Direct personal memory questions (e.g., "What is my favorite programming language?", "What is my project called?")
        if (lower.startsWith("what is my favorite ") || lower.startsWith("what's my favorite ") ||
            lower.startsWith("what is the name of my ") || lower.startsWith("what is my ") ||
            lower.startsWith("what's my ") || lower.contains("what programming language do i like") ||
            lower.contains("what is my project called")
        ) {
            val querySubject = lower
                .replaceFirst(Regex("^(what is my favorite|what's my favorite|what is my|what's my)\\s+"), "")
                .removeSuffix("?")
                .trim()

            val memories = memoryManager.findRelevantMemories(querySubject)
            if (memories.isNotEmpty()) {
                val bestMemory = memories.first()
                return CommandResult(
                    textResponse = bestMemory.content + ".",
                    providerName = "Memory",
                    isSuccess = true
                )
            }
        }

        return null
    }

    private suspend fun handleNotificationCommand(text: String, owner: OwnerProfile): CommandResult? {
        val lower = text.lowercase().trim().removeSuffix(".").removeSuffix("?").trim()

        val isNotificationQuery = lower in listOf(
            "what notifications came", "show my notifications", "any new messages", "any new notifications",
            "read my notifications", "read my latest message notification", "read latest message",
            "inniku enna notifications vandhurukku", "enna notifications vandhurukku",
            "yaar message pannirukanga", "latest message notification sollu", "notifications enna vandhurukku"
        ) || lower.startsWith("did ") && lower.endsWith(" message me") ||
           lower.contains("message pannirukara") || lower.contains("message pannirukangala") ||
           lower.startsWith("read ") && lower.contains("latest message")

        if (!isNotificationQuery) return null

        val mgr = notificationAccessManager
        if (mgr == null || !mgr.isAccessGranted()) {
            return CommandResult(
                textResponse = "${owner.name}, Notification Access is not enabled. Please enable BG in Notification Access settings first.",
                providerName = "NotificationService",
                isSuccess = false
            )
        }

        val repo = mgr.repository
        val recentNotifs = repo.getRecentNotifications()

        // 1. Check for specific sender query: E.g., "Did Karthi message me?" or "Karthi message pannirukara?"
        val senderQuery = when {
            lower.startsWith("did ") && lower.endsWith(" message me") -> {
                lower.removePrefix("did ").removeSuffix(" message me").trim()
            }
            lower.contains("message pannirukara") -> {
                lower.substringBefore("message pannirukara").trim()
            }
            lower.contains("message pannirukangala") -> {
                lower.substringBefore("message pannirukangala").trim()
            }
            else -> null
        }

        if (senderQuery != null && senderQuery.isNotEmpty()) {
            val matching = repo.findNotificationsForSender(senderQuery)
            return if (matching.isNotEmpty()) {
                val latest = matching.first()
                val summary = if (latest.isRedacted) {
                    "Yes, ${latest.senderName} sent a notification, but sensitive details are hidden for privacy."
                } else {
                    "Yes, ${latest.senderName} message pannirukanga on ${latest.appLabel}: \"${latest.text}\"."
                }
                CommandResult(
                    textResponse = summary,
                    providerName = "NotificationService",
                    isSuccess = true
                )
            } else {
                CommandResult(
                    textResponse = "No, ${owner.name}, $senderQuery kitendhu entha message notification-um varala.",
                    providerName = "NotificationService",
                    isSuccess = true
                )
            }
        }

        // 2. Read specific latest message request: "Read my latest message notification" or "Read Karthi's latest message"
        if (lower.contains("read ") || lower.contains("sollu")) {
            val messagingNotifs = repo.getRecentMessagingNotifications()
            if (messagingNotifs.isEmpty()) {
                return CommandResult(
                    textResponse = "There are no recent message notifications, ${owner.name}.",
                    providerName = "NotificationService",
                    isSuccess = true
                )
            }
            val latest = messagingNotifs.first()
            val text = if (latest.isRedacted) {
                "I received a sensitive message notification from ${latest.senderName}, but won't read security details aloud."
            } else {
                "${latest.appLabel}-la from ${latest.senderName}: \"${latest.text}\"."
            }
            return CommandResult(
                textResponse = text,
                providerName = "NotificationService",
                isSuccess = true
            )
        }

        // 3. General notification summary: "What notifications came?" / "Enna notifications vandhurukku?"
        if (recentNotifs.isEmpty()) {
            return CommandResult(
                textResponse = "You have no new notifications right now, ${owner.name}.",
                providerName = "NotificationService",
                isSuccess = true
            )
        }

        val messagingCount = recentNotifs.count { it.isMessaging }
        val apps = recentNotifs.map { it.appLabel }.distinct().take(3).joinToString(", ")
        val summary = if (messagingCount > 0) {
            val sampleSender = recentNotifs.firstOrNull { it.isMessaging }?.senderName ?: "someone"
            "${recentNotifs.size} notifications vandhirukku. $apps-la messages including from $sampleSender."
        } else {
            "${recentNotifs.size} new notifications from $apps."
        }

        return CommandResult(
            textResponse = summary,
            providerName = "NotificationService",
            isSuccess = true
        )
    }

    private suspend fun handleMessagingPreparationCommand(text: String, owner: OwnerProfile): CommandResult? {
        val lower = text.lowercase().trim().removeSuffix(".").removeSuffix("!").trim()

        // Match English: "send <message> to <recipient>", "prepare message to <recipient>: <message>"
        // Match Tanglish: "<recipient>-ku <message> message anuppu", "<recipient>-ku <message> anuppu", "<recipient> message anuppu"
        var recipient: String? = null
        var messageBody: String? = null
        var app = "WhatsApp"

        val tanglishRegex1 = Regex("^(.+?)(?:-ku|\\s+ku)\\s+(.+?)\\s+message\\s+(?:anuppu|prepare\\s+pannu)$", RegexOption.IGNORE_CASE)
        val tanglishRegex2 = Regex("^(.+?)(?:-ku|\\s+ku)\\s+(.+?)\\s+anuppu$", RegexOption.IGNORE_CASE)
        val englishRegex1 = Regex("^(?:send|prepare\\s+message)\\s+(?:to\\s+)?(.+?):?\\s+(?:message\\s+)?(.+)$", RegexOption.IGNORE_CASE)

        val m1 = tanglishRegex1.matchEntire(lower)
        val m2 = tanglishRegex2.matchEntire(lower)
        val m3 = englishRegex1.matchEntire(lower)

        when {
            m1 != null -> {
                recipient = m1.groupValues[1].trim()
                messageBody = m1.groupValues[2].trim()
            }
            m2 != null -> {
                recipient = m2.groupValues[1].trim()
                messageBody = m2.groupValues[2].trim()
            }
            m3 != null -> {
                recipient = m3.groupValues[1].trim()
                messageBody = m3.groupValues[2].trim()
            }
        }

        if (recipient == null || messageBody == null) return null
        if (recipient.isBlank() || messageBody.isBlank()) return null

        // Sensitive / Financial Content Safety Gate
        if (!com.boopathi.bg.service.messaging.MessagingPolicy.isContentSafeToSend(messageBody)) {
            val blockedMsg = "I can't send messages containing sensitive authentication or financial information."
            return CommandResult(
                textResponse = blockedMsg,
                providerName = "MessagingPolicy",
                isSuccess = false
            )
        }

        val resolution = messagingTargetResolver.resolveTarget(recipient)
        val resolvedTarget = resolution.target?.displayName ?: messagingTargetResolver.cleanRecipientName(recipient)

        val draft = com.boopathi.bg.service.messaging.MessageDraft(
            recipient = resolvedTarget,
            messageText = messageBody,
            sourceApp = app
        )

        val task = com.boopathi.bg.service.messaging.MessagePreparationTask.createPreparationTask(draft)

        return if (multiStepAgent != null) {
            val executedTask = multiStepAgent.executeTask(task)
            if (executedTask.state == com.boopathi.bg.service.agent.AgentTaskState.SUCCESS) {
                executedTask.state = com.boopathi.bg.service.agent.AgentTaskState.WAITING_FOR_CONFIRMATION
            }
            val responseText = "Prepared message draft for $resolvedTarget: '$messageBody'. Ready to send. Send this message?"
            CommandResult(
                textResponse = responseText,
                providerName = "MessagingPreparation",
                isSuccess = true,
                agentTask = executedTask
            )
        } else {
            CommandResult(
                textResponse = "Draft created for $resolvedTarget: '$messageBody'.",
                providerName = "MessagingPreparation",
                isSuccess = true
            )
        }
    }

    private fun fallbackWithMemoriesOrSafeMessage(
        relevantMemories: List<MemoryEntity>,
        owner: OwnerProfile,
        providerName: String
    ): CommandResult {
        return if (relevantMemories.isNotEmpty()) {
            val bestMemory = relevantMemories.first()
            CommandResult(
                textResponse = "Based on what I remember, your ${bestMemory.title.lowercase()} is ${bestMemory.content}.",
                providerName = "Memory",
                isSuccess = true
            )
        } else {
            CommandResult(
                textResponse = "I'm having trouble connecting to my AI service right now.",
                providerName = providerName,
                isSuccess = false
            )
        }
    }

    private fun formatAppAgentResponse(
        result: com.boopathi.bg.service.agent.AppActionResult,
        plan: com.boopathi.bg.service.agent.AppPlan,
        owner: OwnerProfile
    ): String {
        return when (result.status) {
            com.boopathi.bg.service.agent.AppActionResultStatus.REQUIRES_ACCESSIBILITY -> {
                "App Control permission is not enabled. Please enable BG in Accessibility settings first."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.BLOCKED -> {
                "Action blocked by safety policy. BG cannot interact with sensitive password, credential, or payment controls."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.REQUIRES_CONFIRMATION -> {
                "This action requires confirmation: ${result.reason ?: "Potentially sensitive action."}"
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.NOT_FOUND -> {
                result.reason ?: "Could not find '${result.targetDescription}' on the visible screen."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.TIMEOUT -> {
                result.reason ?: "Window timeout waiting for target application."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.UNAVAILABLE -> {
                result.reason ?: "Requested target is unavailable."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.REQUIRES_CLARIFICATION -> {
                "${owner.name}, ${result.reason ?: "Multiple matching options found. Please clarify which one you mean."}"
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.FAILED -> {
                result.reason ?: "Action failed on '${result.targetDescription}'."
            }
            com.boopathi.bg.service.agent.AppActionResultStatus.SUCCESS -> {
                when (result.actionType) {
                    com.boopathi.bg.service.agent.AppActionType.INSPECT_UI -> {
                        @Suppress("UNCHECKED_CAST")
                        val elements = result.data["elements"] as? List<com.boopathi.bg.service.agent.UiElementInfo>
                        if (elements.isNullOrEmpty()) {
                            "On your screen, I see no interactive UI elements."
                        } else {
                            val actionable = elements.filter { it.isClickable || it.isEditable }
                            val labels = actionable.map { it.displayLabel }.distinct().take(6)
                            if (labels.isNotEmpty()) {
                                "On your screen, I found ${elements.size} elements including: ${labels.joinToString(", ")}."
                            } else {
                                val textSample = elements.map { it.displayLabel }.distinct().take(4)
                                "On your screen, I see: ${textSample.joinToString(", ")}."
                            }
                        }
                    }
                    com.boopathi.bg.service.agent.AppActionType.TAP -> {
                        "Tapped ${result.targetDescription}."
                    }
                    com.boopathi.bg.service.agent.AppActionType.TYPE_TEXT -> {
                        "Entered text into ${result.targetDescription}."
                    }
                    com.boopathi.bg.service.agent.AppActionType.SCROLL -> {
                        "Scrolled screen successfully."
                    }
                    com.boopathi.bg.service.agent.AppActionType.BACK -> {
                        "Navigated back."
                    }
                    com.boopathi.bg.service.agent.AppActionType.HOME -> {
                        "Navigated to home screen."
                    }
                    com.boopathi.bg.service.agent.AppActionType.OPEN_APP,
                    com.boopathi.bg.service.agent.AppActionType.WAIT_FOR_WINDOW -> {
                        "Completed: ${plan.goal}."
                    }
                    com.boopathi.bg.service.agent.AppActionType.SEND_MESSAGE -> {
                        "Dispatched and verified message."
                    }
                }
            }
        }
    }

    private fun formatAgentTaskResponse(
        task: com.boopathi.bg.service.agent.AgentTask,
        owner: OwnerProfile
    ): String {
        return when (task.state) {
            com.boopathi.bg.service.agent.AgentTaskState.SUCCESS -> {
                "Completed task: ${task.normalizedGoal} (${task.totalSteps} steps verified)."
            }
            com.boopathi.bg.service.agent.AgentTaskState.BLOCKED -> {
                "Task blocked by safety policy. ${task.failureReason ?: "Sensitive security controls cannot be automated."}"
            }
            com.boopathi.bg.service.agent.AgentTaskState.WAITING_FOR_CONFIRMATION -> {
                val req = task.confirmationRequest
                "Confirmation required: ${req?.reason ?: "Potentially sensitive action (${req?.actionDescription})."} Proceed?"
            }
            com.boopathi.bg.service.agent.AgentTaskState.CANCELLED -> {
                "Task '${task.normalizedGoal}' was cancelled at step ${task.currentStepIndex + 1}."
            }
            com.boopathi.bg.service.agent.AgentTaskState.TIMEOUT -> {
                "Task timed out: ${task.failureReason ?: "Maximum execution time exceeded."}"
            }
            com.boopathi.bg.service.agent.AgentTaskState.WAITING_FOR_ACCESSIBILITY -> {
                "App Control permission is not enabled. Please enable BG in Accessibility settings first."
            }
            com.boopathi.bg.service.agent.AgentTaskState.PAUSED -> {
                "${owner.name}, ${task.failureReason ?: "Clarification needed to proceed."}"
            }
            com.boopathi.bg.service.agent.AgentTaskState.FAILED -> {
                "Task failed at step ${task.currentStepIndex + 1}/${task.totalSteps}: ${task.failureReason ?: "Verification failed."}"
            }
            else -> {
                "Task status: ${task.state}."
            }
        }
    }
}

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

class CommandProcessor(
    private val memoryManager: MemoryManager,
    private val localProvider: LocalFallbackProvider = LocalFallbackProvider(),
    private val geminiProvider: AIProvider = GeminiProvider(),
    private val groqProvider: AIProvider = GroqProvider(),
    private val ownerVerificationService: OwnerVerificationService = DefaultOwnerVerificationService()
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

        // Routing Hierarchy:
        // Priority 1: Deterministic Memory, Note, or Task commands
        val memoryCommandResult = handleMemoryOrTaskCommand(trimmed, owner)
        val result = if (memoryCommandResult != null) {
            Log.d(TAG, "Router: Executed via [${memoryCommandResult.providerName}]")
            memoryCommandResult
        } else if (localProvider.canHandleLocally(trimmed)) {
            // Priority 2: Standard local deterministic commands (time, date, greeting, identity)
            Log.d(TAG, "Router: Matched local deterministic command -> LocalProvider")
            localProvider.processCommand(trimmed, owner)
        } else {
            // Priority 3: AI Provider with contextual memory retrieval
            val history = memoryManager.getRecentContext(maxTurns = 5)
            val relevantMemories = memoryManager.findRelevantMemories(trimmed)

            if (geminiProvider.isAvailable) {
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
}

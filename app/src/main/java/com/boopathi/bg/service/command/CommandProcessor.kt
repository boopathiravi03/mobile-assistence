package com.boopathi.bg.service.command

import android.util.Log
import com.boopathi.bg.data.model.CommandResult
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

        // Routing decision:
        // Priority 1: Local deterministic command
        val result = if (localProvider.canHandleLocally(trimmed)) {
            Log.d(TAG, "Router: Matched local deterministic command -> LocalProvider")
            localProvider.processCommand(trimmed, owner)
        } else {
            // Priority 2: Gemini AI (with conversational history context)
            val history = memoryManager.getRecentContext(maxTurns = 5)

            if (geminiProvider.isAvailable) {
                Log.d(TAG, "Router: Routing natural language query to Gemini AI (context turns: ${history.size / 2})")
                val geminiResult = geminiProvider.processCommand(trimmed, owner, history)
                if (geminiResult.isSuccess) {
                    geminiResult
                } else if (groqProvider.isAvailable) {
                    Log.d(TAG, "Router: Gemini failed; falling back to Groq AI")
                    groqProvider.processCommand(trimmed, owner, history)
                } else {
                    Log.w(TAG, "Router: Gemini failed; falling back to safe message")
                    CommandResult(
                        textResponse = "I'm having trouble connecting to my AI service right now.",
                        providerName = "Gemini",
                        isSuccess = false
                    )
                }
            } else if (groqProvider.isAvailable) {
                Log.d(TAG, "Router: Routing query to Groq AI")
                groqProvider.processCommand(trimmed, owner, history)
            } else {
                Log.d(TAG, "Router: No remote AI keys available; returning graceful message")
                CommandResult(
                    textResponse = "I'm having trouble connecting to my AI service right now.",
                    providerName = "Local",
                    isSuccess = false
                )
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Router completed command via [${result.providerName}] in ${duration}ms, success: ${result.isSuccess}")

        // Save turn to conversational memory
        memoryManager.logInteraction(trimmed, result.textResponse)

        return result
    }
}

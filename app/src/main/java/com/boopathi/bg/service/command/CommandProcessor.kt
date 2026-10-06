package com.boopathi.bg.service.command

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

    fun getActiveProviderStatus(): String {
        return when {
            geminiProvider.isAvailable -> "Gemini AI (Primary)"
            groqProvider.isAvailable -> "Groq AI (Fallback)"
            else -> "Local Command Engine (Offline)"
        }
    }

    suspend fun processCommand(userSpeech: String): CommandResult {
        val trimmed = userSpeech.trim()
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

        // 1. Check local deterministic commands first
        val result = if (localProvider.canHandleLocally(trimmed)) {
            localProvider.processCommand(trimmed, owner)
        } else if (geminiProvider.isAvailable) {
            val geminiResult = geminiProvider.processCommand(trimmed, owner)
            if (geminiResult.isSuccess) {
                geminiResult
            } else if (groqProvider.isAvailable) {
                groqProvider.processCommand(trimmed, owner)
            } else {
                localProvider.processCommand(trimmed, owner)
            }
        } else if (groqProvider.isAvailable) {
            val groqResult = groqProvider.processCommand(trimmed, owner)
            if (groqResult.isSuccess) {
                groqResult
            } else {
                localProvider.processCommand(trimmed, owner)
            }
        } else {
            // Local fallback for unhandled commands
            localProvider.processCommand(trimmed, owner)
        }

        // Log into memory
        memoryManager.logInteraction(trimmed, result.textResponse)

        return result
    }
}


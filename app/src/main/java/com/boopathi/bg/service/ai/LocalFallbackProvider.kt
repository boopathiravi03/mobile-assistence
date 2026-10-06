package com.boopathi.bg.service.ai

import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFallbackProvider : AIProvider {

    override val name: String = "Local Command Engine"
    override val isAvailable: Boolean = true

    fun canHandleLocally(prompt: String): Boolean {
        val normalized = normalize(prompt)
        return isGreeting(normalized) ||
                isIdentity(normalized) ||
                isTimeQuery(normalized) ||
                isSmallTalk(normalized)
    }

    override suspend fun processCommand(
        prompt: String,
        owner: OwnerProfile,
        conversationHistory: List<ChatMessage>,
        relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
    ): CommandResult {
        val normalized = normalize(prompt)

        return when {
            isGreeting(normalized) -> {
                CommandResult(
                    textResponse = "Hi ${owner.name}. How can I help you?",
                    providerName = name,
                    isSuccess = true
                )
            }
            isIdentity(normalized) -> {
                CommandResult(
                    textResponse = "I'm BG, your personal AI mobile assistant.",
                    providerName = name,
                    isSuccess = true
                )
            }
            isTimeQuery(normalized) -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                val currentTime = timeFormat.format(Date())
                val response = "The current time is $currentTime."
                CommandResult(
                    textResponse = response,
                    providerName = name,
                    isSuccess = true
                )
            }
            isSmallTalk(normalized) -> {
                CommandResult(
                    textResponse = "I'm functioning smoothly and ready to assist you, ${owner.name}.",
                    providerName = name,
                    isSuccess = true
                )
            }
            else -> {
                CommandResult(
                    textResponse = "I'm having trouble connecting to my AI service right now.",
                    providerName = name,
                    isSuccess = false
                )
            }
        }
    }

    private fun normalize(input: String): String {
        return input.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
    }

    private fun isGreeting(text: String): Boolean {
        return text in setOf("hi bg", "hey bg", "hello bg", "hi", "hey", "hello", "vanakkam bg", "vanakkam")
    }

    private fun isIdentity(text: String): Boolean {
        return text in setOf(
            "who are you",
            "what are you",
            "who is bg",
            "introduce yourself",
            "tell me about yourself",
            "what is your name"
        )
    }

    private fun isTimeQuery(text: String): Boolean {
        return text in setOf(
            "what time is it",
            "whats the time",
            "what is the time",
            "tell me the time",
            "current time",
            "time please",
            "time enna"
        )
    }

    private fun isSmallTalk(text: String): Boolean {
        return text in setOf(
            "how are you",
            "how are you doing",
            "how r u",
            "how are you bg",
            "eppadi iruka"
        )
    }
}

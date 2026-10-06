package com.boopathi.bg.service.ai

import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile

interface AIProvider {
    val name: String
    val isAvailable: Boolean
    suspend fun processCommand(
        prompt: String,
        owner: OwnerProfile,
        conversationHistory: List<ChatMessage> = emptyList()
    ): CommandResult
}

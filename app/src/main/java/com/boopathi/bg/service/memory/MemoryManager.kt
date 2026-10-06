package com.boopathi.bg.service.memory

import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.model.Sender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MemoryManager {
    suspend fun getOwnerProfile(): OwnerProfile
    suspend fun updateOwnerProfile(profile: OwnerProfile)
    suspend fun logInteraction(userSpeech: String, bgResponse: String)
    fun getConversationFlow(): StateFlow<List<ChatMessage>>
}

class InMemoryMemoryManager(
    private var currentOwner: OwnerProfile = OwnerProfile(name = "Boopathi", preferredLanguage = "en")
) : MemoryManager {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val messages = _messages.asStateFlow()

    override suspend fun getOwnerProfile(): OwnerProfile = currentOwner

    override suspend fun updateOwnerProfile(profile: OwnerProfile) {
        currentOwner = profile
    }

    override suspend fun logInteraction(userSpeech: String, bgResponse: String) {
        val userMsg = ChatMessage(sender = Sender.USER, text = userSpeech)
        val bgMsg = ChatMessage(sender = Sender.BG, text = bgResponse)
        _messages.value = _messages.value + listOf(userMsg, bgMsg)
    }

    override fun getConversationFlow(): StateFlow<List<ChatMessage>> = messages
}


package com.boopathi.bg.service.memory

import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.model.Sender
import com.boopathi.bg.data.repository.BGRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class RoomMemoryManager(
    private val repository: BGRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : MemoryManager {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val messages = _messages.asStateFlow()

    init {
        // Hydrate conversation flow from Room on startup
        scope.launch {
            loadPersistedMessages()
        }
    }

    suspend fun loadPersistedMessages() = withContext(Dispatchers.IO) {
        val stored = repository.getRecentMessages(limit = 50)
        val chatMessages = stored.map { entity ->
            ChatMessage(
                id = entity.id.toString(),
                sender = if (entity.sender.equals("USER", ignoreCase = true)) Sender.USER else Sender.BG,
                text = entity.message,
                timestamp = entity.timestamp
            )
        }
        _messages.value = chatMessages
    }

    override suspend fun getOwnerProfile(): OwnerProfile = withContext(Dispatchers.IO) {
        val entity = repository.getOwnerProfile()
        OwnerProfile(
            id = entity.id,
            name = entity.name,
            preferredLanguage = entity.preferredLanguage,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    override suspend fun updateOwnerProfile(profile: OwnerProfile): Unit = withContext(Dispatchers.IO) {
        repository.updateOwnerProfile(
            name = profile.name,
            preferredLanguage = profile.preferredLanguage
        )
    }

    override suspend fun logInteraction(userSpeech: String, bgResponse: String) {
        val userMsg = ChatMessage(sender = Sender.USER, text = userSpeech)
        val bgMsg = ChatMessage(sender = Sender.BG, text = bgResponse)

        // Instant UI update
        _messages.value = _messages.value + listOf(userMsg, bgMsg)

        // Room persistence
        withContext(Dispatchers.IO) {
            repository.saveMessage(sender = "USER", message = userSpeech)
            repository.saveMessage(sender = "BG", message = bgResponse)
        }
    }

    override fun getConversationFlow(): StateFlow<List<ChatMessage>> = messages

    override suspend fun getRecentContext(maxTurns: Int): List<ChatMessage> {
        val currentList = _messages.value
        val maxMessages = maxTurns * 2
        return if (currentList.size <= maxMessages) {
            currentList
        } else {
            currentList.takeLast(maxMessages)
        }
    }

    override suspend fun clearHistory(): Unit = withContext(Dispatchers.IO) {
        _messages.value = emptyList()
        repository.clearConversation()
    }

    override suspend fun saveMemory(
        title: String,
        content: String,
        type: String,
        importance: Int
    ): MemoryEntity = withContext(Dispatchers.IO) {
        repository.saveMemory(
            title = title,
            content = content,
            type = type,
            importance = importance
        )
    }

    override suspend fun getMemories(type: String?): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (type != null) {
            repository.getMemoriesByType(type)
        } else {
            repository.getAllMemories()
        }
    }

    override suspend fun findRelevantMemories(query: String): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val trimmed = query.trim().lowercase()
        // 1. Direct search
        val direct = repository.searchMemories(trimmed)
        if (direct.isNotEmpty()) return@withContext direct

        // 2. Keyword/token search against all stored memories
        val allMemories = repository.getAllMemories()
        val queryTokens = trimmed.split(" ", "?", ".", ",", "!", ":", ";")
            .map { it.trim() }
            .filter { it.length > 2 && it !in STOP_WORDS }

        allMemories.filter { mem ->
            val memTokens = (mem.title + " " + mem.content).lowercase()
                .split(" ", "?", ".", ",", "!", ":", ";")
                .map { it.trim() }
            queryTokens.any { qToken -> memTokens.contains(qToken) || mem.content.lowercase().contains(qToken) || mem.title.lowercase().contains(qToken) }
        }
    }

    override suspend fun forgetMemory(keyword: String): Boolean = withContext(Dispatchers.IO) {
        repository.deleteMemoryByKeyword(keyword) > 0
    }

    override suspend fun saveNote(content: String): MemoryEntity = withContext(Dispatchers.IO) {
        repository.saveMemory(
            title = "Note",
            content = content,
            type = MemoryEntity.TYPE_NOTE,
            importance = 1
        )
    }

    override suspend fun getNotes(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        repository.getMemoriesByType(MemoryEntity.TYPE_NOTE)
    }

    override suspend fun createTask(
        title: String,
        description: String,
        dueTime: Long?
    ): TaskEntity = withContext(Dispatchers.IO) {
        repository.createTask(
            title = title,
            description = description,
            dueTime = dueTime
        )
    }

    override suspend fun getTasks(completed: Boolean?): List<TaskEntity> = withContext(Dispatchers.IO) {
        if (completed == false) {
            repository.getActiveTasks()
        } else {
            repository.getAllTasks()
        }
    }

    override suspend fun markTaskComplete(titleKeyword: String): Boolean = withContext(Dispatchers.IO) {
        repository.completeTask(titleKeyword)
    }

    companion object {
        private val STOP_WORDS = setOf(
            "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
            "is", "are", "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "can", "could", "should", "would", "my", "your", "the",
            "a", "an", "and", "or", "in", "on", "at", "to", "for", "of", "with", "about"
        )
    }
}


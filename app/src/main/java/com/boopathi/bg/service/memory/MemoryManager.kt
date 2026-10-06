package com.boopathi.bg.service.memory

import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.TaskEntity
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
    suspend fun getRecentContext(maxTurns: Int = 5): List<ChatMessage>
    suspend fun clearHistory()

    // Phase 2B: Persistent Memory
    suspend fun saveMemory(
        title: String,
        content: String,
        type: String = MemoryEntity.TYPE_FACT,
        importance: Int = 1
    ): MemoryEntity

    suspend fun getMemories(type: String? = null): List<MemoryEntity>
    suspend fun findRelevantMemories(query: String): List<MemoryEntity>
    suspend fun forgetMemory(keyword: String): Boolean

    // Notes
    suspend fun saveNote(content: String): MemoryEntity
    suspend fun getNotes(): List<MemoryEntity>

    // Tasks
    suspend fun createTask(
        title: String,
        description: String = "",
        dueTime: Long? = null
    ): TaskEntity

    suspend fun getTasks(completed: Boolean? = null): List<TaskEntity>
    suspend fun markTaskComplete(titleKeyword: String): Boolean
}

class InMemoryMemoryManager(
    private var currentOwner: OwnerProfile = OwnerProfile(name = "Boopathi", preferredLanguage = "en")
) : MemoryManager {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val messages = _messages.asStateFlow()

    private val memoryStore = mutableListOf<MemoryEntity>()
    private val taskStore = mutableListOf<TaskEntity>()
    private var idCounter = 1L

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

    override suspend fun getRecentContext(maxTurns: Int): List<ChatMessage> {
        val currentList = _messages.value
        val maxMessages = maxTurns * 2
        return if (currentList.size <= maxMessages) {
            currentList
        } else {
            currentList.takeLast(maxMessages)
        }
    }

    override suspend fun clearHistory() {
        _messages.value = emptyList()
    }

    override suspend fun saveMemory(
        title: String,
        content: String,
        type: String,
        importance: Int
    ): MemoryEntity {
        val entity = MemoryEntity(
            id = idCounter++,
            title = title,
            content = content,
            type = type,
            importance = importance,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        memoryStore.add(entity)
        return entity
    }

    override suspend fun getMemories(type: String?): List<MemoryEntity> {
        return if (type != null) {
            memoryStore.filter { it.type == type }
        } else {
            memoryStore.toList()
        }
    }

    override suspend fun findRelevantMemories(query: String): List<MemoryEntity> {
        val q = query.lowercase()
        return memoryStore.filter {
            it.title.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                q.contains(it.content.lowercase()) ||
                q.contains(it.title.lowercase())
        }
    }

    override suspend fun forgetMemory(keyword: String): Boolean {
        val q = keyword.lowercase()
        return memoryStore.removeAll {
            it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
        }
    }

    override suspend fun saveNote(content: String): MemoryEntity {
        return saveMemory(
            title = "Note",
            content = content,
            type = MemoryEntity.TYPE_NOTE,
            importance = 1
        )
    }

    override suspend fun getNotes(): List<MemoryEntity> {
        return getMemories(MemoryEntity.TYPE_NOTE)
    }

    override suspend fun createTask(
        title: String,
        description: String,
        dueTime: Long?
    ): TaskEntity {
        val entity = TaskEntity(
            id = idCounter++,
            title = title,
            description = description,
            dueTime = dueTime,
            completed = false,
            createdAt = System.currentTimeMillis()
        )
        taskStore.add(entity)
        return entity
    }

    override suspend fun getTasks(completed: Boolean?): List<TaskEntity> {
        return if (completed != null) {
            taskStore.filter { it.completed == completed }
        } else {
            taskStore.toList()
        }
    }

    override suspend fun markTaskComplete(titleKeyword: String): Boolean {
        val q = titleKeyword.lowercase()
        val index = taskStore.indexOfFirst {
            !it.completed && (it.title.lowercase().contains(q) || it.description.lowercase().contains(q))
        }
        return if (index != -1) {
            taskStore[index] = taskStore[index].copy(completed = true)
            true
        } else {
            false
        }
    }
}

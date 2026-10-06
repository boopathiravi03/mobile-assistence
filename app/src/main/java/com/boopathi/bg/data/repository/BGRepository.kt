package com.boopathi.bg.data.repository

import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.ConversationMessageEntity
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.local.entity.OwnerProfileEntity
import com.boopathi.bg.data.local.entity.TaskEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface BGRepository {
    suspend fun getOwnerProfile(): OwnerProfileEntity
    suspend fun updateOwnerProfile(name: String, preferredLanguage: String)
    fun getOwnerProfileFlow(): Flow<OwnerProfileEntity?>

    suspend fun saveMessage(sender: String, message: String, convId: String = "default"): Long
    suspend fun getAllMessages(convId: String = "default"): List<ConversationMessageEntity>
    suspend fun getRecentMessages(limit: Int, convId: String = "default"): List<ConversationMessageEntity>
    suspend fun clearConversation(convId: String = "default")

    suspend fun saveMemory(
        title: String,
        content: String,
        type: String = MemoryEntity.TYPE_FACT,
        importance: Int = 1
    ): MemoryEntity
    suspend fun getAllMemories(): List<MemoryEntity>
    suspend fun getMemoriesByType(type: String): List<MemoryEntity>
    suspend fun searchMemories(query: String): List<MemoryEntity>
    suspend fun deleteMemoryByKeyword(keyword: String): Int
    suspend fun deleteMemoryById(id: Long): Boolean

    suspend fun createTask(
        title: String,
        description: String = "",
        dueTime: Long? = null
    ): TaskEntity
    suspend fun getAllTasks(): List<TaskEntity>
    suspend fun getActiveTasks(): List<TaskEntity>
    suspend fun completeTask(keyword: String): Boolean
}

class RoomBGRepository(
    private val db: BGDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BGRepository {

    override suspend fun getOwnerProfile(): OwnerProfileEntity = withContext(ioDispatcher) {
        val existing = db.ownerProfileDao().getOwnerProfile()
        if (existing != null) {
            existing
        } else {
            val defaultProfile = OwnerProfileEntity(
                id = 1L,
                name = "Boopathi",
                preferredLanguage = "en",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            db.ownerProfileDao().insertOrUpdate(defaultProfile)
            defaultProfile
        }
    }

    override suspend fun updateOwnerProfile(name: String, preferredLanguage: String): Unit = withContext(ioDispatcher) {
        val current = getOwnerProfile()
        val updated = current.copy(
            name = name,
            preferredLanguage = preferredLanguage,
            updatedAt = System.currentTimeMillis()
        )
        db.ownerProfileDao().insertOrUpdate(updated)
    }

    override fun getOwnerProfileFlow(): Flow<OwnerProfileEntity?> =
        db.ownerProfileDao().getOwnerProfileFlow()

    override suspend fun saveMessage(sender: String, message: String, convId: String): Long = withContext(ioDispatcher) {
        val entity = ConversationMessageEntity(
            conversationId = convId,
            sender = sender,
            message = message,
            timestamp = System.currentTimeMillis()
        )
        db.conversationMessageDao().insertMessage(entity)
    }

    override suspend fun getAllMessages(convId: String): List<ConversationMessageEntity> = withContext(ioDispatcher) {
        db.conversationMessageDao().getAllMessages(convId)
    }

    override suspend fun getRecentMessages(limit: Int, convId: String): List<ConversationMessageEntity> = withContext(ioDispatcher) {
        // Returns messages ordered by timestamp ASC for display
        db.conversationMessageDao().getRecentMessages(limit, convId).reversed()
    }

    override suspend fun clearConversation(convId: String): Unit = withContext(ioDispatcher) {
        db.conversationMessageDao().clearHistory(convId)
    }

    override suspend fun saveMemory(
        title: String,
        content: String,
        type: String,
        importance: Int
    ): MemoryEntity = withContext(ioDispatcher) {
        val entity = MemoryEntity(
            title = title,
            content = content,
            type = type,
            importance = importance,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = db.memoryDao().insertMemory(entity)
        entity.copy(id = id)
    }

    override suspend fun getAllMemories(): List<MemoryEntity> = withContext(ioDispatcher) {
        db.memoryDao().getAllMemories()
    }

    override suspend fun getMemoriesByType(type: String): List<MemoryEntity> = withContext(ioDispatcher) {
        db.memoryDao().getMemoriesByType(type)
    }

    override suspend fun searchMemories(query: String): List<MemoryEntity> = withContext(ioDispatcher) {
        db.memoryDao().searchMemories(query)
    }

    override suspend fun deleteMemoryByKeyword(keyword: String): Int = withContext(ioDispatcher) {
        db.memoryDao().deleteByKeyword(keyword)
    }

    override suspend fun deleteMemoryById(id: Long): Boolean = withContext(ioDispatcher) {
        db.memoryDao().deleteById(id) > 0
    }

    override suspend fun createTask(
        title: String,
        description: String,
        dueTime: Long?
    ): TaskEntity = withContext(ioDispatcher) {
        val entity = TaskEntity(
            title = title,
            description = description,
            dueTime = dueTime,
            completed = false,
            createdAt = System.currentTimeMillis()
        )
        val id = db.taskDao().insertTask(entity)
        entity.copy(id = id)
    }

    override suspend fun getAllTasks(): List<TaskEntity> = withContext(ioDispatcher) {
        db.taskDao().getAllTasks()
    }

    override suspend fun getActiveTasks(): List<TaskEntity> = withContext(ioDispatcher) {
        db.taskDao().getTasksByStatus(completed = false)
    }

    override suspend fun completeTask(keyword: String): Boolean = withContext(ioDispatcher) {
        val task = db.taskDao().findTaskByKeyword(keyword)
        if (task != null) {
            db.taskDao().updateTask(task.copy(completed = true))
            true
        } else {
            false
        }
    }
}


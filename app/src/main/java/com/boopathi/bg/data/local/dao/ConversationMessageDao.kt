package com.boopathi.bg.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boopathi.bg.data.local.entity.ConversationMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessageEntity): Long

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesFlow(convId: String = "default"): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    suspend fun getAllMessages(convId: String = "default"): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :convId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int, convId: String = "default"): List<ConversationMessageEntity>

    @Query("DELETE FROM conversation_messages WHERE conversationId = :convId")
    suspend fun clearHistory(convId: String = "default"): Int

    @Query("SELECT COUNT(*) FROM conversation_messages")
    suspend fun getMessageCount(): Int
}


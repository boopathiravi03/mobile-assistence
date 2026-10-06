package com.boopathi.bg.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"])
    ]
)
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val conversationId: String = "default",
    val sender: String, // "USER" or "BG"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)


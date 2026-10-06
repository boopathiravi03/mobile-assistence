package com.boopathi.bg.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["type"])
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val content: String,
    val type: String = TYPE_FACT, // FACT, PREFERENCE, NOTE, REMINDER
    val importance: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_FACT = "FACT"
        const val TYPE_PREFERENCE = "PREFERENCE"
        const val TYPE_NOTE = "NOTE"
        const val TYPE_REMINDER = "REMINDER"
    }
}


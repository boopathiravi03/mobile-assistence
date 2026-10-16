package com.boopathi.bg.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["completed"]),
        Index(value = ["dueTime"]),
        Index(value = ["status", "dueTime"])
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val description: String = "",
    val dueTime: Long? = null,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'task'") val taskType: String = TaskType.TASK,
    @ColumnInfo(defaultValue = "'pending'") val status: String = TaskStatus.PENDING,
    val timezoneId: String? = null,
    val recurrenceRule: String? = null,
    val nextOccurrenceAt: Long? = null,
    val snoozedUntil: Long? = null,
    val lastOccurrenceAt: Long? = null,
    val notificationId: Int? = null,
    val expiresAt: Long? = null,
    val failureReason: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TASK = "task"
        const val TYPE_REMINDER = "reminder"
        const val TYPE_RECURRING_REMINDER = "recurring_reminder"
        const val TYPE_WORKFLOW_REQUEST = "workflow_request"
    }
}

object TaskType {
    const val TASK = TaskEntity.TYPE_TASK
    const val REMINDER = TaskEntity.TYPE_REMINDER
    const val RECURRING_REMINDER = TaskEntity.TYPE_RECURRING_REMINDER
    const val WORKFLOW_REQUEST = TaskEntity.TYPE_WORKFLOW_REQUEST
}

object TaskStatus {
    const val PENDING = "pending"
    const val SCHEDULED = "scheduled"
    const val DELIVERED = "delivered"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}


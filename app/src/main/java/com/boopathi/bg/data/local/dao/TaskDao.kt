package com.boopathi.bg.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.boopathi.bg.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity): Int

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE completed = :completed ORDER BY createdAt DESC")
    suspend fun getTasksByStatus(completed: Boolean): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE completed = :completed ORDER BY createdAt DESC")
    fun getTasksByStatusFlow(completed: Boolean): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :keyword || '%' OR description LIKE '%' || :keyword || '%' LIMIT 1")
    suspend fun findTaskByKeyword(keyword: String): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE completed = 0")
    suspend fun getPendingTaskCount(): Int

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE taskType IN ('reminder', 'recurring_reminder', 'workflow_request') ORDER BY COALESCE(nextOccurrenceAt, dueTime) ASC")
    fun observeScheduledTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskType IN ('reminder', 'recurring_reminder', 'workflow_request') AND status IN ('pending', 'scheduled', 'delivered', 'failed') ORDER BY COALESCE(nextOccurrenceAt, dueTime) ASC")
    suspend fun getReminderTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE taskType IN ('reminder', 'recurring_reminder', 'workflow_request') AND status = 'scheduled'")
    suspend fun getScheduledTasks(): List<TaskEntity>

    @Query("UPDATE tasks SET lastOccurrenceAt = :occurrenceAt, snoozedUntil = NULL, status = :nextStatus, completed = :completed, dueTime = COALESCE(:nextOccurrenceAt, dueTime), nextOccurrenceAt = :nextOccurrenceAt, notificationId = :notificationId, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :id AND status = 'scheduled' AND nextOccurrenceAt = :occurrenceAt AND (lastOccurrenceAt IS NULL OR lastOccurrenceAt != :occurrenceAt)")
    suspend fun claimOccurrenceWithResult(
        id: Long,
        occurrenceAt: Long,
        nextStatus: String,
        completed: Boolean,
        nextOccurrenceAt: Long?,
        notificationId: Int,
        failureReason: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE tasks SET snoozedUntil = :snoozedUntil, status = 'scheduled', failureReason = NULL, updatedAt = :updatedAt WHERE id = :id AND status IN ('scheduled', 'delivered') AND completed = 0")
    suspend fun setSnooze(id: Long, snoozedUntil: Long, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE tasks SET snoozedUntil = NULL, status = :status, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :id AND status = 'scheduled' AND snoozedUntil = :snoozedUntil")
    suspend fun claimSnooze(id: Long, snoozedUntil: Long, status: String, failureReason: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE tasks SET failureReason = :reason, updatedAt = :updatedAt WHERE id = :id AND status = 'scheduled'")
    suspend fun setFailureReason(id: Long, reason: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE tasks SET status = :status, completed = :completed, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :id AND status IN ('pending', 'scheduled', 'delivered', 'failed')")
    suspend fun updateLifecycle(id: Long, status: String, completed: Boolean, failureReason: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM tasks WHERE completed = 1 OR status IN ('completed', 'cancelled')")
    suspend fun clearCompleted(): Int
}


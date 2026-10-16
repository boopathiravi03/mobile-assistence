package com.boopathi.bg.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity

@Dao
interface WorkflowHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: WorkflowHistoryEntity): Long

    @Update
    suspend fun update(history: WorkflowHistoryEntity): Int

    @Query("SELECT * FROM workflow_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WorkflowHistoryEntity?

    @Query("SELECT * FROM workflow_history WHERE workflowId = :workflowId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByWorkflowId(workflowId: String): WorkflowHistoryEntity?

    @Query("SELECT * FROM workflow_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<WorkflowHistoryEntity>

    @Query("DELETE FROM workflow_history WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM workflow_history")
    suspend fun clearAll(): Int

    /**
     * Marks non‑terminal records as INTERRUPTED when the process restarts.
     * Used during app startup to flag unfinished workflows.
     */
    @Query("UPDATE workflow_history SET status = 'INTERRUPTED' WHERE status IN ('EXECUTING','WAITING_FOR_CONFIRMATION','WAITING_FOR_CLARIFICATION')")
    suspend fun markInterruptedActive(): Int
}


package com.boopathi.bg.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Workflow History (Phase 6D).
 */
@Dao
interface WorkflowHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: WorkflowHistoryEntity): Long

    @Query("SELECT * FROM workflow_history WHERE workflowId = :id")
    suspend fun getWorkflowById(id: String): WorkflowHistoryEntity?

    @Query("SELECT * FROM workflow_history ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentWorkflows(limit: Int = 100): List<WorkflowHistoryEntity>

    @Query("SELECT * FROM workflow_history ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentWorkflowsFlow(limit: Int = 100): Flow<List<WorkflowHistoryEntity>>

    @Query("SELECT * FROM workflow_history WHERE status = :status ORDER BY updatedAt DESC")
    suspend fun getWorkflowsByStatus(status: String): List<WorkflowHistoryEntity>

    @Query("SELECT * FROM workflow_history WHERE status = 'INTERRUPTED' AND isEligibleForResume = 1 AND expiresAt > :currentTime ORDER BY updatedAt DESC")
    suspend fun getResumableWorkflows(currentTime: Long = System.currentTimeMillis()): List<WorkflowHistoryEntity>

    @Query("SELECT * FROM workflow_history ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestWorkflow(): WorkflowHistoryEntity?

    @Query("UPDATE workflow_history SET status = 'INTERRUPTED', isEligibleForResume = 1, updatedAt = :currentTime WHERE status IN ('EXECUTING', 'VALIDATING', 'VERIFYING')")
    suspend fun markActiveWorkflowsAsInterrupted(currentTime: Long = System.currentTimeMillis()): Int

    @Query("UPDATE workflow_history SET status = 'EXPIRED', isEligibleForResume = 0, updatedAt = :currentTime WHERE expiresAt < :currentTime AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')")
    suspend fun markExpiredWorkflows(currentTime: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM workflow_history WHERE workflowId = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM workflow_history WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredRecords(currentTime: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM workflow_history WHERE workflowId NOT IN (SELECT workflowId FROM workflow_history ORDER BY updatedAt DESC LIMIT :keepLimit)")
    suspend fun trimOldHistory(keepLimit: Int = 100): Int

    @Query("DELETE FROM workflow_history")
    suspend fun clearAllWorkflowHistory(): Int

    @Query("SELECT COUNT(*) FROM workflow_history")
    suspend fun getCount(): Int
}


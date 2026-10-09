package com.boopathi.bg.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.WorkflowType

/**
 * Persisted record of a cross-app workflow execution (Phase 6D).
 *
 * Stores non-sensitive audit metadata and checkpoints for safe resumption.
 * Explicitly excludes:
 * - Raw AccessibilityNodeInfo references
 * - Entire UI trees
 * - Passwords, PINs, OTPs, CVVs, credentials
 * - Raw notification dumps
 * - Transient execution payloads
 */
@Entity(
    tableName = "workflow_history",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class WorkflowHistoryEntity(
    @PrimaryKey
    val workflowId: String,
    val goalDescription: String,
    val workflowType: String,
    val status: String,
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours retention for resumption
    val lastSafeCheckpointJson: String? = null,
    val failureReason: String? = null,
    val isEligibleForResume: Boolean = false,
    val consequentialActionAttempted: Boolean = false,
    val consequentialOutcomeUncertain: Boolean = false,
    val stepsJson: String = "" // Serialized minimal step blueprints for safe revalidation on resume
) {
    val typedStatus: CrossAppWorkflowStatus
        get() = try {
            CrossAppWorkflowStatus.valueOf(status)
        } catch (e: Exception) {
            CrossAppWorkflowStatus.FAILED
        }

    val typedWorkflowType: WorkflowType
        get() = try {
            WorkflowType.valueOf(workflowType)
        } catch (e: Exception) {
            WorkflowType.INSPECT_ACROSS_APPS
        }
}


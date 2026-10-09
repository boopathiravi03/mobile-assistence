package com.boopathi.bg.service.workflow

/**
 * Roles that an app can play in a cross-app workflow.
 */
enum class WorkflowAppRole {
    SOURCE,
    INTERMEDIATE,
    DESTINATION
}

/**
 * Lifecycle states of a CrossAppWorkflow.
 */
enum class CrossAppWorkflowStatus {
    DRAFT,
    VALIDATING,
    READY,
    EXECUTING,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_CLARIFICATION,
    VERIFYING,
    SUCCESS,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    EXPIRED,
    UNVERIFIED
}

/**
 * High-level supported cross-app workflow types in Phase 6C.
 */
enum class WorkflowType {
    INSPECT_ACROSS_APPS,
    TRANSFER_USER_PROVIDED_TEXT,
    PREPARE_CROSS_APP_MESSAGE,
    OPEN_AND_INSPECT_DESTINATION
}

/**
 * An individual step within a CrossAppWorkflow.
 *
 * Holds no live AccessibilityNodeInfo or raw UI trees.
 */
data class CrossAppWorkflowStep(
    val id: String,
    val appRole: WorkflowAppRole,
    val targetApp: String,
    val expectedPackage: String?,
    val actionReference: String,
    val parameters: Map<String, String> = emptyMap(),
    val expectedOutcome: String,
    val requiresConfirmation: Boolean = false,
    val isHandoff: Boolean = false
)

/**
 * Representation of a bounded Cross-App Workflow.
 */
data class CrossAppWorkflow(
    val workflowId: String,
    val type: WorkflowType,
    val goalDescription: String,
    val steps: List<CrossAppWorkflowStep>,
    val currentStepIndex: Int = 0,
    val status: CrossAppWorkflowStatus = CrossAppWorkflowStatus.DRAFT,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val failureReason: String? = null
) {
    val totalSteps: Int get() = steps.size
    val totalHandoffs: Int get() = steps.count { it.isHandoff }
    val isComplete: Boolean get() = status == CrossAppWorkflowStatus.SUCCESS ||
            status == CrossAppWorkflowStatus.FAILED ||
            status == CrossAppWorkflowStatus.CANCELLED ||
            status == CrossAppWorkflowStatus.EXPIRED
    val isTerminal: Boolean get() = isComplete
    val isResumable: Boolean get() = status == CrossAppWorkflowStatus.INTERRUPTED
}


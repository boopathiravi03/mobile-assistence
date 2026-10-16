package com.boopathi.bg.service.agent

import com.boopathi.bg.service.security.MessageDispatchAuthorization
import java.util.UUID

/**
 * Strongly typed representation of a user goal decomposed into bounded sequential steps.
 */
data class AgentTask(
    val taskId: String = UUID.randomUUID().toString(),
    val originalCommand: String,
    val normalizedGoal: String,
    val steps: MutableList<AgentStep> = mutableListOf(),
    var currentStepIndex: Int = 0,
    var state: AgentTaskState = AgentTaskState.IDLE,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var failureReason: String? = null,
    var confirmationRequest: ConfirmationRequest? = null,
    var draft: com.boopathi.bg.service.messaging.MessageDraft? = null,
    var confirmationCreatedAt: Long = System.currentTimeMillis(),
    var sendExecuted: Boolean = false,
    var confirmationFingerprint: String? = draft?.let { messageDraft ->
        confirmationRequest?.let { MessageDispatchAuthorization.confirmationFingerprint(taskId, messageDraft, it) }
    },
    var recoveryAttemptCount: Int = 0,
    var lastRecoveryReason: String? = null
) {
    val totalSteps: Int get() = steps.size
    val currentStep: AgentStep?
        get() = if (currentStepIndex in steps.indices) steps[currentStepIndex] else null
    val isComplete: Boolean
        get() = state == AgentTaskState.SUCCESS || state == AgentTaskState.FAILED ||
                state == AgentTaskState.CANCELLED || state == AgentTaskState.BLOCKED ||
                state == AgentTaskState.TIMEOUT
}


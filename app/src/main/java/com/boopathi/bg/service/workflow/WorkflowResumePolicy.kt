package com.boopathi.bg.service.workflow

import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.security.OwnerAuthGate

/**
 * Result of validating an interrupted workflow for safe resumption.
 */
data class WorkflowResumeValidationResult(
    val isEligible: Boolean,
    val revalidatedWorkflow: CrossAppWorkflow? = null,
    val rejectionReason: String? = null,
    val requiresFreshAuth: Boolean = false,
    val requiresUserClarification: Boolean = false
)

/**
 * Enforces strict safety rules before any interrupted task can be resumed (Phase 6D):
 * 1. Workflow exists and has not expired (< 24h).
 * 2. Status is INTERRUPTED (Terminal tasks SUCCESS, FAILED, CANCELLED, EXPIRED cannot be resumed).
 * 3. Original goal and destination are clear and supported.
 * 4. Required apps are supported and installed.
 * 5. Consequential operations (like message dispatch) are NEVER automatically replayed.
 * 6. If consequential outcome was uncertain or attempted, resume is prohibited or marked UNVERIFIED.
 * 7. Current foreground app matches expected checkpoint or step requirement.
 * 8. Stale confirmations are invalidated and require fresh explicit user confirmation and auth.
 */
class WorkflowResumePolicy(
    private val validator: CrossAppWorkflowValidator = CrossAppWorkflowValidator()
) {

    fun validateResumeEligibility(
        historyEntity: WorkflowHistoryEntity,
        currentForegroundPackage: String? = null,
        currentTime: Long = System.currentTimeMillis()
    ): WorkflowResumeValidationResult {
        // 1. Expiration check
        if (currentTime >= historyEntity.expiresAt) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Workflow has expired and cannot be resumed."
            )
        }

        // 2. Status eligibility check: Only INTERRUPTED can resume
        if (historyEntity.typedStatus != CrossAppWorkflowStatus.INTERRUPTED) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Workflow in status '${historyEntity.status}' is not eligible for resumption."
            )
        }

        if (!historyEntity.isEligibleForResume) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Workflow is marked ineligible for resumption."
            )
        }

        // 3. Consequential duplicate prevention: Never replay attempted or uncertain sends
        if (historyEntity.consequentialActionAttempted || historyEntity.consequentialOutcomeUncertain) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Workflow attempted a consequential action with uncertain outcome. Replay blocked to prevent duplicate action."
            )
        }

        // 4. Deserialize steps safely
        val steps = WorkflowStepSerializer.deserializeSteps(historyEntity.stepsJson)
        if (steps.isEmpty()) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Workflow step blueprint is empty or corrupted."
            )
        }

        // 5. Check remaining steps bounds
        val nextStepIndex = historyEntity.currentStepIndex
        if (nextStepIndex >= steps.size) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "All workflow steps have already concluded."
            )
        }

        val nextStep = steps[nextStepIndex]

        // 6. Prohibit automatic resumption of consequential actions
        if (nextStep.actionReference.equals("SEND_MESSAGE", ignoreCase = true)) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Next step is consequential message dispatch. Cannot be automatically resumed."
            )
        }

        // 7. App supported check
        val targetPkg = nextStep.expectedPackage ?: validator.resolvePackage(nextStep.targetApp)
        if (targetPkg == null || targetPkg !in CrossAppWorkflowValidator.SUPPORTED_PACKAGES) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Target app '${nextStep.targetApp}' (${targetPkg ?: "unknown"}) is not supported."
            )
        }

        // 8. Reconstruct workflow object
        val workflowToResume = CrossAppWorkflow(
            workflowId = historyEntity.workflowId,
            type = historyEntity.typedWorkflowType,
            goalDescription = historyEntity.goalDescription,
            steps = steps,
            currentStepIndex = nextStepIndex,
            status = CrossAppWorkflowStatus.READY,
            createdAtMillis = historyEntity.createdAt
        )

        // 9. Full plan revalidation against safety policy
        val planValidation = validator.validateWorkflow(workflowToResume)
        if (!planValidation.isValid) {
            return WorkflowResumeValidationResult(
                isEligible = false,
                rejectionReason = "Plan failed safety revalidation: ${planValidation.rejectionReason}"
            )
        }

        // 10. Foreground app check if specified
        if (currentForegroundPackage != null && currentForegroundPackage != targetPkg) {
            // If the next step is an explicit OPEN_APP handoff, we allow it because execution will launch it;
            // Otherwise, wrong foreground app blocks immediate execution
            if (nextStep.actionReference != "OPEN_APP") {
                return WorkflowResumeValidationResult(
                    isEligible = false,
                    rejectionReason = "Current foreground app ($currentForegroundPackage) does not match expected app ($targetPkg)."
                )
            }
        }

        return WorkflowResumeValidationResult(
            isEligible = true,
            revalidatedWorkflow = workflowToResume,
            requiresFreshAuth = nextStep.requiresConfirmation
        )
    }
}


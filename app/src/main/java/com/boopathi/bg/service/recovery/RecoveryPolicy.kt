package com.boopathi.bg.service.recovery

import com.boopathi.bg.service.agent.AppAction

/**
 * Strict bounded recovery policy limits.
 * Enforces maximum retry attempts, execution step limits, and prevents automatic retry
 * of consequential, irreversible, or sensitive actions.
 */
object RecoveryPolicy {
    const val MAX_RECOVERY_ATTEMPTS_PER_STEP: Int = 2
    const val MAX_TOTAL_RECOVERY_ATTEMPTS_PER_TASK: Int = 4
    const val MAX_RECOVERY_PLAN_STEPS: Int = 3
    const val MAX_RECOVERY_DURATION_MS: Long = 10000L

    /**
     * Actions that must NEVER be automatically recovered or retried without explicit confirmation.
     */
    fun isActionRecoverable(action: AppAction): Boolean {
        return when (action) {
            is AppAction.SendMessage -> false // Consequential dispatch action: strictly prohibited from auto-retry
            is AppAction.OpenApp -> true
            is AppAction.WaitForWindow -> true
            is AppAction.InspectUi -> true
            is AppAction.Tap -> true
            is AppAction.TypeText -> true
            is AppAction.Scroll -> true
            is AppAction.Back -> true
            is AppAction.Home -> true
        }
    }

    /**
     * Checks if a recovery attempt can proceed given the current step-level, task-level,
     * and duration constraints.
     */
    fun canAttemptRecovery(
        stepRecoveryCount: Int,
        totalRecoveryCount: Int,
        recoveryStartTimeMs: Long,
        action: AppAction? = null
    ): RecoveryCheckResult {
        if (action != null && !isActionRecoverable(action)) {
            return RecoveryCheckResult(
                allowed = false,
                reason = "Action '${action.type}' is consequential and cannot be automatically retried."
            )
        }

        if (stepRecoveryCount >= MAX_RECOVERY_ATTEMPTS_PER_STEP) {
            return RecoveryCheckResult(
                allowed = false,
                reason = "Step recovery limit reached ($stepRecoveryCount/$MAX_RECOVERY_ATTEMPTS_PER_STEP attempts)."
            )
        }

        if (totalRecoveryCount >= MAX_TOTAL_RECOVERY_ATTEMPTS_PER_TASK) {
            return RecoveryCheckResult(
                allowed = false,
                reason = "Total task recovery limit reached ($totalRecoveryCount/$MAX_TOTAL_RECOVERY_ATTEMPTS_PER_TASK attempts)."
            )
        }

        val elapsed = System.currentTimeMillis() - recoveryStartTimeMs
        if (elapsed > MAX_RECOVERY_DURATION_MS) {
            return RecoveryCheckResult(
                allowed = false,
                reason = "Recovery duration timed out (${elapsed}ms > ${MAX_RECOVERY_DURATION_MS}ms limit)."
            )
        }

        return RecoveryCheckResult(allowed = true)
    }
}

data class RecoveryCheckResult(
    val allowed: Boolean,
    val reason: String? = null
)


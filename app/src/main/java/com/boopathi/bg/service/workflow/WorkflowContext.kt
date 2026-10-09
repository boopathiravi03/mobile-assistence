package com.boopathi.bg.service.workflow

/**
 * Execution bounds and constraints for Cross-App Workflows (Phase 6C).
 */
object WorkflowLimits {
    const val MAX_WORKFLOW_STEPS = 10
    const val MIN_WORKFLOW_STEPS = 1
    const val MAX_APP_HANDOFFS = 4
    const val MAX_RECOVERY_ATTEMPTS_PER_STEP = 2
    const val MAX_RECOVERY_ATTEMPTS_PER_TASK = 4
    const val MAX_RECOVERY_PLAN_STEPS = 3
    const val MAX_WORKFLOW_DURATION_MS = 60_000L // 60 seconds bounded workflow timeout
    const val CONTEXT_EXPIRATION_MS = 120_000L   // 2 minutes validity for prepared context
}

/**
 * State container for execution context across app transitions.
 *
 * Explicitly excludes:
 * - Passwords, OTPs, PINs, CVVs, credentials
 * - Raw AccessibilityNodeInfo references
 * - Entire unredacted notification histories or private app trees
 */
data class WorkflowContext(
    val workflowId: String,
    val authorizedGoal: String,
    var currentStepIndex: Int = 0,
    var verifiedSourcePackage: String? = null,
    var verifiedDestinationPackage: String? = null,
    private var _userProvidedText: String? = null,
    var expectedOutcome: String = "",
    var lastVerificationStatus: String = "INITIALIZED",
    var isCancelled: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    var lastUpdatedMillis: Long = System.currentTimeMillis()
) {
    /**
     * Non-sensitive user-provided text access.
     */
    val userProvidedText: String? get() = _userProvidedText

    /**
     * Safely updates user-provided text, rejecting sensitive credentials.
     */
    fun setUserProvidedText(text: String?) {
        if (text != null && containsSensitiveData(text)) {
            throw IllegalArgumentException("Sensitive credentials or secrets cannot be stored in WorkflowContext.")
        }
        _userProvidedText = text
        lastUpdatedMillis = System.currentTimeMillis()
    }

    /**
     * Clears transient sensitive or user data upon workflow completion/cancellation.
     */
    fun clearTransientData() {
        _userProvidedText = null
        verifiedSourcePackage = null
        verifiedDestinationPackage = null
        lastUpdatedMillis = System.currentTimeMillis()
    }

    /**
     * Checks if context is expired or stale.
     */
    fun isExpired(timeoutMs: Long = WorkflowLimits.CONTEXT_EXPIRATION_MS): Boolean {
        return (System.currentTimeMillis() - lastUpdatedMillis) > timeoutMs
    }

    companion object {
        private val SENSITIVE_PATTERNS = listOf(
            Regex("(?i)(password|passcode|pin|otp|cvv|cvc|secret|token|pwd)"),
            Regex("(?i)(private[ _]?key|bearer)"),
            Regex("^\\d{4,6}$"),                                      // e.g. 1234, 894312 (standalone PIN/OTP)
            Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"), // Credit card format
            Regex("\\b\\d{3}[ -]?\\d{2}[ -]?\\d{4}\\b")             // SSN format
        )

        fun containsSensitiveData(text: String): Boolean {
            return SENSITIVE_PATTERNS.any { it.containsMatchIn(text.trim()) }
        }
    }
}


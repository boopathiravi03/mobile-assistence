package com.boopathi.bg.service.workflow

import com.boopathi.bg.service.planner.AgentPlanRisk

/**
 * Result of validating a cross-app workflow plan against local safety and capability constraints.
 */
data class WorkflowValidationResult(
    val isValid: Boolean,
    val validatedWorkflow: CrossAppWorkflow? = null,
    val rejectionReason: String? = null,
    val calculatedRisk: AgentPlanRisk = AgentPlanRisk.LOW
)

/**
 * Validates CrossAppWorkflow plans before any execution starts.
 *
 * Enforces:
 * - Step count bounds (1..10)
 * - App handoff count bounds (max 4)
 * - Supported package / app validation
 * - Prohibition of arbitrary shell commands or code
 * - Prohibition of coordinate clicks
 * - Rejection of credential / PIN / OTP transfers
 * - Non-consequential text transfer (no auto-send / auto-submit)
 */
class CrossAppWorkflowValidator {

    companion object {
        val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.google.android.youtube",
            "com.android.settings",
            "com.google.android.apps.messaging",
            "com.google.android.calculator",
            "com.android.camera",
            "com.google.android.apps.maps",
            "com.google.android.gm",
            "com.android.chrome",
            "com.google.android.keep" // Notes app support
        )

        val SUPPORTED_APP_NAMES = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "settings" to "com.android.settings",
            "messages" to "com.google.android.apps.messaging",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "notes" to "com.google.android.keep",
            "keep" to "com.google.android.keep"
        )

        private val FORBIDDEN_SHELL_KEYWORDS = listOf(
            "su", "sh", "bash", "root", "chmod", "rm -rf", "exec", "am start", "input tap"
        )

        private val FORBIDDEN_COORDINATE_KEYWORDS = listOf(
            "coordinate", "tap(", "click(", "x,y", "point", "pixels"
        )

        private val ALLOWED_ACTIONS = setOf("OPEN_APP", "INSPECT_SCREEN", "TRANSFER_TEXT", "PREPARE_MESSAGE")
        private const val MAX_TEXT_LENGTH = 2_000
        private const val MAX_PARAMETER_COUNT = 12
    }

    fun resolvePackage(appNameOrPkg: String): String? {
        val clean = appNameOrPkg.trim().lowercase()
        if (SUPPORTED_PACKAGES.contains(clean)) return clean
        return SUPPORTED_APP_NAMES[clean]
    }

    fun validateWorkflow(workflow: CrossAppWorkflow): WorkflowValidationResult {
        if (workflow.workflowId.isBlank() || workflow.workflowId.length > 100 ||
            workflow.goalDescription.length > MAX_TEXT_LENGTH ||
            workflow.steps.any { step ->
                step.id.isBlank() || step.id.length > 100 || step.targetApp.length > 100 ||
                    step.expectedOutcome.length > 500 || step.actionReference.length > 60 ||
                    step.parameters.size > MAX_PARAMETER_COUNT ||
                    step.parameters.any { (key, value) -> key.length > 80 || value.length > MAX_TEXT_LENGTH }
            }) {
            return WorkflowValidationResult(false, rejectionReason = "Workflow contains malformed or oversized data.")
        }
        // 1. Bounds checks
        if (workflow.steps.isEmpty()) {
            return WorkflowValidationResult(
                isValid = false,
                rejectionReason = "Workflow is empty. At least one step is required."
            )
        }

        if (workflow.steps.size > WorkflowLimits.MAX_WORKFLOW_STEPS) {
            return WorkflowValidationResult(
                isValid = false,
                rejectionReason = "Workflow exceeds maximum allowed steps (${WorkflowLimits.MAX_WORKFLOW_STEPS})."
            )
        }

        if (workflow.totalHandoffs > WorkflowLimits.MAX_APP_HANDOFFS) {
            return WorkflowValidationResult(
                isValid = false,
                rejectionReason = "Workflow exceeds maximum app handoffs (${WorkflowLimits.MAX_APP_HANDOFFS})."
            )
        }

        var overallRisk = AgentPlanRisk.LOW
        var requiresConfirmation = false

        for ((index, step) in workflow.steps.withIndex()) {
            val stepNumber = index + 1

            // 2. Validate App / Package
            val resolvedPkg = step.expectedPackage ?: resolvePackage(step.targetApp)
            if (resolvedPkg == null) {
                return WorkflowValidationResult(
                    isValid = false,
                    rejectionReason = "Step $stepNumber targets unsupported app or package: '${step.targetApp}'."
                )
            }
            if (step.expectedPackage != null && step.expectedPackage != resolvePackage(step.targetApp)) {
                return WorkflowValidationResult(false, rejectionReason = "Step $stepNumber has an unsupported app target.")
            }

            // 3. Prohibit shell execution & coordinates
            val actionRef = step.actionReference.lowercase()
            if (FORBIDDEN_SHELL_KEYWORDS.any { actionRef.contains(it) } ||
                step.parameters.values.any { valLower -> FORBIDDEN_SHELL_KEYWORDS.any { valLower.lowercase().contains(it) } }
            ) {
                return WorkflowValidationResult(
                    isValid = false,
                    rejectionReason = "Step $stepNumber contains forbidden shell/system command reference."
                )
            }

            if (FORBIDDEN_COORDINATE_KEYWORDS.any { actionRef.contains(it) }) {
                return WorkflowValidationResult(
                    isValid = false,
                    rejectionReason = "Step $stepNumber attempts coordinate-based screen interaction."
                )
            }

            if (step.actionReference.uppercase() !in ALLOWED_ACTIONS) {
                return WorkflowValidationResult(false, rejectionReason = "Step $stepNumber contains an unsupported action.")
            }

            // 4. Prohibit credentials, OTP, and passwords in parameters
            for ((key, value) in step.parameters) {
                if (WorkflowContext.containsSensitiveData(value) || WorkflowContext.containsSensitiveData(key)) {
                    return WorkflowValidationResult(
                        isValid = false,
                        rejectionReason = "Step $stepNumber contains sensitive credential or PIN/OTP data."
                    )
                }
            }

            // 5. Check risk level for messaging preparation or actions requiring confirmation
            if (step.actionReference.equals("SEND_MESSAGE", ignoreCase = true)) {
                overallRisk = AgentPlanRisk.HIGH
                requiresConfirmation = true
            } else if (step.actionReference.equals("PREPARE_MESSAGE", ignoreCase = true) ||
                step.actionReference.equals("TRANSFER_TEXT", ignoreCase = true)
            ) {
                if (overallRisk != AgentPlanRisk.HIGH) {
                    overallRisk = AgentPlanRisk.MEDIUM
                }
            }

            if (step.requiresConfirmation) {
                requiresConfirmation = true
            }
        }

        val validatedWorkflow = workflow.copy(
            status = if (requiresConfirmation) CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION else CrossAppWorkflowStatus.VALIDATING
        )

        return WorkflowValidationResult(
            isValid = true,
            validatedWorkflow = validatedWorkflow,
            calculatedRisk = overallRisk
        )
    }
}


package com.boopathi.bg.service.planner

data class PlanValidationResult(
    val isValid: Boolean,
    val validatedPlan: AgentPlan?,
    val rejectionReason: String? = null
)

class AgentPlanValidator {

    companion object {
        const val MAX_STEPS = 10
        const val MIN_STEPS = 1
        private const val MAX_GOAL_LENGTH = 2_000
        private const val MAX_FIELD_LENGTH = 1_000
        private const val MAX_PARAMETERS = 16

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.google.android.youtube",
            "com.android.settings",
            "com.google.android.apps.messaging",
            "com.google.android.calculator",
            "com.android.camera",
            "com.google.android.apps.maps",
            "com.google.android.gm",
            "com.android.chrome"
        )

        private val COMMON_APP_NAMES = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "settings" to "com.android.settings",
            "messages" to "com.google.android.apps.messaging",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome"
        )

        private val SENSITIVE_KEYWORDS = listOf(
            "password", "passcode", "pin", "otp", "cvv", "credit card",
            "debit card", "bank account", "social security", "ssn",
            "fingerprint", "face id", "biometric"
        )

        private val FINANCIAL_OR_DESTRUCTIVE_KEYWORDS = listOf(
            "pay", "purchase", "buy now", "send money", "transfer money",
            "delete account", "erase all", "factory reset", "uninstall"
        )

        private val MEDIUM_RISK_KEYWORDS = listOf(
            "send", "post", "delete", "remove", "submit", "apply", "share"
        )
    }

    fun validatePlan(plan: AgentPlan): PlanValidationResult {
        if (plan.goal.rawInput.length > MAX_GOAL_LENGTH || plan.goal.normalizedGoal.length > MAX_GOAL_LENGTH ||
            plan.goal.entities.size > MAX_PARAMETERS || plan.goal.entities.any { (key, value) -> key.length > 80 || value.length > MAX_FIELD_LENGTH }) {
            return PlanValidationResult(false, null, "Plan contains oversized goal data.")
        }

        // Step count bounds check: 1..10
        if (plan.steps.size < MIN_STEPS) {
            return PlanValidationResult(
                isValid = false,
                validatedPlan = null,
                rejectionReason = "Plan is empty. At least one executable step is required."
            )
        }

        if (plan.steps.size > MAX_STEPS) {
            return PlanValidationResult(
                isValid = false,
                validatedPlan = null,
                rejectionReason = "That task is too large for one operation. I need to break it into smaller steps."
            )
        }

        if (plan.steps.map { it.stepId }.any(String::isBlank) || plan.steps.map { it.stepId }.distinct().size != plan.steps.size ||
            plan.steps.any { step ->
                step.stepId.length > 80 || step.description.length > 400 || (step.target?.length ?: 0) > MAX_FIELD_LENGTH ||
                    step.parameters.size > MAX_PARAMETERS || step.parameters.any { (key, value) -> key.length > 80 || value.length > MAX_FIELD_LENGTH }
            }) {
            return PlanValidationResult(false, null, "Plan contains malformed or oversized step data.")
        }

        var overallRisk = AgentPlanRisk.LOW
        var requiresConfirmationOverall = false
        val validatedSteps = mutableListOf<AgentPlanStep>()

        for ((index, step) in plan.steps.withIndex()) {
            val stepNumber = index + 1
            val stepEvaluation = evaluateStepSafety(step, stepNumber)
            if (!stepEvaluation.isAllowed) {
                return PlanValidationResult(
                    isValid = false,
                    validatedPlan = null,
                    rejectionReason = stepEvaluation.rejectionReason ?: "Step $stepNumber violates safety policy."
                )
            }

            if (stepEvaluation.calculatedRisk == AgentPlanRisk.HIGH) {
                overallRisk = AgentPlanRisk.HIGH
            } else if (stepEvaluation.calculatedRisk == AgentPlanRisk.MEDIUM && overallRisk != AgentPlanRisk.HIGH) {
                overallRisk = AgentPlanRisk.MEDIUM
            }

            if (stepEvaluation.requiresConfirmation) {
                requiresConfirmationOverall = true
            }

            validatedSteps.add(
                step.copy(
                    risk = stepEvaluation.calculatedRisk,
                    requiresConfirmation = stepEvaluation.requiresConfirmation
                )
            )
        }

        val validatedPlan = plan.copy(
            steps = validatedSteps,
            risk = overallRisk,
            requiresConfirmation = requiresConfirmationOverall,
            status = if (requiresConfirmationOverall) AgentPlanStatus.NEEDS_CONFIRMATION else AgentPlanStatus.VALIDATED
        )

        return PlanValidationResult(
            isValid = true,
            validatedPlan = validatedPlan,
            rejectionReason = null
        )
    }

    private data class StepSafetyResult(
        val isAllowed: Boolean,
        val calculatedRisk: AgentPlanRisk,
        val requiresConfirmation: Boolean,
        val rejectionReason: String? = null
    )

    private fun evaluateStepSafety(step: AgentPlanStep, stepNumber: Int): StepSafetyResult {
        val target = step.target?.trim().orEmpty()
        val textParam = step.parameters["text"]?.trim().orEmpty()
        val combinedContent = "$target $textParam ${step.parameters.values.joinToString(" ")}".lowercase()

        if (step.parameters.keys.any { it.contains("shell", true) || it.contains("script", true) || it.contains("command", true) || it.contains("coordinate", true) } ||
            Regex("(?:\\b(?:x|y)\\s*[:=]|\\b(?:tap|click)\\s*\\(\\s*\\d+\\s*,|coordinates?)", RegexOption.IGNORE_CASE).containsMatchIn(combinedContent)) {
            return StepSafetyResult(false, AgentPlanRisk.HIGH, false, "Step $stepNumber contains an unsupported command or coordinate target.")
        }

        // 1. Check for strictly blocked sensitive keywords (OTP, PIN, passwords, banking)
        for (kw in SENSITIVE_KEYWORDS) {
            if (combinedContent.contains(kw)) {
                return StepSafetyResult(
                    isAllowed = false,
                    calculatedRisk = AgentPlanRisk.HIGH,
                    requiresConfirmation = false,
                    rejectionReason = "Step $stepNumber accesses sensitive security/credential content ($kw), which is strictly forbidden."
                )
            }
        }

        // 2. Check for strictly blocked financial/destructive keywords
        for (kw in FINANCIAL_OR_DESTRUCTIVE_KEYWORDS) {
            if (combinedContent.contains(kw)) {
                return StepSafetyResult(
                    isAllowed = false,
                    calculatedRisk = AgentPlanRisk.HIGH,
                    requiresConfirmation = false,
                    rejectionReason = "Step $stepNumber involves financial or destructive operations ($kw), which is strictly forbidden."
                )
            }
        }

        // 3. Action-specific validation
        return when (step.action) {
            PlannedAction.OPEN_APP -> {
                if (target.isBlank() || !isSupportedApp(target)) {
                    StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false,
                        rejectionReason = "Step $stepNumber: App target is missing or unsupported."
                    )
                } else {
                    StepSafetyResult(
                        isAllowed = true,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false
                    )
                }
            }

            PlannedAction.INSPECT_UI,
            PlannedAction.SCROLL,
            PlannedAction.BACK,
            PlannedAction.HOME,
            PlannedAction.READ_NOTIFICATION,
            PlannedAction.QUERY_MEMORY,
            PlannedAction.ASK_USER -> {
                StepSafetyResult(
                    isAllowed = true,
                    calculatedRisk = AgentPlanRisk.LOW,
                    requiresConfirmation = false
                )
            }

            PlannedAction.WAIT_FOR_WINDOW -> {
                if (!isSupportedApp(target)) {
                    StepSafetyResult(false, AgentPlanRisk.LOW, false,
                        "Step $stepNumber waits for an unsupported app.")
                } else {
                    StepSafetyResult(true, AgentPlanRisk.LOW, false)
                }
            }

            PlannedAction.TAP -> {
                if (target.isBlank()) {
                    return StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false,
                        rejectionReason = "Step $stepNumber: Tap target element query is empty."
                    )
                }

                // Check medium-risk actions requiring confirmation
                val needsConfirm = MEDIUM_RISK_KEYWORDS.any { kw -> target.lowercase().contains(kw) }
                StepSafetyResult(
                    isAllowed = true,
                    calculatedRisk = if (needsConfirm) AgentPlanRisk.MEDIUM else AgentPlanRisk.LOW,
                    requiresConfirmation = needsConfirm
                )
            }

            PlannedAction.TYPE_TEXT -> {
                if (target.isBlank() || textParam.isBlank()) {
                    return StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false,
                        rejectionReason = "Step $stepNumber: Type text requires a valid target and text parameter."
                    )
                }
                StepSafetyResult(
                    isAllowed = true,
                    calculatedRisk = AgentPlanRisk.LOW,
                    requiresConfirmation = false
                )
            }

            PlannedAction.DEVICE_TOOL -> {
                val toolName = step.parameters["tool"]?.uppercase() ?: target.uppercase()
                val allowedTools = setOf("FLASHLIGHT", "VOLUME", "OPEN_SETTINGS", "OPEN_APP", "GO_HOME")
                if (toolName !in allowedTools) {
                    StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.HIGH,
                        requiresConfirmation = false,
                        rejectionReason = "Step $stepNumber: Unsupported or unpermitted device tool '$toolName'."
                    )
                } else {
                    StepSafetyResult(
                        isAllowed = true,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false
                    )
                }
            }

            PlannedAction.PREPARE_MESSAGE -> {
                val recipient = step.parameters["recipient"] ?: target
                val message = step.parameters["message"] ?: textParam
                val app = step.parameters["app"] ?: "WhatsApp"
                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isValidRecipient(recipient) ||
                    message.isBlank() || message.length > com.boopathi.bg.service.messaging.MessagingPolicy.MAX_MESSAGE_LENGTH ||
                    app !in com.boopathi.bg.service.messaging.MessagingPolicy.SUPPORTED_APPS) {
                    StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false,
                        rejectionReason = "Step $stepNumber: Message recipient is missing."
                    )
                } else {
                    StepSafetyResult(
                        isAllowed = true,
                        calculatedRisk = AgentPlanRisk.LOW,
                        requiresConfirmation = false
                    )
                }
            }

            PlannedAction.SEND_MESSAGE -> {
                val recipient = step.parameters["recipient"] ?: target
                val messageText = step.parameters["message"] ?: textParam
                val app = step.parameters["app"] ?: "WhatsApp"

                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isValidRecipient(recipient) ||
                    messageText.isBlank() || messageText.length > com.boopathi.bg.service.messaging.MessagingPolicy.MAX_MESSAGE_LENGTH ||
                    app !in com.boopathi.bg.service.messaging.MessagingPolicy.SUPPORTED_APPS ||
                    !com.boopathi.bg.service.messaging.MessagingPolicy.isContentSafeToSend(messageText)) {
                    return StepSafetyResult(
                        isAllowed = false,
                        calculatedRisk = AgentPlanRisk.MEDIUM,
                        requiresConfirmation = true,
                        rejectionReason = "Step $stepNumber: Cannot send message without a valid recipient."
                    )
                }

                // Sending a message always requires explicit confirmation and is MEDIUM risk
                StepSafetyResult(
                    isAllowed = true,
                    calculatedRisk = AgentPlanRisk.MEDIUM,
                    requiresConfirmation = true
                )
            }
        }
    }

    private fun isSupportedApp(value: String): Boolean {
        val clean = value.trim().lowercase()
        return clean in COMMON_APP_NAMES || clean in SUPPORTED_PACKAGES
    }
}


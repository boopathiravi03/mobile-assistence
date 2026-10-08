package com.boopathi.bg.service.agent

import com.boopathi.bg.service.tool.RiskLevel

data class RiskDecision(
    val level: RiskLevel,
    val isPermitted: Boolean,
    val status: AppActionResultStatus,
    val reason: String? = null
)

/**
 * Enforces risk-based execution boundaries for Phase 4 App Agent operations.
 */
object AgentExecutionPolicy {

    private val SENSITIVE_KEYWORDS = listOf(
        "password", "passcode", "pin", "otp", "cvv", "credit card",
        "debit card", "bank account", "social security", "ssn",
        "fingerprint", "face id", "biometric"
    )

    private val FINANCIAL_OR_DESTRUCTIVE_KEYWORDS = listOf(
        "pay", "purchase", "buy now", "send money", "transfer money",
        "delete account", "erase all", "factory reset", "uninstall"
    )

    private val MEDIUM_RISK_ACTION_KEYWORDS = listOf(
        "send", "post", "delete", "remove", "submit", "apply"
    )

    fun evaluateActionRisk(action: AppAction): RiskDecision {
        return when (action) {
            is AppAction.OpenApp -> RiskDecision(
                level = RiskLevel.LOW,
                isPermitted = true,
                status = AppActionResultStatus.SUCCESS
            )

            is AppAction.WaitForWindow -> RiskDecision(
                level = RiskLevel.LOW,
                isPermitted = true,
                status = AppActionResultStatus.SUCCESS
            )

            is AppAction.InspectUi -> RiskDecision(
                level = RiskLevel.LOW,
                isPermitted = true,
                status = AppActionResultStatus.SUCCESS
            )

            is AppAction.Scroll -> RiskDecision(
                level = RiskLevel.LOW,
                isPermitted = true,
                status = AppActionResultStatus.SUCCESS
            )

            is AppAction.Back, is AppAction.Home -> RiskDecision(
                level = RiskLevel.LOW,
                isPermitted = true,
                status = AppActionResultStatus.SUCCESS
            )

            is AppAction.TypeText -> {
                val lowerQuery = action.targetQuery.lowercase()
                val lowerText = action.textToType.lowercase()

                // Check for sensitive credential/payment entries -> HIGH risk, BLOCKED
                for (kw in SENSITIVE_KEYWORDS) {
                    if (lowerQuery.contains(kw) || lowerText.contains(kw)) {
                        return RiskDecision(
                            level = RiskLevel.HIGH,
                            isPermitted = false,
                            status = AppActionResultStatus.BLOCKED,
                            reason = "Typing sensitive security/credential data ($kw) is strictly blocked."
                        )
                    }
                }

                // Check for financial terms -> HIGH risk, BLOCKED
                for (kw in FINANCIAL_OR_DESTRUCTIVE_KEYWORDS) {
                    if (lowerQuery.contains(kw) || lowerText.contains(kw)) {
                        return RiskDecision(
                            level = RiskLevel.HIGH,
                            isPermitted = false,
                            status = AppActionResultStatus.BLOCKED,
                            reason = "Financial transaction typing ($kw) is strictly blocked in Phase 4."
                        )
                    }
                }

                // Ordinary text typing is LOW risk
                RiskDecision(
                    level = RiskLevel.LOW,
                    isPermitted = true,
                    status = AppActionResultStatus.SUCCESS
                )
            }

            is AppAction.Tap -> {
                val lowerQuery = action.targetQuery.lowercase()

                // High risk credential / security taps -> BLOCKED
                for (kw in SENSITIVE_KEYWORDS) {
                    if (lowerQuery.contains(kw)) {
                        return RiskDecision(
                            level = RiskLevel.HIGH,
                            isPermitted = false,
                            status = AppActionResultStatus.BLOCKED,
                            reason = "Tapping sensitive credential or security target ($kw) is blocked."
                        )
                    }
                }

                // High risk taps -> BLOCKED
                for (kw in FINANCIAL_OR_DESTRUCTIVE_KEYWORDS) {
                    if (lowerQuery.contains(kw)) {
                        return RiskDecision(
                            level = RiskLevel.HIGH,
                            isPermitted = false,
                            status = AppActionResultStatus.BLOCKED,
                            reason = "Tapping financial or destructive button ($kw) is blocked in Phase 4."
                        )
                    }
                }

                // Medium risk taps -> REQUIRES_CONFIRMATION
                for (kw in MEDIUM_RISK_ACTION_KEYWORDS) {
                    if (lowerQuery.contains(kw)) {
                        return RiskDecision(
                            level = RiskLevel.MEDIUM,
                            isPermitted = false,
                            status = AppActionResultStatus.REQUIRES_CONFIRMATION,
                            reason = "Action '$kw' has external consequences and requires explicit confirmation."
                        )
                    }
                }

                // Harmless navigation taps -> LOW risk, PERMITTED
                RiskDecision(
                    level = RiskLevel.LOW,
                    isPermitted = true,
                    status = AppActionResultStatus.SUCCESS
                )
            }

            is AppAction.SendMessage -> {
                // Inspect content safety first
                if (!com.boopathi.bg.service.messaging.MessagingPolicy.isContentSafeToSend(action.expectedMessage)) {
                    return RiskDecision(
                        level = RiskLevel.HIGH,
                        isPermitted = false,
                        status = AppActionResultStatus.BLOCKED,
                        reason = "Message contains sensitive authentication, credential, or financial content."
                    )
                }

                // SendMessage is a consequential MEDIUM risk action that requires confirmation and authentication
                RiskDecision(
                    level = RiskLevel.MEDIUM,
                    isPermitted = false,
                    status = AppActionResultStatus.REQUIRES_CONFIRMATION,
                    reason = "Sending message to ${action.expectedRecipient} requires explicit confirmation and owner authentication."
                )
            }
        }
    }
}


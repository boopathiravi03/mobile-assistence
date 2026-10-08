package com.boopathi.bg.service.messaging

/**
 * Resolved target recipient for messaging preparation.
 */
data class MessagingTarget(
    val displayName: String,
    val sourceApp: String = "WhatsApp",
    val identifierType: String = "CONTACT_NAME",
    val confidence: Float = 1.0f
)

enum class TargetResolutionStatus {
    FOUND,
    NOT_FOUND,
    AMBIGUOUS,
    UNAVAILABLE
}

data class TargetResolutionResult(
    val status: TargetResolutionStatus,
    val target: MessagingTarget? = null,
    val candidateMatches: List<String> = emptyList(),
    val reason: String? = null
)


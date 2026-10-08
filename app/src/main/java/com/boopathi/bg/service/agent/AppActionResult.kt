package com.boopathi.bg.service.agent

enum class AppActionResultStatus {
    SUCCESS,
    FAILED,
    NOT_FOUND,
    UNAVAILABLE,
    BLOCKED,
    REQUIRES_CONFIRMATION,
    TIMEOUT,
    REQUIRES_ACCESSIBILITY,
    REQUIRES_CLARIFICATION
}

data class AppActionResult(
    val actionType: AppActionType,
    val status: AppActionResultStatus,
    val targetDescription: String,
    val reason: String? = null,
    val executionDurationMs: Long = 0L,
    val verificationDetails: String? = null,
    val data: Map<String, Any?> = emptyMap()
) {
    val isSuccess: Boolean
        get() = status == AppActionResultStatus.SUCCESS
}


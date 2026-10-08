package com.boopathi.bg.service.messaging

/**
 * Result outcomes for messaging dispatch and verification.
 */
enum class MessagingSendStatus {
    SUCCESS,
    UNVERIFIED,
    BLOCKED,
    CANCELLED,
    AUTH_FAILED,
    RECIPIENT_MISMATCH,
    DRAFT_MISMATCH,
    SEND_CONTROL_NOT_FOUND,
    APP_NOT_SUPPORTED,
    TIMEOUT,
    FAILED
}

data class MessagingSendResult(
    val status: MessagingSendStatus,
    val recipient: String,
    val messageText: String,
    val details: String? = null,
    val isVerified: Boolean = false
) {
    val isSuccess: Boolean
        get() = status == MessagingSendStatus.SUCCESS
}


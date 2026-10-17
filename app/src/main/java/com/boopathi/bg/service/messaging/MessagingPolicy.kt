package com.boopathi.bg.service.messaging

/**
 * High-level policy governing messaging capabilities across phases.
 *
 * Phase 5C Upgrade:
 * Sending is permitted ONLY when ALL conditions are met:
 * 1. Valid MessageDraft
 * 2. Valid recipient
 * 3. Non-empty message
 * 4. Explicit user confirmation (not expired, max 60s)
 * 5. Successful owner/device authentication
 * 6. Supported messaging application (e.g. WhatsApp)
 * 7. Message content passed sensitive/financial safety inspection
 * 8. Valid pre-send verification of conversation and send control
 */
object MessagingPolicy {
    /** Preparation of messaging drafts and navigation via AppAgent is allowed */
    const val PREPARATION_ALLOWED = true

    /** All consequential messaging actions require explicit user confirmation */
    const val CONFIRMATION_REQUIRED = true

    /** Owner/device authentication is strictly required before the final send */
    const val OWNER_AUTH_REQUIRED = true

    /**
     * In Phase 5C, sending is supported ONLY under controlled conditions.
     */
    const val SEND_ALLOWED = true

    /** Maximum confirmation age in milliseconds (60 seconds) */
    const val CONFIRMATION_TIMEOUT_MS = 60_000L

    /** Bounded post-send verification window in milliseconds (5 seconds) */
    const val SEND_VERIFY_TIMEOUT_MS = 5_000L
    const val MAX_MESSAGE_LENGTH = 4_000
    private const val MAX_RECIPIENT_LENGTH = 80

    /**
     * Backward-compatibility flag for Phase 5B tests:
     * defaults to false in Phase 5C to enable controlled sending.
     */
    var SENDING_UNSUPPORTED = false

    /**
     * Supported messaging applications whitelist.
     */
    val SUPPORTED_APPS = setOf("WhatsApp")

    fun isSendingPermitted(): Boolean = !SENDING_UNSUPPORTED && SEND_ALLOWED

    fun isValidRecipient(recipient: String): Boolean = recipient == recipient.trim() &&
        recipient.isNotBlank() && recipient.length <= MAX_RECIPIENT_LENGTH &&
        recipient.none { it.isISOControl() }

    /**
     * Inspects message content for sensitive authentication, credentials, or financial data.
     * Returns true if the message is safe to send, false if blocked.
     */
    fun isContentSafeToSend(text: String): Boolean {
        val normalized = text.lowercase().trim()
        if (normalized.isBlank() || normalized.length > MAX_MESSAGE_LENGTH) return false

        val sensitivePhrases = listOf(
            "otp",
            "one-time password",
            "verification code",
            "password",
            "passcode",
            "cvv",
            "card number",
            "bank account",
            "upi pin",
            "security code",
            "authentication code",
            "send money"
        )
        val phraseDetected = sensitivePhrases.any { phrase ->
            Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])")
                .containsMatchIn(normalized)
        }
        if (phraseDetected) return false

        val credentialWithValue = Regex(
            "(?i)\\b(?:otp|pin|code|password|passcode)\\b\\s*(?:is|:|=)?\\s*\\d{4,8}\\b"
        )
        if (credentialWithValue.containsMatchIn(normalized)) return false

        val moneyTransferPattern = Regex(
            "(?i)\\b(?:transfer|send)\\s+(?:\\$|₹|rs\\.?\\s*)?\\d+\\s*" +
                "(?:rupees|inr|usd|bucks)?\\s+to\\b"
        )
        if (moneyTransferPattern.containsMatchIn(normalized)) return false

        return true
    }

    /**
     * Validates whether confirmation is fresh and not expired.
     */
    fun isConfirmationFresh(confirmationTimestamp: Long, now: Long = System.currentTimeMillis()): Boolean {
        return confirmationTimestamp > 0L && now >= confirmationTimestamp &&
            now - confirmationTimestamp <= CONFIRMATION_TIMEOUT_MS
    }
}

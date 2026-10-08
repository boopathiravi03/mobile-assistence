package com.boopathi.bg.service.notification

/**
 * Enforces strict privacy rules on incoming notifications:
 * 1. Filters or redacts authentication, financial, and credential notifications.
 * 2. Prevents sensitive data from being read aloud or forwarded to external AI.
 * 3. Never stores or logs raw security codes.
 */
object NotificationPrivacyPolicy {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp",
        "verification code",
        "one-time password",
        "onetime password",
        "password",
        "pin",
        "cvv",
        "bank",
        "upi",
        "debit",
        "credit",
        "transaction",
        "payment",
        "security code",
        "authentication code",
        "login code",
        "2fa",
        "mfa"
    )

    private val OTP_PATTERN = Regex("\\b\\d{4,8}\\b")

    /**
     * Determines whether the notification text or title contains sensitive authentication or financial information.
     */
    fun isSensitive(title: String, text: String): Boolean {
        val combined = "$title $text".lowercase()
        return SENSITIVE_KEYWORDS.any { keyword -> combined.contains(keyword) }
    }

    /**
     * Sanitizes a NotificationInfo. If sensitive content is detected, the sensitive text
     * is redacted and replaced with a privacy-safe placeholder.
     */
    fun sanitize(info: NotificationInfo): NotificationInfo {
        if (isSensitive(info.title, info.text)) {
            return info.copy(
                text = "Sensitive authentication/transaction details hidden for privacy.",
                isRedacted = true
            )
        }
        return info
    }

    /**
     * Check whether content is safe to speak aloud via TTS.
     */
    fun isSafeToSpeakAloud(info: NotificationInfo): Boolean {
        return !info.isRedacted && !isSensitive(info.title, info.text)
    }

    /**
     * Check whether content is safe to forward to AI.
     * By default, Phase 5B prohibits forwarding notification contents to AI.
     */
    fun isSafeForAI(info: NotificationInfo): Boolean {
        return false // Explicit isolation: Notifications are untrusted data never sent to AI by default.
    }
}


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
        "passcode",
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

    private val SENSITIVE_KEYWORD_PATTERNS = SENSITIVE_KEYWORDS.map { keyword ->
        Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(keyword)}(?![\\p{L}\\p{N}])")
    }

    private val OTP_PATTERN = Regex("\\b\\d{4,8}\\b")
    private val CODE_CONTEXT_PATTERN = Regex(
        "(?i)\\b(?:otp|code|passcode|verification|authentication|" +
            "login|sign[ -]?in|security|confirm)\\b"
    )

    /**
     * Determines whether the notification text or title contains sensitive authentication or financial information.
     */
    fun isSensitive(title: String, text: String): Boolean {
        val combined = "$title $text"
        if (SENSITIVE_KEYWORD_PATTERNS.any { it.containsMatchIn(combined) }) return true

        // Redact short numeric codes when nearby wording makes their purpose clear.
        // This deliberately avoids treating every 4-8 digit number as a secret.
        return OTP_PATTERN.findAll(combined).any { match ->
            val contextStart = (match.range.first - 40).coerceAtLeast(0)
            val contextEnd = (match.range.last + 33).coerceAtMost(combined.length)
            CODE_CONTEXT_PATTERN.containsMatchIn(combined.substring(contextStart, contextEnd))
        }
    }

    /**
     * Sanitizes a NotificationInfo. If sensitive content is detected, the sensitive text
     * is redacted and replaced with a privacy-safe placeholder.
     */
    fun sanitize(info: NotificationInfo, additionalText: String = ""): NotificationInfo {
        if (isSensitive(info.title, "${info.text} $additionalText")) {
            return info.copy(
                title = "Sensitive notification",
                text = "Sensitive authentication/transaction details hidden for privacy.",
                senderName = "",
                conversationKey = "",
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


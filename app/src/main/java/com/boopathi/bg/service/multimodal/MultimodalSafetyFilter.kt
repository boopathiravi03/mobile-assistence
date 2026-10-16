package com.boopathi.bg.service.multimodal

/**
 * Enforces privacy, prompt injection defense, and action-separation guarantees
 * for multimodal inputs and extracted visual content.
 */
object MultimodalSafetyFilter {

    private val SENSITIVE_PATTERNS = listOf(
        Regex("(?i)\\b(password|passcode|pin|otp|cvv|cvc|token|secret)\\b"),
        Regex("(?i)(password|passcode|secret)\\d+"),
        Regex("^\\d{4,6}$"),                                      // Isolated 4-6 digit OTP/PIN
        Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"), // Credit card
        Regex("\\b\\d{3}[ -]?\\d{2}[ -]?\\d{4}\\b")             // SSN
    )

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore (all )?previous instructions"),
        Regex("(?i)disregard (all )?prior (rules|instructions)"),
        Regex("(?i)system prompt:?"),
        Regex("(?i)you are now (an unrestricted|a new)"),
        Regex("(?i)developer mode (enabled|active)"),
        Regex("(?i)bypass (all )?security"),
        Regex("(?i)override user instructions"),
        Regex("(?i)new instructions:?")
    )

    /**
     * Checks if text contains sensitive credentials, payment info, or secrets.
     */
    fun containsSensitiveData(text: String): Boolean {
        if (text.isBlank()) return false
        return SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Detects potential adversarial prompt injection attempts embedded in image OCR text.
     */
    fun isPromptInjectionDetected(text: String): Boolean {
        if (text.isBlank()) return false
        return PROMPT_INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Sanitizes diagnostic text by redacting detected sensitive numbers/tokens.
     */
    fun redactSensitiveContent(text: String): String {
        var sanitized = text
        sanitized = Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b").replace(sanitized, "[REDACTED_CARD]")
        sanitized = Regex("\\b\\d{3}[ -]?\\d{2}[ -]?\\d{4}\\b").replace(sanitized, "[REDACTED_SSN]")
        sanitized = Regex("(?i)(password|passcode|secret)[:= ]+(\\S+)").replace(sanitized, "$1: [REDACTED]")
        return sanitized
    }

    /**
     * Enforces action separation: Validates that a string from perception is strictly advisory
     * and cannot be treated directly as an executable Android plan.
     */
    fun sanitizeAdvisoryAction(actionSuggestion: String): String {
        return actionSuggestion.trim()
            .removePrefix("Run: ")
            .removePrefix("Execute: ")
            .removePrefix("Command: ")
    }
}


package com.boopathi.bg.service.messaging

/**
 * High-level policy governing messaging capabilities across phases.
 */
object MessagingPolicy {
    /** Preparation of messaging drafts and navigation via AppAgent is allowed */
    const val PREPARATION_ALLOWED = true

    /** All consequential messaging actions require explicit user confirmation */
    const val CONFIRMATION_REQUIRED = true

    /**
     * CRITICAL PHASE 5B SAFETY BOUNDARY:
     * Actual message sending is strictly UNSUPPORTED and DISABLED.
     * BG must NEVER dispatch a message or press Send in Phase 5B.
     */
    const val SENDING_UNSUPPORTED = true

    fun isSendingPermitted(): Boolean = !SENDING_UNSUPPORTED
}


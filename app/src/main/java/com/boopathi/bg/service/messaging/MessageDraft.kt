package com.boopathi.bg.service.messaging

/**
 * Immutable draft representation of a message prepared for sending.
 * Does not automatically dispatch.
 */
data class MessageDraft(
    val recipient: String,
    val messageText: String,
    val sourceApp: String = "WhatsApp",
    val createdAt: Long = System.currentTimeMillis()
)


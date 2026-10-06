package com.boopathi.bg.data.model

import java.util.UUID

enum class Sender {
    USER,
    BG
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)


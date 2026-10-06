package com.boopathi.bg.data.model

enum class AssistantState(val statusLabel: String) {
    IDLE("Tap microphone to talk to BG"),
    LISTENING("Listening..."),
    PROCESSING("Thinking..."),
    SPEAKING("BG is responding..."),
    ERROR("Something went wrong")
}


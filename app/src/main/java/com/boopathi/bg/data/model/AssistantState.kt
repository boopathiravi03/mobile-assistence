package com.boopathi.bg.data.model

enum class AssistantState(val statusLabel: String) {
    IDLE("Tap microphone to talk to BG"),
    REQUESTING_PERMISSION("Microphone permission required"),
    LISTENING("Listening..."),
    RECOGNIZING("Recognizing speech..."),
    PROCESSING("Thinking..."),
    WAITING_FOR_CONFIRMATION("Waiting for confirmation..."),
    EXECUTING("Working on your request..."),
    SPEAKING("BG is responding..."),
    PAUSED("Voice session paused"),
    CANCELLED("Cancelled"),
    ERROR("Something went wrong")
}


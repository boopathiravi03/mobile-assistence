package com.boopathi.bg.service.voice

import com.boopathi.bg.data.model.AssistantState

/** State transition rules shared by the voice coordinator and its tests. */
internal object VoiceStateTransitions {
    private val allowed = mapOf(
        AssistantState.IDLE to setOf(AssistantState.REQUESTING_PERMISSION, AssistantState.LISTENING, AssistantState.PROCESSING, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.REQUESTING_PERMISSION to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.LISTENING to setOf(AssistantState.RECOGNIZING, AssistantState.PROCESSING, AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.RECOGNIZING to setOf(AssistantState.PROCESSING, AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.PROCESSING to setOf(AssistantState.WAITING_FOR_CONFIRMATION, AssistantState.EXECUTING, AssistantState.SPEAKING, AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.WAITING_FOR_CONFIRMATION to setOf(AssistantState.EXECUTING, AssistantState.SPEAKING, AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.EXECUTING to setOf(AssistantState.SPEAKING, AssistantState.IDLE, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.SPEAKING to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.CANCELLED, AssistantState.ERROR),
        AssistantState.PAUSED to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.CANCELLED),
        AssistantState.CANCELLED to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.ERROR),
        AssistantState.ERROR to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.CANCELLED)
    )

    fun allows(from: AssistantState, to: AssistantState): Boolean = from == to || allowed[from]?.contains(to) == true
}

package com.boopathi.bg

import com.boopathi.bg.data.model.AssistantState
import com.boopathi.bg.service.voice.VoiceSessionGate
import com.boopathi.bg.service.voice.VoiceStateTransitions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionReliabilityTest {
    @Test fun startsCreateDistinctSessions() {
        val gate = VoiceSessionGate()
        val first = gate.begin()
        val second = gate.begin()
        assertNotEquals(first, second)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun cancelledSessionRejectsLateRecognitionCallback() {
        val gate = VoiceSessionGate()
        val session = gate.begin()
        gate.invalidate(session)
        assertFalse(gate.isCurrent(session))
        assertFalse(gate.claimFinalResult(session))
    }

    @Test fun onlyOneFinalRecognitionCallbackCanBeClaimed() {
        val gate = VoiceSessionGate()
        val session = gate.begin()
        assertTrue(gate.claimFinalResult(session))
        assertFalse(gate.claimFinalResult(session))
    }

    @Test fun obsoleteCancellationCannotInvalidateNewSession() {
        val gate = VoiceSessionGate()
        val oldSession = gate.begin()
        val newSession = gate.begin()
        gate.invalidate(oldSession)
        assertTrue(gate.isCurrent(newSession))
    }

    @Test fun listeningMayMoveToRecognitionAndProcessing() {
        assertTrue(VoiceStateTransitions.allows(AssistantState.LISTENING, AssistantState.RECOGNIZING))
        assertTrue(VoiceStateTransitions.allows(AssistantState.RECOGNIZING, AssistantState.PROCESSING))
    }

    @Test fun speakingCannotJumpBackIntoCommandProcessing() {
        assertFalse(VoiceStateTransitions.allows(AssistantState.SPEAKING, AssistantState.PROCESSING))
        assertTrue(VoiceStateTransitions.allows(AssistantState.SPEAKING, AssistantState.CANCELLED))
    }
}

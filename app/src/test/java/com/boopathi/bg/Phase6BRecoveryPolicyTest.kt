package com.boopathi.bg

import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.recovery.RecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Phase 6B RecoveryPolicy rules, bounds, and consequential action restrictions.
 */
class Phase6BRecoveryPolicyTest {

    @Test
    fun testDefaultPolicyBounds() {
        assertEquals(2, RecoveryPolicy.MAX_RECOVERY_ATTEMPTS_PER_STEP)
        assertEquals(4, RecoveryPolicy.MAX_TOTAL_RECOVERY_ATTEMPTS_PER_TASK)
        assertEquals(3, RecoveryPolicy.MAX_RECOVERY_PLAN_STEPS)
        assertEquals(10000L, RecoveryPolicy.MAX_RECOVERY_DURATION_MS)
    }

    @Test
    fun testSendMessageIsConsequentialAndNeverRecoverable() {
        val sendAction = AppAction.SendMessage(expectedRecipient = "Karthi", expectedMessage = "hi")
        assertFalse("SendMessage must never be auto-recovered", RecoveryPolicy.isActionRecoverable(sendAction))

        val check = RecoveryPolicy.canAttemptRecovery(
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis(),
            action = sendAction
        )
        assertFalse(check.allowed)
        assertTrue(check.reason?.contains("consequential") == true)
    }

    @Test
    fun testStandardActionsAreRecoverable() {
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.OpenApp("WhatsApp")))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.Tap("Send")))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.TypeText("Search", "Query")))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.InspectUi))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.WaitForWindow("com.whatsapp")))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.Back))
        assertTrue(RecoveryPolicy.isActionRecoverable(AppAction.Home))
    }

    @Test
    fun testStepAttemptLimitEnforced() {
        val action = AppAction.Tap("Search")
        val now = System.currentTimeMillis()

        // 0 and 1 are allowed (less than MAX_RECOVERY_ATTEMPTS_PER_STEP = 2)
        assertTrue(RecoveryPolicy.canAttemptRecovery(0, 0, now, action).allowed)
        assertTrue(RecoveryPolicy.canAttemptRecovery(1, 0, now, action).allowed)

        // 2 attempts already executed -> disallow
        val check = RecoveryPolicy.canAttemptRecovery(2, 0, now, action)
        assertFalse(check.allowed)
        assertTrue(check.reason?.contains("Step recovery limit reached") == true)
    }

    @Test
    fun testTotalTaskRecoveryLimitEnforced() {
        val action = AppAction.Tap("Search")
        val now = System.currentTimeMillis()

        // total < 4 allowed
        assertTrue(RecoveryPolicy.canAttemptRecovery(0, 3, now, action).allowed)

        // total >= 4 disallowed
        val check = RecoveryPolicy.canAttemptRecovery(0, 4, now, action)
        assertFalse(check.allowed)
        assertTrue(check.reason?.contains("Total task recovery limit reached") == true)
    }

    @Test
    fun testDurationTimeoutEnforced() {
        val action = AppAction.Tap("Search")
        val expiredStart = System.currentTimeMillis() - 15000L // 15s elapsed

        val check = RecoveryPolicy.canAttemptRecovery(0, 0, expiredStart, action)
        assertFalse(check.allowed)
        assertTrue(check.reason?.contains("timed out") == true)
    }
}


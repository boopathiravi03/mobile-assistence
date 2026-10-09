package com.boopathi.bg

import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.recovery.RecoveryPlanner
import com.boopathi.bg.service.recovery.UiMismatch
import com.boopathi.bg.service.recovery.UiMismatchType
import com.boopathi.bg.service.recovery.UiStateSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for deterministic recovery paths, AI safety constraints, and abort logic.
 */
class Phase6BRecoveryPlannerTest {

    private val planner = RecoveryPlanner()

    @Test
    fun testScreenNotReadyGeneratesWaitAndInspect() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Next"), description = "Tap Next")
        val mismatch = UiMismatch(
            type = UiMismatchType.SCREEN_NOT_READY,
            description = "Timeout waiting for screen transition",
            expectedPackage = "com.whatsapp"
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertTrue(proposal.isExecutable)
        assertEquals(2, proposal.recoverySteps.size)
        assertTrue(proposal.recoverySteps[0].action is AppAction.WaitForWindow)
        assertTrue(proposal.recoverySteps[1].action is AppAction.InspectUi)
    }

    @Test
    fun testTargetNotFoundWithScrollableScreenGeneratesScrollAndInspect() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Settings"), description = "Tap Settings")
        val snapshot = UiStateSnapshot(
            packageName = "com.android.settings",
            visibleLabels = listOf("Wi-Fi", "Bluetooth"),
            actionableLabels = listOf("Wi-Fi", "Bluetooth"),
            editableLabels = emptyList(),
            scrollableLabels = listOf("SettingsList")
        )
        val mismatch = UiMismatch(
            type = UiMismatchType.TARGET_NOT_FOUND,
            description = "Target not visible",
            targetQuery = "About Phone",
            observedSnapshot = snapshot
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertTrue(proposal.isExecutable)
        assertEquals(2, proposal.recoverySteps.size)
        assertTrue(proposal.recoverySteps[0].action is AppAction.Scroll)
        assertEquals(ScrollDirection.DOWN, (proposal.recoverySteps[0].action as AppAction.Scroll).direction)
        assertTrue(proposal.recoverySteps[1].action is AppAction.InspectUi)
    }

    @Test
    fun testTargetNotFoundWithoutScrollGeneratesReInspect() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Send"), description = "Tap Send")
        val snapshot = UiStateSnapshot(
            packageName = "com.whatsapp",
            visibleLabels = listOf("Chat", "Input"),
            actionableLabels = listOf("Chat"),
            editableLabels = listOf("Input"),
            scrollableLabels = emptyList()
        )
        val mismatch = UiMismatch(
            type = UiMismatchType.TARGET_NOT_FOUND,
            description = "Target not found",
            targetQuery = "Send",
            observedSnapshot = snapshot
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertTrue(proposal.isExecutable)
        assertEquals(1, proposal.recoverySteps.size)
        assertTrue(proposal.recoverySteps[0].action is AppAction.InspectUi)
    }

    @Test
    fun testAmbiguousTargetAbortsSafely() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Call"), description = "Tap Call")
        val mismatch = UiMismatch(
            type = UiMismatchType.TARGET_AMBIGUOUS,
            description = "Multiple matching items",
            targetQuery = "Call",
            candidateMatches = listOf("Voice Call", "Video Call")
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertFalse("Ambiguous targets must never guess or auto-click", proposal.isExecutable)
        assertTrue(proposal.recoverySteps.isEmpty())
        assertTrue(proposal.explanation.contains("ambiguous"))
    }

    @Test
    fun testWrongForegroundAppRelaunchesExpectedApp() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Send"), description = "Tap Send")
        val mismatch = UiMismatch(
            type = UiMismatchType.WRONG_FOREGROUND_APP,
            description = "Wrong app foregrounded",
            expectedPackage = "com.whatsapp",
            actualPackage = "com.android.launcher"
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertTrue(proposal.isExecutable)
        assertEquals(2, proposal.recoverySteps.size)
        assertTrue(proposal.recoverySteps[0].action is AppAction.OpenApp)
        assertEquals("com.whatsapp", (proposal.recoverySteps[0].action as AppAction.OpenApp).appName)
        assertTrue(proposal.recoverySteps[1].action is AppAction.WaitForWindow)
    }

    @Test
    fun testObservationUnavailableAbortsSafely() = runBlocking {
        val step = AgentStep(action = AppAction.Tap("Next"), description = "Tap Next")
        val mismatch = UiMismatch(
            type = UiMismatchType.OBSERVATION_UNAVAILABLE,
            description = "Accessibility disconnected"
        )

        val proposal = planner.planRecovery(
            failedStep = step,
            mismatch = mismatch,
            stepRecoveryCount = 0,
            totalRecoveryCount = 0,
            recoveryStartTimeMs = System.currentTimeMillis()
        )

        assertFalse(proposal.isExecutable)
        assertTrue(proposal.recoverySteps.isEmpty())
    }
}


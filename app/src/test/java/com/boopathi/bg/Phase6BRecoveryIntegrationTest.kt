package com.boopathi.bg

import com.boopathi.bg.service.agent.AgentExecutionConfig
import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AgentTask
import com.boopathi.bg.service.agent.AgentTaskState
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.MultiStepAgent
import com.boopathi.bg.service.agent.StepStatus
import com.boopathi.bg.service.agent.UiElementInfo
import com.boopathi.bg.service.recovery.ExpectedState
import com.boopathi.bg.service.recovery.ExpectedStateType
import com.boopathi.bg.service.recovery.RecoveryPlanner
import com.boopathi.bg.service.recovery.RecoveryPolicy
import com.boopathi.bg.service.recovery.SafeTargetMatcher
import com.boopathi.bg.service.recovery.TargetMatchResult
import com.boopathi.bg.service.recovery.UiMismatchType
import com.boopathi.bg.service.recovery.UiSnapshotObserver
import com.boopathi.bg.service.recovery.UiStateSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration tests for Phase 6B:
 * UI state observation, safe target matching, bounded step recovery, and stopping on failure.
 */
class Phase6BRecoveryIntegrationTest {

    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var appAgent: AppAgent
    private lateinit var multiStepAgent: MultiStepAgent
    private lateinit var observer: UiSnapshotObserver

    @Before
    fun setUp() {
        bridge = FakeAccessibilityBridge()
        appAgent = AppAgent(bridge = bridge)
        multiStepAgent = MultiStepAgent(
            appAgent = appAgent,
            config = AgentExecutionConfig(maxSteps = 8, maxTaskDurationMs = 15000L)
        )
        observer = UiSnapshotObserver(bridge = bridge)
    }

    @Test
    fun testSafeTargetMatcherExactAndAmbiguous() {
        val elements = listOf(
            UiElementInfo(text = "Search settings", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Search history", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Settings", isClickable = true, isEnabled = true)
        )

        // Exact match
        val exact = SafeTargetMatcher.matchTarget(elements, "Settings")
        assertTrue(exact is TargetMatchResult.SingleMatch)
        assertEquals("Settings", (exact as TargetMatchResult.SingleMatch).element.text)

        // Ambiguous match with no exact match
        val ambig = SafeTargetMatcher.matchTarget(elements, "Search")
        assertTrue(ambig is TargetMatchResult.MultipleMatches)
        assertEquals(2, (ambig as TargetMatchResult.MultipleMatches).distinctLabels.size)

        // No match
        val noMatch = SafeTargetMatcher.matchTarget(elements, "Bluetooth")
        assertTrue(noMatch is TargetMatchResult.NoMatch)
    }

    @Test
    fun testUiSnapshotObserverSanitizationAndVerification() = runBlocking {
        bridge.currentPkg = "com.android.settings"
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Display\n\n", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Sound", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Search settings", isEditable = true, isEnabled = true)
        )

        val snapshot = observer.captureSnapshot()
        assertTrue(snapshot.isAvailable)
        assertEquals("com.android.settings", snapshot.packageName)
        assertTrue(snapshot.hasVisibleLabel("Display"))
        assertTrue(snapshot.hasEditableLabel("Search settings"))

        // Verify ExpectedState matches
        val expected = ExpectedState(
            type = ExpectedStateType.TARGET_VISIBLE,
            targetQuery = "Display"
        )
        val verResult = observer.verifyExpectedState(snapshot, expected)
        assertTrue(verResult.isVerified)

        // Verify mismatch when target absent
        val absentExpected = ExpectedState(
            type = ExpectedStateType.TARGET_VISIBLE,
            targetQuery = "Security & Privacy"
        )
        val absentResult = observer.verifyExpectedState(snapshot, absentExpected)
        assertFalse(absentResult.isVerified)
        assertEquals(UiMismatchType.TARGET_NOT_FOUND, absentResult.mismatch?.type)
    }

    @Test
    fun testMultiStepAgentRecoversWhenTargetAppearsAfterReInspect() = runBlocking {
        // Step 1 will initially fail because element isn't there yet
        bridge.currentPkg = "com.whatsapp"
        bridge.mockElements = mutableListOf() // Empty at first

        // Simulate that after the first attempt fails, the element becomes visible in the bridge
        // Use a bridge that starts empty, and on first tap fails, then element appears
        val customBridge = FakeAccessibilityBridge()
        customBridge.mockElements = mutableListOf()

        val testAgent = MultiStepAgent(
            appAgent = AppAgent(bridge = customBridge),
            config = AgentExecutionConfig(maxSteps = 5)
        )

        val task = AgentTask(
            originalCommand = "Tap Next",
            normalizedGoal = "Tap Next",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Next"), description = "Tap Next", maxRetries = 0)
            )
        )

        // Run execution and populate element dynamically
        var attempts = 0
        val dynamicBridge = object : FakeAccessibilityBridge() {
            override suspend fun getVisibleElements(): List<UiElementInfo> {
                return if (attempts > 0) {
                    listOf(UiElementInfo(text = "Next", isClickable = true, isEnabled = true))
                } else {
                    listOf(UiElementInfo(text = "Loading", isClickable = false, isEnabled = true))
                }
            }

            override suspend fun findByText(query: String, normalized: Boolean): List<UiElementInfo> {
                attempts++
                return if (attempts > 1) {
                    listOf(UiElementInfo(text = "Next", isClickable = true, isEnabled = true))
                } else {
                    emptyList()
                }
            }
        }

        val dynamicAgent = MultiStepAgent(
            appAgent = AppAgent(bridge = dynamicBridge),
            config = AgentExecutionConfig(maxSteps = 5)
        )

        val result = dynamicAgent.executeTask(task)

        assertEquals(AgentTaskState.SUCCESS, result.state)
        assertEquals(1, result.recoveryAttemptCount)
        assertEquals(StepStatus.SUCCESS, result.steps[0].status)
    }

    @Test
    fun testMultiStepAgentTerminatesSafelyWhenMaxRecoveryExceeded() = runBlocking {
        // Element is never present
        bridge.mockElements = mutableListOf()

        val task = AgentTask(
            originalCommand = "Tap Nonexistent",
            normalizedGoal = "Tap Nonexistent",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Nonexistent"), description = "Tap Nonexistent")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.FAILED, result.state)
        // Must respect step recovery limit (MAX_RECOVERY_ATTEMPTS_PER_STEP = 2)
        assertEquals(RecoveryPolicy.MAX_RECOVERY_ATTEMPTS_PER_STEP, result.recoveryAttemptCount)
        assertEquals(StepStatus.FAILED, result.steps[0].status)
    }

    @Test
    fun testMultiStepAgentNeverAutoRecoversSendMessage() = runBlocking {
        val task = AgentTask(
            originalCommand = "Send hi to Karthi",
            normalizedGoal = "Send hi to Karthi",
            steps = mutableListOf(
                AgentStep(
                    action = AppAction.SendMessage("Karthi", "hi"),
                    description = "Send message"
                )
            )
        )

        // Without confirmation, SendMessage will fail risk policy or action
        val result = multiStepAgent.executeTask(task)

        // Should NOT attempt auto-recovery
        assertEquals(0, result.recoveryAttemptCount)
    }
}


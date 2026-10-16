package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.agent.AgentExecutionConfig
import com.boopathi.bg.service.agent.AgentExecutionPolicy
import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AgentTask
import com.boopathi.bg.service.agent.AgentTaskState
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.AppPlanner
import com.boopathi.bg.service.agent.MultiStepAgent
import com.boopathi.bg.service.agent.StepStatus
import com.boopathi.bg.service.agent.UiElementInfo
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.tool.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase5AMultiStepAgentTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var appAgent: AppAgent
    private lateinit var multiStepAgent: MultiStepAgent
    private lateinit var processor: CommandProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository)

        bridge = FakeAccessibilityBridge()
        appAgent = AppAgent(bridge = bridge)
        multiStepAgent = MultiStepAgent(appAgent = appAgent)
        processor = CommandProcessor(
            memoryManager = memoryManager,
            appAgent = appAgent,
            multiStepAgent = multiStepAgent
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Task Creation & Bounded Step Initialization
    @Test
    fun testAgentTaskCreation() {
        val task = AgentTask(
            originalCommand = "Open YouTube and tap Search",
            normalizedGoal = "Open YouTube and tap Search",
            steps = mutableListOf(
                AgentStep(action = AppAction.OpenApp("YouTube"), description = "Open YouTube"),
                AgentStep(action = AppAction.WaitForWindow("youtube", 3000L), description = "Wait for window"),
                AgentStep(action = AppAction.InspectUi, description = "Inspect UI"),
                AgentStep(action = AppAction.Tap("Search"), description = "Tap Search")
            )
        )

        assertEquals(4, task.totalSteps)
        assertEquals(AgentTaskState.IDLE, task.state)
        assertEquals(0, task.currentStepIndex)
        assertEquals("Open YouTube", task.currentStep?.description)
        assertFalse(task.isComplete)
    }

    // 2. Sequential Step Execution in Strict Order
    @Test
    fun testMultiStepTaskExecutesInOrder() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Search", isClickable = true, isEnabled = true)
        )

        val task = AgentTask(
            originalCommand = "Open YouTube and tap Search",
            normalizedGoal = "Open YouTube and tap Search",
            steps = mutableListOf(
                AgentStep(action = AppAction.InspectUi, description = "Inspect UI"),
                AgentStep(action = AppAction.Tap("Search"), description = "Tap Search")
            )
        )

        val completed = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.SUCCESS, completed.state)
        assertEquals(StepStatus.SUCCESS, completed.steps[0].status)
        assertEquals(StepStatus.SUCCESS, completed.steps[1].status)
        assertTrue(bridge.executedActions.contains("click:Search"))
    }

    // 3. Strict Stop-on-Failure Boundary (No further execution when step fails)
    @Test
    fun testTaskStopsWhenStepFails() = runBlocking {
        // Missing "Search" will cause Step 1 (Tap) to fail
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Home", isClickable = true)
        )

        val task = AgentTask(
            originalCommand = "Tap Search and go Back",
            normalizedGoal = "Tap Search and go Back",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Search"), description = "Tap Search", maxRetries = 0),
                AgentStep(action = AppAction.Back, description = "Navigate Back")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.FAILED, result.state)
        assertEquals(StepStatus.FAILED, result.steps[0].status)
        assertEquals(StepStatus.PENDING, result.steps[1].status) // Step 2 must NEVER run
        assertFalse(bridge.executedActions.contains("back"))
    }

    // 4. Verification Requirement: Next Step cannot run without current step verified
    @Test
    fun testNextStepCannotRunBeforeVerification() = runBlocking {
        // Disabled element fails verification
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Next", isClickable = true, isEnabled = false)
        )

        val task = AgentTask(
            originalCommand = "Tap Next and Scroll",
            normalizedGoal = "Tap Next and Scroll",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Next"), description = "Tap Next", maxRetries = 0),
                AgentStep(action = AppAction.Scroll(), description = "Scroll down")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.FAILED, result.state)
        assertEquals(StepStatus.FAILED, result.steps[0].status)
        assertEquals(StepStatus.PENDING, result.steps[1].status)
    }

    // 5. Transient Failure Retries Once within Bounded Limit
    @Test
    fun testTransientFailureRetriesOnce() = runBlocking {
        var attempts = 0
        val dynamicBridge = object : FakeAccessibilityBridge() {
            override suspend fun click(element: UiElementInfo): Boolean {
                attempts++
                return if (attempts == 1) false else true // Succeed on retry
            }
        }
        val customAgent = MultiStepAgent(appAgent = AppAgent(bridge = dynamicBridge))
        dynamicBridge.mockElements = mutableListOf(
            UiElementInfo(text = "Reload", isClickable = true, isEnabled = true)
        )

        val task = AgentTask(
            originalCommand = "Tap Reload",
            normalizedGoal = "Tap Reload",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Reload"), description = "Tap Reload", maxRetries = 1)
            )
        )

        val result = customAgent.executeTask(task)

        assertEquals(AgentTaskState.SUCCESS, result.state)
        assertEquals(1, task.steps[0].retryCount)
    }

    // 6. Retry Limit Enforced (Stops after maxRetries)
    @Test
    fun testRetryLimitEnforced() = runBlocking {
        val failingBridge = object : FakeAccessibilityBridge() {
            override suspend fun click(element: UiElementInfo): Boolean = false
        }
        val customAgent = MultiStepAgent(appAgent = AppAgent(bridge = failingBridge))
        failingBridge.mockElements = mutableListOf(
            UiElementInfo(text = "Reload", isClickable = true, isEnabled = true)
        )

        val task = AgentTask(
            originalCommand = "Tap Reload",
            normalizedGoal = "Tap Reload",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Reload"), description = "Tap Reload", maxRetries = 1)
            )
        )

        val result = customAgent.executeTask(task)

        assertEquals(AgentTaskState.FAILED, result.state)
        assertEquals(1, task.steps[0].retryCount) // Max retries exhausted
    }

    // 7. Overall Task Duration Timeout Boundary
    @Test
    fun testTaskTimeout() = runBlocking {
        val shortTimeoutConfig = AgentExecutionConfig(maxTaskDurationMs = 50L)
        val timeoutAgent = MultiStepAgent(appAgent = appAgent, config = shortTimeoutConfig)

        val task = AgentTask(
            originalCommand = "Long task",
            normalizedGoal = "Long task",
            steps = mutableListOf(
                AgentStep(action = AppAction.InspectUi, description = "Step 1"),
                AgentStep(action = AppAction.InspectUi, description = "Step 2")
            )
        )

        // Artificially delay bridge action to trigger timeout
        val slowBridge = object : FakeAccessibilityBridge() {
            override suspend fun getVisibleElements(): List<UiElementInfo> {
                delay(80L)
                return listOf(UiElementInfo(text = "Item"))
            }
        }
        val slowAgent = MultiStepAgent(appAgent = AppAgent(bridge = slowBridge), config = shortTimeoutConfig)
        val result = slowAgent.executeTask(task)

        assertEquals(AgentTaskState.TIMEOUT, result.state)
    }

    // 8. Maximum Step Count Limit Enforced
    @Test
    fun testMaximumStepCount() = runBlocking {
        val limitConfig = AgentExecutionConfig(maxSteps = 3)
        val limitedAgent = MultiStepAgent(appAgent = appAgent, config = limitConfig)

        val task = AgentTask(
            originalCommand = "Too many steps",
            normalizedGoal = "Too many steps",
            steps = mutableListOf(
                AgentStep(action = AppAction.InspectUi, description = "1"),
                AgentStep(action = AppAction.InspectUi, description = "2"),
                AgentStep(action = AppAction.InspectUi, description = "3"),
                AgentStep(action = AppAction.InspectUi, description = "4") // Exceeds limit
            )
        )

        val result = limitedAgent.executeTask(task)

        assertEquals(AgentTaskState.FAILED, result.state)
        assertTrue(result.failureReason?.contains("maximum step limit") == true)
    }

    // 9. Cooperative User Cancellation at Safe Boundary
    @Test
    fun testTaskCancellation() = runBlocking {
        val slowBridge = object : FakeAccessibilityBridge() {
            override suspend fun getVisibleElements(): List<UiElementInfo> {
                delay(100L)
                return listOf(UiElementInfo(text = "Item"))
            }
        }
        val slowAgent = MultiStepAgent(appAgent = AppAgent(bridge = slowBridge))
        val task = AgentTask(
            originalCommand = "Cancelable task",
            normalizedGoal = "Cancelable task",
            steps = mutableListOf(
                AgentStep(action = AppAction.InspectUi, description = "Step 1"),
                AgentStep(action = AppAction.InspectUi, description = "Step 2")
            )
        )

        val executionJob = launch {
            slowAgent.executeTask(task)
        }

        delay(30)
        slowAgent.cancelCurrentTask("User pressed Stop")
        executionJob.join()

        assertEquals(AgentTaskState.CANCELLED, slowAgent.taskState.value)
        assertEquals(AgentTaskState.CANCELLED, task.state)
    }

    // 10. Safety Policy: Medium Risk Requires Confirmation
    @Test
    fun testMediumRiskRequiresConfirmation() = runBlocking {
        val task = AgentTask(
            originalCommand = "Post status update",
            normalizedGoal = "Post status update",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Post"), description = "Tap Post")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.WAITING_FOR_CONFIRMATION, result.state)
        assertNotNull(result.confirmationRequest)
        assertEquals(RiskLevel.MEDIUM, result.confirmationRequest?.riskLevel)
    }

    // 11. Safety Policy: High Risk Blocked Completely
    @Test
    fun testHighRiskBlocked() = runBlocking {
        val task = AgentTask(
            originalCommand = "Enter CVV pin",
            normalizedGoal = "Enter CVV pin",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("CVV"), description = "Tap CVV")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.BLOCKED, result.state)
        assertTrue(result.failureReason?.contains("blocked", ignoreCase = true) == true)
    }

    // 12. Dynamic Targeting: Ambiguous Target Requests Clarification (No Guessing)
    @Test
    fun testAmbiguousTargetRequiresClarification() = runBlocking {
        // Two distinct settings options match "Settings" query partially
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "App Settings", isClickable = true),
            UiElementInfo(text = "Network Settings", isClickable = true)
        )

        val task = AgentTask(
            originalCommand = "Tap Settings",
            normalizedGoal = "Tap Settings",
            steps = mutableListOf(
                AgentStep(action = AppAction.Tap("Settings"), description = "Tap Settings")
            )
        )

        val result = multiStepAgent.executeTask(task)

        assertEquals(AgentTaskState.PAUSED, result.state)
        assertTrue(result.failureReason?.contains("clarify", ignoreCase = true) == true)
    }

    // 13. Multilingual Planning: English Multi-step Intent
    @Test
    fun testEnglishMultiStepPlanning() {
        val planner = AppPlanner()
        val task = planner.createTask("Open YouTube and tap Search")

        assertNotNull(task)
        assertEquals("Open YouTube and tap Search", task!!.normalizedGoal)
        assertEquals(4, task.totalSteps)
        assertTrue(task.steps[0].action is AppAction.OpenApp)
        assertTrue(task.steps[1].action is AppAction.WaitForWindow)
        assertTrue(task.steps[2].action is AppAction.InspectUi)
        assertTrue(task.steps[3].action is AppAction.Tap)
        assertEquals("Search", (task.steps[3].action as AppAction.Tap).targetQuery)
    }

    // 14. Multilingual Planning: Tanglish Multi-step Intent
    @Test
    fun testTanglishMultiStepPlanning() {
        val planner = AppPlanner()
        val task = planner.createTask("YouTube open panni Search button-a tap pannu")

        assertNotNull(task)
        assertEquals("Open YouTube and tap Search", task!!.normalizedGoal)
        assertEquals(4, task.totalSteps)
        assertTrue(task.steps[0].action is AppAction.OpenApp)
        assertEquals("YouTube", (task.steps[0].action as AppAction.OpenApp).appName)
        assertTrue(task.steps[3].action is AppAction.Tap)
        assertEquals("Search", (task.steps[3].action as AppAction.Tap).targetQuery)
    }

    // 15. Multilingual Planning: Tamil / Tanglish Screen Inspection
    @Test
    fun testTamilMultiStepPlanning() {
        val planner = AppPlanner()
        val task = planner.createTask("YouTube open panni screen-la enna irukku sollu")

        assertNotNull(task)
        assertEquals("Open YouTube and inspect screen", task!!.normalizedGoal)
        assertEquals(3, task.totalSteps)
        assertTrue(task.steps[0].action is AppAction.OpenApp)
        assertTrue(task.steps[2].action is AppAction.InspectUi)
    }

    // 16. CommandProcessor Integration & Cancellation Voice Routing
    @Test
    fun testCommandProcessorCancellationFlow() = runBlocking {
        // Set an active task
        val task = AgentTask(
            originalCommand = "test",
            normalizedGoal = "test",
            steps = mutableListOf(AgentStep(action = AppAction.InspectUi, description = "inspect"))
        )
        task.state = AgentTaskState.EXECUTING

        // Test English Stop
        val result1 = processor.processCommand("Stop")
        assertEquals("MultiStepAgent", result1.providerName)
        assertTrue(result1.isSuccess)

        // Test Tanglish Stop
        val result2 = processor.processCommand("Stop pannu")
        assertEquals("MultiStepAgent", result2.providerName)
        assertTrue(result2.isSuccess)
    }
}


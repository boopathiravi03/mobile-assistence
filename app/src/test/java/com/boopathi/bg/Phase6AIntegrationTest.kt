package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.agent.AgentExecutionPolicy
import com.boopathi.bg.service.agent.AgentTaskState
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.MultiStepAgent
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.messaging.MessagingTargetResolver
import com.boopathi.bg.service.notification.InMemoryNotificationRepository
import com.boopathi.bg.service.notification.NotificationAccessManager
import com.boopathi.bg.service.planner.AgentPlanExecutor
import com.boopathi.bg.service.planner.AgentPlanRisk
import com.boopathi.bg.service.planner.AgentPlanStatus
import com.boopathi.bg.service.planner.AgentPlanner
import com.boopathi.bg.service.security.DefaultOwnerVerificationService
import com.boopathi.bg.service.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
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
class Phase6AIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var appAgent: AppAgent
    private lateinit var multiStepAgent: MultiStepAgent
    private lateinit var notifRepo: InMemoryNotificationRepository
    private lateinit var notifManager: NotificationAccessManager
    private lateinit var targetResolver: MessagingTargetResolver
    private lateinit var agentPlanner: AgentPlanner
    private lateinit var agentPlanExecutor: AgentPlanExecutor
    private lateinit var processor: CommandProcessor
    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    private class MockAIProvider(
        override val name: String = "MockAI",
        override val isAvailable: Boolean = true,
        var responseText: String = ""
    ) : AIProvider {
        override suspend fun processCommand(
            prompt: String,
            owner: OwnerProfile,
            conversationHistory: List<ChatMessage>,
            relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
        ): CommandResult {
            return CommandResult(
                textResponse = responseText,
                providerName = name,
                isSuccess = responseText.isNotBlank()
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository)

        bridge = FakeAccessibilityBridge(currentPkg = "com.google.android.youtube")
        appAgent = AppAgent(bridge = bridge)
        multiStepAgent = MultiStepAgent(appAgent = appAgent)

        notifRepo = InMemoryNotificationRepository(maxCapacity = 50)
        notifManager = NotificationAccessManager(context, repository = notifRepo)
        targetResolver = MessagingTargetResolver()

        agentPlanner = AgentPlanner()
        agentPlanExecutor = AgentPlanExecutor(
            appAgent = appAgent,
            multiStepAgent = multiStepAgent,
            notificationAccessManager = notifManager
        )

        processor = CommandProcessor(
            memoryManager = memoryManager,
            appAgent = appAgent,
            multiStepAgent = multiStepAgent,
            notificationAccessManager = notifManager,
            messagingTargetResolver = targetResolver,
            agentPlanner = agentPlanner,
            agentPlanExecutor = agentPlanExecutor
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Natural Language -> Plan -> MultiStep Execution pipeline
    @Test
    fun testNaturalLanguageToExecutionPipeline() = runBlocking {
        // Mock UI elements
        bridge.mockElements = mutableListOf(
            com.boopathi.bg.service.agent.UiElementInfo(text = "Library", isClickable = true, isEnabled = true)
        )

        val plan = agentPlanner.planGoal("Open YouTube and tap Library", owner)
        assertNotNull(plan)
        assertEquals(AgentPlanStatus.VALIDATED, plan.status)
        assertEquals(4, plan.steps.size)

        val executionResult = agentPlanExecutor.executePlan(plan, owner)
        assertNotNull(executionResult)
        assertEquals("AgentPlanExecutor", executionResult.providerName)
        assertTrue(executionResult.isSuccess)
    }

    // 2. Sensitive plan rejection before execution
    @Test
    fun testSensitivePlanRejectedBeforeExecution() = runBlocking {
        val mockAi = MockAIProvider(
            responseText = """
            {
              "goal": {
                "normalizedGoal": "Read confidential OTP",
                "category": "APP_INSPECTION",
                "confidence": 0.99
              },
              "explanation": "Reading OTP",
              "steps": [
                {
                  "stepId": "s1",
                  "action": "TAP",
                  "target": "Copy OTP",
                  "description": "Copying OTP"
                }
              ]
            }
            """.trimIndent()
        )

        val customPlanner = AgentPlanner(aiProvider = mockAi)
        val plan = customPlanner.planGoal("Read my bank OTP", owner)

        assertEquals(AgentPlanStatus.REJECTED, plan.status)

        // Attempting to execute rejected plan returns failure immediately
        val executionResult = agentPlanExecutor.executePlan(plan, owner)
        assertFalse(executionResult.isSuccess)
        assertEquals("AgentPlanValidator", executionResult.providerName)
    }

    // 3. CommandProcessor end-to-end integration preserves deterministic commands
    @Test
    fun testCommandProcessorPreservesDeterministicCommands() = runBlocking {
        // Test memory persistence
        val rememberResult = processor.processCommand("Remember that my pet is named Bruno")
        assertTrue(rememberResult.isSuccess)
        assertEquals("Memory", rememberResult.providerName)

        val recallResult = processor.processCommand("What is my pet called?")
        assertTrue(recallResult.isSuccess)
        assertTrue(recallResult.textResponse.contains("Bruno"))

        // Test cancel command
        val cancelResult = processor.processCommand("Stop")
        assertTrue(cancelResult.isSuccess)
        assertEquals("MultiStepAgent", cancelResult.providerName)
    }

    // 4. Execution handoff properly converts to AgentTask
    @Test
    fun testExecutionHandoffConvertsPlanToTask() = runBlocking {
        val plan = agentPlanner.planGoal("Open Settings and tap Display", owner)
        assertNotNull(plan)

        val task = agentPlanExecutor.convertPlanToAgentTask(plan)
        assertEquals("Open Settings and tap Display", task.normalizedGoal)
        assertEquals(4, task.totalSteps)
        assertEquals("Settings", (task.steps[0].action as AppAction.OpenApp).appName)
    }
}


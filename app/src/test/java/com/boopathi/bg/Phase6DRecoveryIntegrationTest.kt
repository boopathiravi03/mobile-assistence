package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResult
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.workflow.CrossAppHandoffVerifier
import com.boopathi.bg.service.workflow.CrossAppWorkflow
import com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor
import com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.CoroutineScope
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
import java.util.UUID

/**
 * Phase 6D Integration Tests:
 * - End-to-end command routing for history and resumption
 * - Interruption recovery simulation
 * - Transient payload clearing on cancellation and completion
 */
@RunWith(AndroidJUnit4::class)
class Phase6DRecoveryIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var appAgent: AppAgent
    private lateinit var executor: CrossAppWorkflowExecutor
    private lateinit var processor: CommandProcessor
    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository, CoroutineScope(Dispatchers.Unconfined))
        bridge = FakeAccessibilityBridge()
        appAgent = AppAgent(bridge)
        executor = CrossAppWorkflowExecutor(
            appAgent = appAgent,
            repository = repository
        )
        processor = CommandProcessor(
            memoryManager = memoryManager,
            appAgent = appAgent,
            crossAppWorkflowExecutor = executor,
            repository = repository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun test8_runningWorkflowBecomesInterruptedAfterSimulatedProcessDeath() = runBlocking {
        // 1. Simulate workflow executing before abrupt process death
        val wfId = UUID.randomUUID().toString()
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = wfId,
                goalDescription = "Open Settings and YouTube",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.EXECUTING.name,
                currentStepIndex = 1,
                totalSteps = 2,
                isEligibleForResume = false
            )
        )

        // 2. Simulate process startup recovery
        val interruptedCount = repository.markActiveWorkflowsAsInterrupted()
        assertEquals(1, interruptedCount)

        val recovered = repository.getWorkflowHistoryById(wfId)
        assertNotNull(recovered)
        assertEquals(CrossAppWorkflowStatus.INTERRUPTED, recovered?.typedStatus)
        assertTrue(recovered?.isEligibleForResume == true)
    }

    @Test
    fun test34_workflowHistoryCommandsRoutedCorrectly_EnglishAndTamil() = runBlocking {
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "wf_prev_1",
                goalDescription = "Put note in Keep",
                workflowType = WorkflowType.TRANSFER_USER_PROVIDED_TEXT.name,
                status = CrossAppWorkflowStatus.SUCCESS.name,
                currentStepIndex = 2,
                totalSteps = 2
            )
        )

        // Test English "Show my recent tasks"
        val enRes = processor.processCommand("Show my recent tasks")
        assertTrue(enRes.isSuccess)
        assertEquals("WorkflowHistory", enRes.providerName)
        assertTrue(enRes.textResponse.contains("Put note in Keep"))

        // Test English "What happened to my last task?"
        val lastRes = processor.processCommand("What happened to my last task?")
        assertTrue(lastRes.isSuccess)
        assertTrue(lastRes.textResponse.contains("completed successfully"))

        // Test Tamil "kadasi task ennachu"
        val taOwner = memoryManager.getOwnerProfile()
        memoryManager.updateOwnerProfile(taOwner.copy(preferredLanguage = "ta"))
        val taRes = processor.processCommand("kadasi task ennachu")
        assertTrue(taRes.isSuccess)
        assertTrue(taRes.textResponse.contains("Kadasi task"))
    }

    @Test
    fun test35_viewingHistoryNeverExecutesAWorkflow() = runBlocking {
        val initialHistoryCount = repository.getRecentWorkflowHistories().size
        processor.processCommand("Show my recent tasks")

        // Assert no active workflow was triggered
        assertFalse(executor.currentWorkflow.value?.status == CrossAppWorkflowStatus.EXECUTING)
        assertEquals(initialHistoryCount, repository.getRecentWorkflowHistories().size)
    }

    @Test
    fun test37_ambiguousResumeRequestsTriggerClarification() = runBlocking {
        // Insert 2 interrupted workflows
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "wf_1",
                goalDescription = "First interrupted task",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                isEligibleForResume = true
            )
        )
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "wf_2",
                goalDescription = "Second interrupted task",
                workflowType = WorkflowType.TRANSFER_USER_PROVIDED_TEXT.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                isEligibleForResume = true
            )
        )

        val resumeRes = processor.processCommand("Resume the interrupted workflow")
        assertTrue(resumeRes.isSuccess)
        assertTrue(resumeRes.textResponse.contains("Multiple interrupted tasks found"))
    }

    @Test
    fun test21_cancellationIsHonoredImmediatelyAtSafeBoundary() = runBlocking {
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "wf_cancel_test",
                goalDescription = "Inspect multi apps",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                isEligibleForResume = true
            )
        )

        val cancelResult = processor.processCommand("Cancel the previous workflow")
        assertTrue(cancelResult.isSuccess)

        val saved = repository.getWorkflowHistoryById("wf_cancel_test")
        assertEquals(CrossAppWorkflowStatus.CANCELLED, saved?.typedStatus)
        assertFalse(saved?.isEligibleForResume == true)
    }
}


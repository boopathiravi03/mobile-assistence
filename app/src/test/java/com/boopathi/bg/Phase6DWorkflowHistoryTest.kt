package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.WorkflowCheckpoint
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Phase 6D Unit Tests: Workflow History Persistence & Retention.
 */
@RunWith(AndroidJUnit4::class)
class Phase6DWorkflowHistoryTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun test1_workflowSummaryPersistsAcrossRepositoryRecreation() = runBlocking {
        val wfId = UUID.randomUUID().toString()
        val entity = WorkflowHistoryEntity(
            workflowId = wfId,
            goalDescription = "Open Settings then open YouTube",
            workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
            status = CrossAppWorkflowStatus.SUCCESS.name,
            currentStepIndex = 2,
            totalSteps = 2,
            createdAt = System.currentTimeMillis() - 1000,
            updatedAt = System.currentTimeMillis()
        )

        repository.saveWorkflowHistory(entity)

        // Create new repository instance pointing to same database
        val newRepo = RoomBGRepository(db, Dispatchers.Unconfined)
        val fetched = newRepo.getWorkflowHistoryById(wfId)

        assertNotNull("Workflow summary must persist across repository recreation", fetched)
        assertEquals("Open Settings then open YouTube", fetched?.goalDescription)
        assertEquals(CrossAppWorkflowStatus.SUCCESS, fetched?.typedStatus)
    }

    @Test
    fun test2_workflowStatusPersistsCorrectly() = runBlocking {
        val wfId = UUID.randomUUID().toString()
        val entity = WorkflowHistoryEntity(
            workflowId = wfId,
            goalDescription = "Put meeting note in keep",
            workflowType = WorkflowType.TRANSFER_USER_PROVIDED_TEXT.name,
            status = CrossAppWorkflowStatus.INTERRUPTED.name,
            currentStepIndex = 1,
            totalSteps = 2,
            isEligibleForResume = true
        )

        repository.saveWorkflowHistory(entity)
        val fetched = repository.getWorkflowHistoryById(wfId)

        assertNotNull(fetched)
        assertEquals(CrossAppWorkflowStatus.INTERRUPTED, fetched?.typedStatus)
        assertTrue(fetched?.isEligibleForResume == true)
    }

    @Test
    fun test3_latestWorkflowCanBeRetrieved() = runBlocking {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = id1,
                goalDescription = "First task",
                workflowType = WorkflowType.OPEN_AND_INSPECT_DESTINATION.name,
                status = CrossAppWorkflowStatus.SUCCESS.name,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = id2,
                goalDescription = "Second task",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                createdAt = 2000L,
                updatedAt = 2000L
            )
        )

        val latest = repository.getLatestWorkflowHistory()
        assertNotNull(latest)
        assertEquals(id2, latest?.workflowId)
        assertEquals("Second task", latest?.goalDescription)
    }

    @Test
    fun test4_historyOrderingIsCorrect() = runBlocking {
        for (i in 1..5) {
            repository.saveWorkflowHistory(
                WorkflowHistoryEntity(
                    workflowId = "wf_$i",
                    goalDescription = "Task $i",
                    workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                    status = CrossAppWorkflowStatus.SUCCESS.name,
                    createdAt = i * 1000L,
                    updatedAt = i * 1000L
                )
            )
        }

        val list = repository.getRecentWorkflowHistories(3)
        assertEquals(3, list.size)
        assertEquals("wf_5", list[0].workflowId)
        assertEquals("wf_4", list[1].workflowId)
        assertEquals("wf_3", list[2].workflowId)
    }

    @Test
    fun test5_historyLimitIsEnforced() = runBlocking {
        // Insert 105 entities
        for (i in 1..105) {
            repository.saveWorkflowHistory(
                WorkflowHistoryEntity(
                    workflowId = "wf_limit_$i",
                    goalDescription = "Task $i",
                    workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                    status = CrossAppWorkflowStatus.SUCCESS.name,
                    createdAt = i * 10L,
                    updatedAt = i * 10L
                )
            )
        }

        val count = db.workflowHistoryDao().getCount()
        assertTrue("History records must be capped to maximum 100 (actual: $count)", count <= 100)
    }

    @Test
    fun test6_expiredRecordsAreCleanedUp() = runBlocking {
        val now = System.currentTimeMillis()
        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "expired_wf",
                goalDescription = "Old task",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                expiresAt = now - 5000L // Already expired
            )
        )

        repository.saveWorkflowHistory(
            WorkflowHistoryEntity(
                workflowId = "valid_wf",
                goalDescription = "Recent task",
                workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
                status = CrossAppWorkflowStatus.INTERRUPTED.name,
                expiresAt = now + 50000L
            )
        )

        repository.markExpiredWorkflows()
        val expiredWf = repository.getWorkflowHistoryById("expired_wf")
        assertEquals(CrossAppWorkflowStatus.EXPIRED, expiredWf?.typedStatus)
        assertFalse(expiredWf?.isEligibleForResume == true)
    }
}


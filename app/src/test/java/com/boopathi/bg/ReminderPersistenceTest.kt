package com.boopathi.bg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.TaskStatus
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.reminder.ReminderManager
import com.boopathi.bg.service.reminder.ReminderPlatformScheduler
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.local.entity.TaskType
import com.boopathi.bg.service.reminder.ReminderRecurrence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class ReminderPersistenceTest {
    @Test fun createCancelAndCompleteUsePersistedLifecycle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BGDatabase.createInMemory(context)
        try {
            val scheduler = FakeReminderScheduler()
            val manager = ReminderManager(context, RoomBGRepository(database), fixedClock(), scheduler)
            val created = manager.createFromText("Remind me to study in 20 minutes")
            assertTrue(created.message, created.taskId != null)
            val saved = database.taskDao().getById(created.taskId!!)!!
            assertEquals("study", saved.title)
            assertEquals(TaskStatus.SCHEDULED, saved.status)
            assertNotNull(saved.timezoneId)
            assertTrue(saved.id in scheduler.activeIds)

            val cancelled = manager.cancel(saved.id)
            assertTrue(cancelled.success)
            assertEquals(TaskStatus.CANCELLED, database.taskDao().getById(saved.id)!!.status)
            assertTrue(saved.id !in scheduler.activeIds)

            val other = manager.createFromText("Remind me to read in 30 minutes")
            assertTrue(other.success)
            assertTrue(manager.complete(other.taskId!!).success)
            assertEquals(TaskStatus.COMPLETED, database.taskDao().getById(other.taskId)!!.status)
        } finally {
            database.close()
        }
    }

    @Test fun recurringOccurrenceAdvancesExactlyOnceAndSnoozeUsesSeparateTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BGDatabase.createInMemory(context)
        try {
            val scheduler = FakeReminderScheduler()
            val manager = ReminderManager(context, RoomBGRepository(database), fixedClock(), scheduler)
            val created = manager.createFromText("Remind me to review goals every Monday at 8 AM")
            val task = database.taskDao().getById(created.taskId!!)!!
            val next = ReminderRecurrence.next(task.recurrenceRule, task.dueTime!!, task.timezoneId, task.dueTime + 1)!!
            assertEquals(1, database.taskDao().claimOccurrenceWithResult(task.id, task.dueTime, TaskStatus.SCHEDULED, false, next, task.id.hashCode(), null))
            assertEquals(0, database.taskDao().claimOccurrenceWithResult(task.id, task.dueTime, TaskStatus.SCHEDULED, false, next, task.id.hashCode(), null))
            val advanced = database.taskDao().getById(task.id)!!
            assertEquals(task.dueTime, advanced.lastOccurrenceAt)
            assertEquals(next, advanced.nextOccurrenceAt)

            // A delivered one-time occurrence can be snoozed without overwriting its original due time.
            val oneTime = manager.createFromText("Remind me to read in 30 minutes")
            val once = database.taskDao().getById(oneTime.taskId!!)!!
            assertEquals(1, database.taskDao().claimOccurrenceWithResult(once.id, once.dueTime!!, TaskStatus.DELIVERED, false, null, once.id.hashCode(), null))
            assertTrue(manager.snooze(once.id).success)
            val snoozed = database.taskDao().getById(once.id)!!
            assertEquals(once.dueTime, snoozed.dueTime)
            assertNotNull(snoozed.snoozedUntil)
            assertTrue(snoozed.snoozedUntil != once.dueTime)
        } finally {
            database.close()
        }
    }

    @Test fun scheduledWorkflowRequestIsNotificationOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BGDatabase.createInMemory(context)
        try {
            val repository = RoomBGRepository(database)
            val manager = ReminderManager(context, repository, fixedClock(), FakeReminderScheduler())
            val response = manager.handleCommand("Schedule workflow request to open Calendar at 5 PM")!!
            assertTrue(response.contains("no phone action will run automatically"))
            val request = repository.getReminderTasks().single()
            assertEquals(TaskType.WORKFLOW_REQUEST, request.taskType)
        } finally {
            database.close()
        }
    }

    private fun fixedClock(): Clock {
        val zone = ZoneId.of("Asia/Kolkata")
        return Clock.fixed(ZonedDateTime.of(2030, 1, 1, 12, 0, 0, 0, zone).toInstant(), zone)
    }

    private class FakeReminderScheduler : ReminderPlatformScheduler {
        val activeIds = mutableSetOf<Long>()
        override fun schedule(task: TaskEntity): Boolean { activeIds += task.id; return true }
        override fun cancel(taskId: Long) { activeIds -= taskId }
    }
}

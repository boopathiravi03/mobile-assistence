package com.boopathi.bg.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.TaskStatus
import com.boopathi.bg.data.repository.RoomBGRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = BGDatabase.getInstance(app)
                val repository = RoomBGRepository(database)
                val scheduler = ReminderAlarmScheduler(app)
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderManager(app, repository).reconcile()
                    ACTION_DELIVER -> deliver(app, repository, scheduler, intent)
                    ACTION_COMPLETE -> intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }?.let {
                        ReminderManager(app, repository).complete(it)
                    }
                    ACTION_SNOOZE -> intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }?.let {
                        ReminderManager(app, repository).snooze(it)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Reminder broadcast failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun deliver(
        context: Context,
        repository: RoomBGRepository,
        scheduler: ReminderAlarmScheduler,
        intent: Intent
    ) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: ReminderAlarmScheduler.KIND_OCCURRENCE
        val task = repository.getTask(id) ?: return
        if (task.status != TaskStatus.SCHEDULED || task.completed) return
        val now = System.currentTimeMillis()
        val notificationPermission = ReminderNotifications.hasPermission(context)
        val stableNotificationId = task.notificationId ?: id.hashCode()

        if (kind == ReminderAlarmScheduler.KIND_SNOOZE) {
            val snoozeAt = task.snoozedUntil ?: return
            val nextStatus = when {
                task.recurrenceRule != null -> TaskStatus.SCHEDULED
                notificationPermission -> TaskStatus.DELIVERED
                else -> TaskStatus.FAILED
            }
            val failure = if (notificationPermission) null else "Notification permission is disabled; BG could not show this reminder."
            if (repository.claimSnooze(id, snoozeAt, nextStatus, failure) == 0) return
            val updated = repository.getTask(id) ?: task
            if (notificationPermission && !ReminderNotifications.show(context, updated.copy(notificationId = stableNotificationId))) {
                recordNotificationFailure(repository, updated.id, updated.recurrenceRule != null)
            }
            return
        }

        val occurrence = task.nextOccurrenceAt ?: task.dueTime ?: return
        if (occurrence != intent.getLongExtra(EXTRA_OCCURRENCE_AT, occurrence)) return
        if (task.recurrenceRule == null && now - occurrence > MISSED_WINDOW_MS) {
            repository.updateTaskStatus(id, TaskStatus.FAILED, false, "Reminder was missed while Android delayed delivery.")
            return
        }
        val nextOccurrence = ReminderRecurrence.next(task.recurrenceRule, occurrence, task.timezoneId, now)
        val nextStatus = when {
            nextOccurrence != null -> TaskStatus.SCHEDULED
            notificationPermission -> TaskStatus.DELIVERED
            else -> TaskStatus.FAILED
        }
        val failure = if (notificationPermission) null else "Notification permission is disabled; BG could not show this reminder."
        val claimed = repository.claimOccurrence(id, occurrence, nextStatus, false, nextOccurrence, stableNotificationId, failure)
        if (claimed == 0) return
        val updated = repository.getTask(id) ?: return
        if (notificationPermission && !ReminderNotifications.show(context, updated)) {
            recordNotificationFailure(repository, updated.id, updated.recurrenceRule != null)
        }
        if (nextOccurrence != null && updated.status == TaskStatus.SCHEDULED && !scheduler.schedule(updated)) {
            repository.setTaskFailure(id, "Android could not register the next recurring reminder alarm.")
        }
    }

    private suspend fun recordNotificationFailure(repository: RoomBGRepository, id: Long, recurring: Boolean) {
        val reason = "Android did not display the reminder notification. Check BG's notification channel settings."
        if (recurring) repository.setTaskFailure(id, reason)
        else repository.updateTaskStatus(id, TaskStatus.FAILED, false, reason)
    }

    companion object {
        const val ACTION_DELIVER = "com.boopathi.bg.reminder.DELIVER"
        const val ACTION_COMPLETE = "com.boopathi.bg.reminder.COMPLETE"
        const val ACTION_SNOOZE = "com.boopathi.bg.reminder.SNOOZE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_KIND = "alarm_kind"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
        private const val MISSED_WINDOW_MS = 12 * 60 * 60 * 1000L
        private const val TAG = "ReminderAlarmReceiver"
    }
}

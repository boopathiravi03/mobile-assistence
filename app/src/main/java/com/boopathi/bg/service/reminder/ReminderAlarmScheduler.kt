package com.boopathi.bg.service.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.boopathi.bg.data.local.entity.TaskEntity

interface ReminderPlatformScheduler {
    fun schedule(task: TaskEntity): Boolean
    fun cancel(taskId: Long)
}

/** Wall-clock, inexact alarms are used so exact-alarm special access is never requested. */
class ReminderAlarmScheduler(private val context: Context) : ReminderPlatformScheduler {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(task: TaskEntity): Boolean {
        val snooze = task.snoozedUntil
        val whenMillis = snooze ?: task.nextOccurrenceAt ?: task.dueTime ?: return false
        val kind = if (snooze != null) KIND_SNOOZE else KIND_OCCURRENCE
        val operation = pendingIntent(task.id, kind, PendingIntent.FLAG_UPDATE_CURRENT, whenMillis) ?: return false
        return runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(whenMillis, System.currentTimeMillis()), operation)
            true
        }.getOrDefault(false)
    }

    override fun cancel(taskId: Long) {
        listOf(KIND_OCCURRENCE, KIND_SNOOZE).forEach { kind ->
            pendingIntent(taskId, kind, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it); it.cancel() }
        }
    }

    private fun pendingIntent(taskId: Long, kind: String, flag: Int, occurrenceAt: Long = 0L): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER
            data = Uri.parse("bg-reminder://task/$taskId/$kind")
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderAlarmReceiver.EXTRA_KIND, kind)
            putExtra(ReminderAlarmReceiver.EXTRA_OCCURRENCE_AT, occurrenceAt)
        }
        val flags = flag or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }

    companion object {
        const val KIND_OCCURRENCE = "occurrence"
        const val KIND_SNOOZE = "snooze"
    }
}

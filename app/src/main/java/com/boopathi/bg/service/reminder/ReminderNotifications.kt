package com.boopathi.bg.service.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.boopathi.bg.R
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.ui.MainActivity

object ReminderNotifications {
    const val CHANNEL_ID = "bg_reminders"
    private const val CHANNEL_NAME = "Reminders"

    fun hasPermission(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scheduled reminders from BG"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
    }

    @SuppressLint("MissingPermission") // hasPermission checks app-level notification access and POST_NOTIFICATIONS before notify.
    fun show(context: Context, task: TaskEntity): Boolean {
        if (!hasPermission(context)) return false
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_reminders", true)
        }
        val open = PendingIntent.getActivity(context, requestCode(task.id, "open"), openIntent, immutableFlags())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(task.title)
            .setContentText("Your BG reminder is due")
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.title))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Complete", action(context, task.id, ReminderAlarmReceiver.ACTION_COMPLETE))
            .addAction(0, "Snooze 10 min", action(context, task.id, ReminderAlarmReceiver.ACTION_SNOOZE))
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(task.notificationId ?: task.id.hashCode(), notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun action(context: Context, id: Long, action: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("bg-reminder://action/$id/${action.lowercase()}")
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, id)
        }
        return PendingIntent.getBroadcast(context, requestCode(id, action), intent, immutableFlags())
    }

    private fun requestCode(id: Long, action: String) = 31 * id.hashCode() + action.hashCode()
    private fun immutableFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
}

package com.boopathi.bg.service.reminder

import android.content.Context
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.local.entity.TaskStatus
import com.boopathi.bg.data.local.entity.TaskType
import com.boopathi.bg.data.repository.BGRepository
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReminderOperationResult(val success: Boolean, val message: String, val taskId: Long? = null)

class ReminderManager(
    context: Context,
    private val repository: BGRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val scheduler: ReminderPlatformScheduler = ReminderAlarmScheduler(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val parser = ReminderParser(clock, clock.zone)
    val reminderTasks: Flow<List<TaskEntity>> = repository.observeReminderTasks()

    init { ReminderNotifications.ensureChannel(appContext) }

    suspend fun createFromText(text: String): ReminderOperationResult = when (val parsed = parser.parse(text)) {
        is ReminderParseResult.Clarification -> ReminderOperationResult(false, parsed.question)
        is ReminderParseResult.Rejected -> ReminderOperationResult(false, parsed.reason)
        is ReminderParseResult.Ready -> {
            val now = clock.millis()
            val due = parsed.reminder.scheduledAt.toInstant().toEpochMilli()
            val task = repository.insertScheduledTask(
                TaskEntity(
                    title = parsed.reminder.title,
                    description = "",
                    dueTime = due,
                    completed = false,
                    createdAt = now,
                    taskType = parsed.reminder.taskType,
                    status = TaskStatus.PENDING,
                    timezoneId = parsed.reminder.scheduledAt.zone.id,
                    recurrenceRule = parsed.reminder.recurrenceRule,
                    nextOccurrenceAt = due,
                    notificationId = null,
                    updatedAt = now
                )
            )
            register(task)
            val actual = repository.getTask(task.id) ?: task
            val whenText = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' h:mm a z").format(parsed.reminder.scheduledAt)
            val recurrenceText = parsed.reminder.recurrenceRule?.let { " Repeats weekly on ${it.substringAfter(':').lowercase()}." }.orEmpty()
            ReminderOperationResult(actual.status == TaskStatus.SCHEDULED,
                if (actual.status == TaskStatus.SCHEDULED) "Reminder scheduled for $whenText.$recurrenceText Android may deliver it later than the requested time.${actual.failureReason?.let { " $it" }.orEmpty()}" else "Reminder saved, but Android could not register its alarm. ${actual.failureReason.orEmpty()}", task.id)
        }
    }

    private suspend fun register(task: TaskEntity) {
        val registered = runCatching { scheduler.schedule(task.copy(status = TaskStatus.SCHEDULED)) }.getOrDefault(false)
        repository.updateTaskStatus(
            task.id,
            if (registered) TaskStatus.SCHEDULED else TaskStatus.FAILED,
            completed = false,
            failureReason = if (registered) null else "Android could not register this reminder alarm."
        )
        if (registered && !ReminderNotifications.hasPermission(appContext)) {
            repository.setTaskFailure(task.id, "Notifications are disabled. Enable them before this reminder is due.")
        }
    }

    suspend fun reconcile(): Int {
        var repaired = 0
        val now = clock.millis()
        for (task in repository.getReminderTasks()) {
            if (task.status !in setOf(TaskStatus.SCHEDULED, TaskStatus.PENDING)) {
                scheduler.cancel(task.id)
                continue
            }
            val trigger = task.snoozedUntil ?: task.nextOccurrenceAt ?: task.dueTime ?: continue
            if (task.expiresAt != null && task.expiresAt < now) {
                repository.updateTaskStatus(task.id, TaskStatus.FAILED, false, "Reminder expired before it could be delivered.")
                scheduler.cancel(task.id)
                continue
            }
            if (trigger < now - MAX_CATCH_UP_AGE_MS && task.snoozedUntil == null && task.recurrenceRule == null) {
                repository.updateTaskStatus(task.id, TaskStatus.FAILED, false, "Reminder was missed while BG was unavailable.")
                scheduler.cancel(task.id)
                continue
            }
            // Repair interrupted create operations and re-register alarms lost by Android after reboot.
            if (task.status == TaskStatus.PENDING) {
                register(task)
                repaired++
            } else if (scheduler.schedule(task)) repaired++
        }
        return repaired
    }

    suspend fun retry(id: Long): ReminderOperationResult {
        if (!ReminderNotifications.hasPermission(appContext)) return ReminderOperationResult(false, "Enable BG reminder notifications in Android Settings, then retry.", id)
        val task = repository.getTask(id) ?: return ReminderOperationResult(false, "That reminder no longer exists.")
        if (task.completed || task.status == TaskStatus.CANCELLED) return ReminderOperationResult(false, "That reminder is no longer active.")
        register(task.copy(status = TaskStatus.PENDING, snoozedUntil = null))
        val updated = repository.getTask(id)
        return ReminderOperationResult(updated?.status == TaskStatus.SCHEDULED, updated?.failureReason ?: "Reminder schedule restored.", id)
    }

    suspend fun reschedule(id: Long, phrase: String): ReminderOperationResult {
        val current = repository.getTask(id) ?: return ReminderOperationResult(false, "That reminder no longer exists.")
        if (current.completed || current.status == TaskStatus.CANCELLED) return ReminderOperationResult(false, "That reminder is no longer active.")
        val parsed = when (val result = parser.parse(phrase)) {
            is ReminderParseResult.Ready -> result.reminder
            is ReminderParseResult.Clarification -> return ReminderOperationResult(false, result.question)
            is ReminderParseResult.Rejected -> return ReminderOperationResult(false, result.reason)
        }
        val now = clock.millis()
        val due = parsed.scheduledAt.toInstant().toEpochMilli()
        scheduler.cancel(id)
        val updated = current.copy(
            title = parsed.title,
            taskType = when {
                current.taskType == TaskType.WORKFLOW_REQUEST -> TaskType.WORKFLOW_REQUEST
                parsed.recurrenceRule != null -> TaskType.RECURRING_REMINDER
                else -> TaskType.REMINDER
            },
            dueTime = due,
            timezoneId = parsed.scheduledAt.zone.id,
            recurrenceRule = parsed.recurrenceRule,
            nextOccurrenceAt = due,
            snoozedUntil = null,
            lastOccurrenceAt = null,
            status = TaskStatus.PENDING,
            completed = false,
            failureReason = null,
            updatedAt = now
        )
        if (repository.updateScheduledTask(updated) == 0) {
            scheduler.schedule(current)
            return ReminderOperationResult(false, "Reminder could not be updated; its previous schedule was restored.", id)
        }
        register(updated)
        val actual = repository.getTask(id)
        return ReminderOperationResult(actual?.status == TaskStatus.SCHEDULED,
            actual?.let { "Reminder updated for ${displayTime(it)}${it.recurrenceRule?.let { rule -> "; repeats weekly on ${rule.substringAfter(':').lowercase()}" }.orEmpty()}. Android may deliver it later than the requested time.${it.failureReason?.let { reason -> " $reason" }.orEmpty()}" } ?: "Reminder could not be updated.", id)
    }

    suspend fun cancel(id: Long): ReminderOperationResult {
        val changed = repository.updateTaskStatus(id, TaskStatus.CANCELLED, completed = false, failureReason = null)
        if (changed == 0) return ReminderOperationResult(false, "That reminder cannot be cancelled.")
        scheduler.cancel(id)
        repository.getTask(id)?.notificationId?.let { ReminderNotifications.cancel(appContext, it) }
        return ReminderOperationResult(true, "Reminder cancelled.", id)
    }

    suspend fun complete(id: Long): ReminderOperationResult {
        val changed = repository.updateTaskStatus(id, TaskStatus.COMPLETED, completed = true, failureReason = null)
        if (changed == 0) return ReminderOperationResult(false, "That reminder is no longer active.")
        scheduler.cancel(id)
        repository.getTask(id)?.notificationId?.let { ReminderNotifications.cancel(appContext, it) }
        return ReminderOperationResult(true, "Reminder marked complete.", id)
    }

    suspend fun delete(id: Long): ReminderOperationResult {
        scheduler.cancel(id)
        repository.getTask(id)?.notificationId?.let { ReminderNotifications.cancel(appContext, it) }
        val deleted = repository.deleteTask(id) > 0
        return ReminderOperationResult(deleted, if (deleted) "Reminder deleted." else "That reminder no longer exists.", id)
    }

    suspend fun clearCompleted(): Int = repository.clearCompletedTasks()

    suspend fun snooze(id: Long, minutes: Long = DEFAULT_SNOOZE_MINUTES): ReminderOperationResult {
        val current = repository.getTask(id) ?: return ReminderOperationResult(false, "That reminder no longer exists.")
        val recentRecurringDelivery = current.recurrenceRule != null && current.lastOccurrenceAt != null &&
            clock.millis() - current.lastOccurrenceAt <= RECENT_DELIVERY_WINDOW_MS
        if (current.failureReason != null || (current.status != TaskStatus.DELIVERED && !recentRecurringDelivery)) {
            return ReminderOperationResult(false, "Snooze is available after a reminder notification is delivered.")
        }
        val until = clock.millis() + minutes * 60_000L
        if (repository.setSnooze(id, until) == 0) return ReminderOperationResult(false, "That reminder cannot be snoozed.")
        val task = repository.getTask(id) ?: return ReminderOperationResult(false, "That reminder no longer exists.")
        val scheduled = scheduler.schedule(task)
        if (!scheduled) {
            repository.setTaskFailure(id, "Snooze could not be registered with Android.")
            return ReminderOperationResult(false, "Snooze could not be registered with Android.", id)
        }
        return ReminderOperationResult(true, "Snoozed for $minutes minutes.", id)
    }

    suspend fun handleCommand(text: String): String? {
        val normalized = text.trim().lowercase().trimEnd('.', '!', '?')
        if (normalized in listOf("show my reminders", "show reminders", "list reminders", "my reminders")) {
            val tasks = repository.getReminderTasks().filter { it.status !in setOf(TaskStatus.CANCELLED, TaskStatus.COMPLETED) }
            return if (tasks.isEmpty()) "You don't have any active reminders." else tasks.take(8).joinToString("\n") { task ->
                val status = if (task.status == TaskStatus.DELIVERED) "alert attempted (not proof it was seen)" else task.status
                "• ${task.title} — ${displayTime(task)} ($status)${task.failureReason?.let { "; $it" }.orEmpty()}"
            }
        }
        if (normalized.startsWith("cancel my ") || normalized.startsWith("cancel the ") || normalized.startsWith("cancel reminder")) {
            val keyword = normalized.removePrefix("cancel my ").removePrefix("cancel the ").removePrefix("cancel reminder").trim()
            return target(keyword)?.let { cancel(it.id).message } ?: "I couldn't identify one matching reminder. Say “show my reminders” and use its title."
        }
        if ((normalized.startsWith("mark ") && normalized.contains("complete")) ||
            (normalized.startsWith("complete ") && normalized.contains("reminder"))) {
            val keyword = normalized.removePrefix("mark ").removePrefix("complete ")
                .replace("completed", " ").replace("complete", " ").replace("reminder", " ").trim()
            return target(keyword)?.let { complete(it.id).message } ?: "I couldn't identify one matching reminder."
        }
        if (normalized.startsWith("snooze this reminder")) {
            val minutes = Regex("for\\s+(\\d+)\\s+minutes?").find(normalized)?.groupValues?.get(1)?.toLongOrNull() ?: DEFAULT_SNOOZE_MINUTES
            val target = repository.getReminderTasks().firstOrNull { it.status == TaskStatus.DELIVERED }
                ?: return "There isn't a delivered reminder to snooze."
            return snooze(target.id, minutes.coerceIn(1, 240)).message
        }
        if (normalized.contains("remind me") || normalized.contains("nyabagam pannu") || normalized.contains("நினைவூட்டு") || normalized.contains("schedule workflow")) {
            val result = createFromText(text)
            val task = result.taskId?.let { repository.getTask(it) }
            return if (task?.taskType == TaskType.WORKFLOW_REQUEST && result.success) {
                "${result.message} This is a scheduled workflow request for review; no phone action will run automatically."
            } else result.message
        }
        return null
    }

    private suspend fun target(keyword: String): TaskEntity? {
        val active = repository.getReminderTasks().filter { it.status !in setOf(TaskStatus.CANCELLED, TaskStatus.COMPLETED) }
        val normalized = keyword.replace(Regex("\\b(my|the|reminder)\\b"), " ").trim()
        if (normalized.isBlank()) return active.firstOrNull()
        val matches = active.filter { task ->
            task.title.contains(normalized, ignoreCase = true) || matchesLocalTime(task, normalized)
        }
        return matches.singleOrNull()
    }

    private fun matchesLocalTime(task: TaskEntity, text: String): Boolean {
        val match = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE).find(text) ?: return false
        val millis = task.snoozedUntil ?: task.nextOccurrenceAt ?: task.dueTime ?: return false
        val zone = runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(clock.zone)
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val rawHour = match.groupValues[1].toIntOrNull() ?: return false
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val hour = when (match.groupValues[3].lowercase()) { "pm" -> if (rawHour == 12) 12 else rawHour + 12; else -> if (rawHour == 12) 0 else rawHour }
        return local.hour == hour && local.minute == minute
    }

    private fun displayTime(task: TaskEntity): String {
        val millis = task.snoozedUntil ?: task.nextOccurrenceAt ?: task.dueTime ?: return "time unavailable"
        val zone = runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(clock.zone)
        return DateTimeFormatter.ofPattern("EEE, d MMM h:mm a z").withZone(zone).format(Instant.ofEpochMilli(millis))
    }

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10L
        private const val MAX_CATCH_UP_AGE_MS = 12 * 60 * 60 * 1000L
        private const val RECENT_DELIVERY_WINDOW_MS = 30 * 60 * 1000L
    }
}

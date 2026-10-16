package com.boopathi.bg.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boopathi.bg.data.local.entity.TaskEntity
import com.boopathi.bg.data.local.entity.TaskStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReminderCenter(
    tasks: List<TaskEntity>,
    notificationsAllowed: Boolean,
    message: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onReschedule: (Long, String) -> Unit,
    onComplete: (Long) -> Unit,
    onSnooze: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onClearCompleted: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    var phrase by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!notificationsAllowed) {
                    Text("Notifications are disabled. BG can save reminders, but Android cannot alert you until permission is enabled.", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRequestNotificationPermission) { Text("Enable notifications") }
                        TextButton(onClick = onOpenNotificationSettings) { Text("Settings") }
                    }
                }
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    enabled = !busy,
                    label = { Text("Remind me to…") },
                    placeholder = { Text("study tomorrow at 7 PM") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val id = editingId
                        if (id != null) onReschedule(id, phrase) else onCreate(phrase)
                    },
                    enabled = phrase.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (editingId == null) "Schedule reminder" else "Save changes") }

                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (tasks.isEmpty()) Text("No reminders yet.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(modifier = Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        ReminderRow(
                            task = task,
                            busy = busy,
                            onEdit = {
                                editingId = task.id
                                phrase = "Remind me to ${task.title} at "
                            },
                            onComplete = { onComplete(task.id) },
                            onSnooze = { onSnooze(task.id) },
                            onCancel = { onCancel(task.id) },
                            onDelete = { deleteId = task.id },
                            onRetry = { onRetry(task.id) }
                        )
                    }
                }
                if (tasks.any { it.completed || it.status == TaskStatus.COMPLETED }) {
                    TextButton(onClick = { clearConfirmation = true }, enabled = !busy) { Text("Clear completed history") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete reminder?") },
            text = { Text("This will remove the reminder and its schedule.") },
            confirmButton = {
                TextButton(onClick = { onDelete(id); deleteId = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep") } }
        )
    }
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("Clear completed reminders?") },
            text = { Text("Completed reminder history will be deleted.") },
            confirmButton = {
                TextButton(onClick = { onClearCompleted(); clearConfirmation = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ReminderRow(
    task: TaskEntity,
    busy: Boolean,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onSnooze: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    val zone = runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(ZoneId.systemDefault())
    val at = task.snoozedUntil ?: task.nextOccurrenceAt ?: task.dueTime
    val time = at?.let { DateTimeFormatter.ofPattern("EEE, d MMM yyyy h:mm a z").withZone(zone).format(Instant.ofEpochMilli(it)) } ?: "Time unavailable"
    val inactive = task.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
    val overdue = task.status in setOf(TaskStatus.SCHEDULED, TaskStatus.DELIVERED, TaskStatus.FAILED) && (at ?: Long.MAX_VALUE) < System.currentTimeMillis()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(task.title, style = MaterialTheme.typography.titleSmall)
        Text("$time${task.recurrenceRule?.let { " · ${it.substringAfter(':').lowercase().replaceFirstChar { char -> char.uppercase() }} weekly" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        val statusLabel = when {
            task.status == TaskStatus.DELIVERED -> "Alert attempted (BG cannot know whether it was seen)"
            overdue -> "Overdue / missed"
            else -> task.status.replaceFirstChar { it.uppercase() }
        }
        Text("$statusLabel${task.failureReason?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = if (task.failureReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!inactive) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("Reschedule") }
                TextButton(onClick = onComplete, enabled = !busy) { Text("Complete") }
                if (task.failureReason == null && (task.status == TaskStatus.DELIVERED || (task.recurrenceRule != null && task.lastOccurrenceAt != null && System.currentTimeMillis() - task.lastOccurrenceAt < 30 * 60 * 1000L))) {
                    TextButton(onClick = onSnooze, enabled = !busy) { Text("Snooze") }
                }
                TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                if (task.status == TaskStatus.FAILED) TextButton(onClick = onRetry, enabled = !busy) { Text("Retry") }
            }
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
        }
    }
}

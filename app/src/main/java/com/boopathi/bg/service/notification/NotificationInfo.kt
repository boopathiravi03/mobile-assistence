package com.boopathi.bg.service.notification

/**
 * Immutable, privacy-sanitized notification model.
 * Never retains raw android.service.notification.StatusBarNotification or
 * android.app.Notification instances.
 */
data class NotificationInfo(
    val notificationId: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "",
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    val conversationKey: String = "",
    val sourceType: NotificationType = NotificationType.OTHER,
    val senderName: String = "",
    val isRedacted: Boolean = false
) {
    val isMessaging: Boolean
        get() = sourceType == NotificationType.MESSAGING
}


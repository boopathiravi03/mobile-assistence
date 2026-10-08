package com.boopathi.bg.service.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interface for accessing and managing in-memory privacy-sanitized notifications.
 */
interface NotificationRepository {
    fun addNotification(notification: NotificationInfo)
    fun removeNotification(notificationId: String)
    fun getRecentNotifications(): List<NotificationInfo>
    fun getRecentMessagingNotifications(): List<NotificationInfo>
    fun findNotificationsForSender(senderName: String): List<NotificationInfo>
    fun findNotificationsForPackage(packageName: String): List<NotificationInfo>
    fun clear()
    val notificationFlow: StateFlow<List<NotificationInfo>>
}

/**
 * In-memory bounded notification buffer.
 * Retains maximum 50 recent notifications. Discards older entries.
 * Does not write notification bodies to persistent disk.
 */
open class InMemoryNotificationRepository(
    private val maxCapacity: Int = 50
) : NotificationRepository {

    private val _notifications = MutableStateFlow<List<NotificationInfo>>(emptyList())
    override val notificationFlow: StateFlow<List<NotificationInfo>> = _notifications.asStateFlow()

    @Synchronized
    override fun addNotification(notification: NotificationInfo) {
        val currentList = _notifications.value.toMutableList()
        // Deduplicate if already present by id
        currentList.removeAll { it.notificationId == notification.notificationId }
        currentList.add(0, notification) // Newest first

        // Enforce maximum buffer limit
        if (currentList.size > maxCapacity) {
            val trimmed = currentList.take(maxCapacity)
            _notifications.value = trimmed
        } else {
            _notifications.value = currentList
        }
    }

    @Synchronized
    override fun removeNotification(notificationId: String) {
        val currentList = _notifications.value.toMutableList()
        if (currentList.removeAll { it.notificationId == notificationId }) {
            _notifications.value = currentList
        }
    }

    override fun getRecentNotifications(): List<NotificationInfo> {
        return _notifications.value
    }

    override fun getRecentMessagingNotifications(): List<NotificationInfo> {
        return _notifications.value.filter { it.isMessaging }
    }

    override fun findNotificationsForSender(senderName: String): List<NotificationInfo> {
        val lower = senderName.lowercase().trim()
        return _notifications.value.filter {
            it.senderName.lowercase().contains(lower) ||
                    it.title.lowercase().contains(lower)
        }
    }

    override fun findNotificationsForPackage(packageName: String): List<NotificationInfo> {
        return _notifications.value.filter { it.packageName.equals(packageName, ignoreCase = true) }
    }

    @Synchronized
    override fun clear() {
        _notifications.value = emptyList()
    }
}


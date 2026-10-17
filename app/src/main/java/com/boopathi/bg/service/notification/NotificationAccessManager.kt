package com.boopathi.bg.service.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationAccessStatus {
    NOT_ENABLED,
    ENABLED_BUT_DISCONNECTED,
    CONNECTED,
    ERROR
}

/**
 * Manages notification listener permission verification and service connectivity status.
 */
open class NotificationAccessManager(
    context: Context,
    val repository: NotificationRepository = InMemoryNotificationRepository()
) {
    private val context: Context = context.applicationContext

    private val _status = MutableStateFlow(NotificationAccessStatus.NOT_ENABLED)
    val status: StateFlow<NotificationAccessStatus> = _status.asStateFlow()

    companion object {
        @Volatile
        private var instance: NotificationAccessManager? = null

        fun getInstance(context: Context): NotificationAccessManager {
            return instance ?: synchronized(this) {
                instance ?: NotificationAccessManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Checks if notification access is granted in system Settings.
     */
    open fun isAccessGranted(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    /**
     * Called by BGNotificationListenerService when connected.
     */
    fun onServiceConnected() {
        _status.value = NotificationAccessStatus.CONNECTED
    }

    /**
     * Called by BGNotificationListenerService when disconnected.
     */
    fun onServiceDisconnected() {
        _status.value = if (isAccessGranted()) {
            NotificationAccessStatus.ENABLED_BUT_DISCONNECTED
        } else {
            NotificationAccessStatus.NOT_ENABLED
        }
    }

    /**
     * Refresh current state based on system settings.
     */
    fun checkStatus() {
        if (!isAccessGranted()) {
            _status.value = NotificationAccessStatus.NOT_ENABLED
        } else if (_status.value != NotificationAccessStatus.CONNECTED) {
            _status.value = NotificationAccessStatus.ENABLED_BUT_DISCONNECTED
        }
    }

    /**
     * Returns Intent to open Android Notification Access settings.
     */
    fun getSettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}


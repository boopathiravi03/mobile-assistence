package com.boopathi.bg.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Android NotificationListenerService implementation for BG.
 * Observes permitted notifications, extracts privacy-filtered models, and
 * updates NotificationRepository.
 *
 * CRITICAL ISOLATION: Notifications are strictly DATA ONLY.
 * Notifications never trigger CommandProcessor or execute actions.
 */
class BGNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "BGNotificationService"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected.")
        NotificationAccessManager.getInstance(applicationContext).onServiceConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected.")
        NotificationAccessManager.getInstance(applicationContext).onServiceDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Parse and apply Privacy Policy immediately
        val info = NotificationParser.parse(sbn) ?: return

        // 2. Add to bounded in-memory buffer (Never forward to AI or execute commands)
        val repo = NotificationAccessManager.getInstance(applicationContext).repository
        repo.addNotification(info)

        Log.d(TAG, "Observed notification from [${info.appLabel}], redacted=${info.isRedacted}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: ""
        val notifId = "${sbn.id}_${sbn.postTime}_$pkg"
        val repo = NotificationAccessManager.getInstance(applicationContext).repository
        repo.removeNotification(notifId)
    }
}


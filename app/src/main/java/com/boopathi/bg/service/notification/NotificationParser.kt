package com.boopathi.bg.service.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Extracts and maps raw Android StatusBarNotification into safe, sanitized NotificationInfo.
 */
object NotificationParser {

    fun parse(sbn: StatusBarNotification): NotificationInfo? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: Bundle()

        val pkg = sbn.packageName ?: ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        if (title.isEmpty() && text.isEmpty()) {
            return null
        }

        val category = n.category ?: ""
        val source = NotificationSource.fromPackage(pkg)
        val isMessaging = category == Notification.CATEGORY_MESSAGE ||
                source != NotificationSource.UNKNOWN ||
                extras.containsKey(Notification.EXTRA_MESSAGING_PERSON) ||
                extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)

        val sourceType = when {
            isMessaging -> NotificationType.MESSAGING
            category == Notification.CATEGORY_EMAIL -> NotificationType.EMAIL
            category == Notification.CATEGORY_SYSTEM -> NotificationType.SYSTEM
            else -> NotificationType.OTHER
        }

        val appLabel = if (source != NotificationSource.UNKNOWN) {
            source.appLabel
        } else {
            pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }

        val senderName = if (isMessaging && title.isNotEmpty()) {
            // E.g. "Karthi (2 messages)" -> "Karthi"
            title.substringBefore(" (").trim()
        } else {
            title
        }

        val rawInfo = NotificationInfo(
            notificationId = "${sbn.id}_${sbn.postTime}_$pkg",
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            category = category,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            conversationKey = extras.getString(Notification.EXTRA_CONVERSATION_TITLE) ?: "",
            sourceType = sourceType,
            senderName = senderName
        )

        return NotificationPrivacyPolicy.sanitize(rawInfo)
    }

    /**
     * Parsing helper for mock/unit tests without requiring real StatusBarNotification objects.
     */
    fun createSafeNotification(
        packageName: String,
        title: String,
        text: String,
        category: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): NotificationInfo {
        val source = NotificationSource.fromPackage(packageName)
        val isMessaging = category == Notification.CATEGORY_MESSAGE ||
                source != NotificationSource.UNKNOWN

        val sourceType = when {
            isMessaging -> NotificationType.MESSAGING
            category == Notification.CATEGORY_EMAIL -> NotificationType.EMAIL
            category == Notification.CATEGORY_SYSTEM -> NotificationType.SYSTEM
            else -> NotificationType.OTHER
        }

        val appLabel = if (source != NotificationSource.UNKNOWN) source.appLabel else packageName

        val rawInfo = NotificationInfo(
            notificationId = "${System.nanoTime()}_$packageName",
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            timestamp = timestamp,
            category = category,
            sourceType = sourceType,
            senderName = title.substringBefore(" (").trim()
        )

        return NotificationPrivacyPolicy.sanitize(rawInfo)
    }
}


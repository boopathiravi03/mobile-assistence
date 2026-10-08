package com.boopathi.bg.service.notification

/**
 * High-level categories for observed system and application notifications.
 */
enum class NotificationType {
    MESSAGING,
    EMAIL,
    SYSTEM,
    OTHER
}

/**
 * Recognized and supported messaging application sources.
 */
enum class NotificationSource(val packageName: String, val appLabel: String) {
    WHATSAPP("com.whatsapp", "WhatsApp"),
    TELEGRAM("org.telegram.messenger", "Telegram"),
    SIGNAL("org.thoughtcrime.securesms", "Signal"),
    SMS_DEFAULT("com.google.android.apps.messaging", "Messages"),
    INSTAGRAM("com.instagram.android", "Instagram"),
    GENERIC_MESSAGING("", "Messaging App"),
    UNKNOWN("", "Other");

    companion object {
        fun fromPackage(pkg: String): NotificationSource {
            return entries.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) } ?: UNKNOWN
        }
    }
}


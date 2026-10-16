package com.boopathi.bg.service.reminder

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

sealed interface ReminderParseResult {
    data class Ready(val reminder: ParsedReminder) : ReminderParseResult
    data class Clarification(val question: String) : ReminderParseResult
    data class Rejected(val reason: String) : ReminderParseResult
}

data class ParsedReminder(
    val title: String,
    val scheduledAt: ZonedDateTime,
    val recurrenceRule: String? = null,
    val taskType: String = "reminder"
)

/** Small deterministic parser: it never guesses an unspecified AM/PM or vague part of day. */
class ReminderParser(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = clock.zone
) {
    private val timePattern = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
    private val relativePattern = Regex("\\b(?:in\\s+)?(\\d{1,3})\\s*(minutes?|mins?|hours?|hrs?|days?|nimishathula|nimidathil)\\b", RegexOption.IGNORE_CASE)
    private val weekdayNames = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

    fun parse(input: String): ReminderParseResult {
        val normalized = input.trim().lowercase(Locale.ROOT)
            .replace("naalaiku", "tomorrow").replace("naalai", "tomorrow")
            .replace("innaiku", "today").replace("indru", "today")
            .replace("நினைவூட்டு", "remind me").replace("நாளை", "tomorrow").replace("இன்று", "today")
            .replace("நிமிடங்களில்", "minutes").replace("நிமிடத்தில்", "minutes").replace("மணிக்கு", "at")
            .replace("mani", " at ")
        if (!normalized.contains("remind") && !normalized.contains("nyabagam pannu") &&
            !normalized.contains("schedule workflow") && !normalized.contains("scheduled workflow")) {
            return ReminderParseResult.Rejected("Say “remind me” and include a time, for example “Remind me to study at 7 PM.”")
        }
        if (Regex("\\b(evening|morning|afternoon|night)\\b").containsMatchIn(normalized) && !timePattern.containsMatchIn(normalized)) {
            return ReminderParseResult.Clarification("What exact time should I use?")
        }
        if (Regex("\\b(every day|daily|daily-a)\\b").containsMatchIn(normalized)) {
            return ReminderParseResult.Rejected("Daily recurrence is not supported yet. Choose a weekday instead.")
        }

        val now = ZonedDateTime.now(clock).withZoneSameInstant(zone)
        val relative = relativePattern.find(normalized)
        val recurrence = weekdayNames.entries.firstOrNull { (name, _) ->
            Regex("\\bevery\\s+$name\\b").containsMatchIn(normalized)
        }
        var scheduled: ZonedDateTime
        var recurrenceRule: String? = null
        if (relative != null) {
            if (recurrence != null) return ReminderParseResult.Clarification("Use either a relative delay or a weekly time, not both.")
            val amount = relative.groupValues[1].toLongOrNull()?.takeIf { it in 1..525_600 }
                ?: return ReminderParseResult.Rejected("That reminder delay is out of range.")
            val unit = relative.groupValues[2].lowercase(Locale.ROOT)
            scheduled = when {
                unit.startsWith("min") || unit.startsWith("n") -> now.plusMinutes(amount)
                unit.startsWith("h") -> now.plusHours(amount)
                else -> now.plusDays(amount)
            }
        } else {
            val timeMatch = timePattern.find(normalized)
                ?: return if (recurrence != null) ReminderParseResult.Clarification("What time should I use for that weekly reminder?")
                else ReminderParseResult.Clarification("What exact time should I use? Include AM or PM, such as 7 PM.")
            val hourRaw = timeMatch.groupValues[1].toIntOrNull() ?: return ReminderParseResult.Rejected("That time is invalid.")
            val minute = timeMatch.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val meridiem = timeMatch.groupValues[3].lowercase(Locale.ROOT)
            if (minute !in 0..59 || hourRaw !in 0..23 || (meridiem.isNotEmpty() && hourRaw !in 1..12)) {
                return ReminderParseResult.Rejected("That time is invalid.")
            }
            if (meridiem.isEmpty() && hourRaw in 1..12) {
                return ReminderParseResult.Clarification("Did you mean ${hourRaw} AM or ${hourRaw} PM?")
            }
            val hour = when (meridiem) { "pm" -> if (hourRaw == 12) 12 else hourRaw + 12; "am" -> if (hourRaw == 12) 0 else hourRaw; else -> hourRaw }
            val time = LocalTime.of(hour, minute)
            val explicitTomorrow = normalized.contains("tomorrow")
            val explicitToday = normalized.contains("today")
            val localDate = when {
                recurrence != null -> {
                    recurrenceRule = "WEEKLY:${recurrence.value.name}"
                    val day = recurrence.value
                    var candidate = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
                    if (candidate == now.toLocalDate() && !time.isAfter(now.toLocalTime())) candidate = candidate.plusWeeks(1)
                    candidate
                }
                explicitTomorrow -> now.toLocalDate().plusDays(1)
                explicitToday -> now.toLocalDate()
                else -> {
                    if (!time.isAfter(now.toLocalTime())) {
                        return ReminderParseResult.Clarification("That time has passed today. Should I use tomorrow instead?")
                    }
                    now.toLocalDate()
                }
            }
            val candidate = ZonedDateTime.of(LocalDateTime.of(localDate, time), zone)
            if (!candidate.isAfter(now)) {
                return ReminderParseResult.Clarification("That time has already passed. Should I use the next occurrence instead?")
            }
            scheduled = candidate
        }

        val title = sanitizeTitle(normalized, timePattern, relativePattern, weekdayNames.keys)
            ?: return ReminderParseResult.Rejected("Please provide a short reminder description.")
        if (title.length > 160) return ReminderParseResult.Rejected("Reminder text must be 160 characters or fewer.")
        if (Regex("\\b(password|passcode|otp|one.time.code|cvv|credit card number)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
            return ReminderParseResult.Rejected("Sensitive credentials cannot be stored in a reminder.")
        }
        val workflow = normalized.contains("schedule workflow") || normalized.contains("scheduled workflow")
        return ReminderParseResult.Ready(
            ParsedReminder(title, scheduled, recurrenceRule, if (workflow) "workflow_request" else if (recurrenceRule != null) "recurring_reminder" else "reminder")
        )
    }

    private fun sanitizeTitle(input: String, timeRegex: Regex, relativeRegex: Regex, weekdays: Set<String>): String? {
        var title = input.trim()
            .replace(Regex("(?i)remind me|nyabagam pannu|schedule workflow request|scheduled workflow request"), " ")
            .replace(Regex("(?i)\\b(today|tomorrow|naalai|naalaiku|innaiku|indru)\\b"), " ")
            .replace(Regex("(?i)\\b(every\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), " ")
            .replace(relativeRegex, " ")
            .replace(timeRegex, " ")
            .replace(Regex("(?i)\\b(at|on|to|in|every|please|me)\\b"), " ")
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().trim('.', ',', '!', '?')
        if (weekdays.isEmpty()) title = input.trim()
        return title.takeIf { it.isNotBlank() }
    }
}

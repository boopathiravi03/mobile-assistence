package com.boopathi.bg.service.reminder

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object ReminderRecurrence {
    fun next(rule: String?, occurrenceAt: Long, zoneId: String?, nowMillis: Long): Long? {
        if (rule == null || !rule.startsWith("WEEKLY:")) return null
        val day = runCatching { DayOfWeek.valueOf(rule.substringAfter(':')) }.getOrNull() ?: return null
        val zone = runCatching { ZoneId.of(zoneId ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val occurrence = Instant.ofEpochMilli(occurrenceAt).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var date = occurrence.toLocalDate().plusDays(1).with(TemporalAdjusters.nextOrSame(day))
        var next = ZonedDateTime.of(LocalDateTime.of(date, occurrence.toLocalTime()), zone)
        while (!next.isAfter(now)) {
            date = date.plusWeeks(1)
            next = ZonedDateTime.of(LocalDateTime.of(date, occurrence.toLocalTime()), zone)
        }
        return next.toInstant().toEpochMilli()
    }
}

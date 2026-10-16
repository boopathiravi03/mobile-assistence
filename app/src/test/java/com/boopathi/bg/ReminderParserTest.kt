package com.boopathi.bg

import com.boopathi.bg.service.reminder.ReminderParseResult
import com.boopathi.bg.service.reminder.ReminderParser
import com.boopathi.bg.service.reminder.ReminderRecurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone)
    private val parser = ReminderParser(Clock.fixed(now.toInstant(), zone), zone)

    @Test fun parsesOneTimeReminderInLocalTimezone() {
        val result = parser.parse("Remind me to study at 7 PM") as ReminderParseResult.Ready
        assertEquals("study", result.reminder.title)
        assertEquals(now.toLocalDate(), result.reminder.scheduledAt.toLocalDate())
        assertEquals(19, result.reminder.scheduledAt.hour)
        assertEquals(zone, result.reminder.scheduledAt.zone)
    }

    @Test fun parsesRelativeDelayAndTanglishUnit() {
        val result = parser.parse("Remind me to drink water in 20 nimishathula") as ReminderParseResult.Ready
        assertEquals("drink water", result.reminder.title)
        assertEquals(now.plusMinutes(20), result.reminder.scheduledAt)
    }

    @Test fun parsesTomorrowAndWeeklyRecurrence() {
        val tomorrow = parser.parse("Remind me tomorrow at 6 AM to exercise") as ReminderParseResult.Ready
        assertEquals(now.toLocalDate().plusDays(1), tomorrow.reminder.scheduledAt.toLocalDate())
        val weekly = parser.parse("Remind me to review goals every Monday at 8 AM") as ReminderParseResult.Ready
        assertEquals("WEEKLY:MONDAY", weekly.reminder.recurrenceRule)
        assertEquals("recurring_reminder", weekly.reminder.taskType)
    }

    @Test fun requestsClarificationForAmbiguousTimeAndVagueEvening() {
        assertTrue(parser.parse("Remind me to study at 7") is ReminderParseResult.Clarification)
        assertTrue(parser.parse("Remind me to study tomorrow evening") is ReminderParseResult.Clarification)
        assertTrue(parser.parse("Remind me to study at 8 AM") is ReminderParseResult.Clarification)
    }

    @Test fun handlesSupportedTamilAndTanglishDateWords() {
        val tamil = parser.parse("நினைவூட்டு நாளை 6 AM மணிக்கு உடற்பயிற்சி") as ReminderParseResult.Ready
        assertEquals("உடற்பயிற்சி", tamil.reminder.title)
        assertEquals(now.toLocalDate().plusDays(1), tamil.reminder.scheduledAt.toLocalDate())
        val tanglish = parser.parse("naalaiku remind me to walk at 7 PM") as ReminderParseResult.Ready
        assertEquals(now.toLocalDate().plusDays(1), tanglish.reminder.scheduledAt.toLocalDate())
    }

    @Test fun rejectsInvalidTimeAndSensitiveReminderText() {
        assertTrue(parser.parse("Remind me to study at 25 PM") is ReminderParseResult.Rejected)
        assertTrue(parser.parse("Remind me to save my OTP at 7 PM") is ReminderParseResult.Rejected)
    }

    @Test fun weeklyRecurrenceKeepsWallClockTimeAcrossDaylightSavingChange() {
        val ny = ZoneId.of("America/New_York")
        val occurrence = ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, ny).toInstant().toEpochMilli()
        val afterDst = ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val next = Instant.ofEpochMilli(ReminderRecurrence.next("WEEKLY:SUNDAY", occurrence, ny.id, afterDst)!!).atZone(ny)
        assertEquals(8, next.hour)
        assertEquals(ZoneId.of("America/New_York"), next.zone)
        assertTrue(next.toInstant().toEpochMilli() > afterDst)
    }
}

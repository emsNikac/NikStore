package com.nikac.nikstore.updates

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime
import java.util.TimeZone

class DailyReleasePolicyTest {
    private val skopje = TimeZone.getTimeZone("Europe/Skopje")
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun schedulesTonightBefore23AndTomorrowAtOrAfter23() {
        val tonight = at("2026-10-22T23:00:00+02:00[Europe/Skopje]")
        val tomorrow = at("2026-10-23T23:00:00+02:00[Europe/Skopje]")
        assertEquals(tonight, DailyReleasePolicy.nextCheckAt(tonight - 1, skopje))
        assertEquals(tomorrow, DailyReleasePolicy.nextCheckAt(tonight, skopje))
        assertEquals(tomorrow, DailyReleasePolicy.nextCheckAt(tonight + 1, skopje))
    }

    @Test fun springClockChangeKeeps23LocalWith23HourGap() {
        val before = at("2026-03-28T23:00:00+01:00[Europe/Skopje]")
        val after = at("2026-03-29T23:00:00+02:00[Europe/Skopje]")
        assertEquals(after, DailyReleasePolicy.nextCheckAt(before, skopje))
        assertEquals(23 * 60 * 60 * 1000L, after - before)
    }

    @Test fun autumnClockChangeKeeps23LocalWith25HourGap() {
        val before = at("2026-10-24T23:00:00+02:00[Europe/Skopje]")
        val after = at("2026-10-25T23:00:00+01:00[Europe/Skopje]")
        assertEquals(after, DailyReleasePolicy.nextCheckAt(before, skopje))
        assertEquals(25 * 60 * 60 * 1000L, after - before)
    }

    @Test fun timeZoneChangeTargetsTheNewLocalEvening() {
        val now = at("2026-10-22T22:00:00+02:00[Europe/Skopje]")
        assertEquals(at("2026-10-22T23:00:00-04:00[America/New_York]"),
            DailyReleasePolicy.nextCheckAt(now, TimeZone.getTimeZone("America/New_York")))
    }

    @Test fun delayedCheckAfterMidnightUsesPreviousNightWindow() {
        assertEquals("2026-10-21", DailyReleasePolicy.checkDay(at("2026-10-22T22:59:59+02:00"), skopje))
        assertEquals("2026-10-22", DailyReleasePolicy.checkDay(at("2026-10-22T23:00:00+02:00"), skopje))
        assertEquals("2026-10-22", DailyReleasePolicy.checkDay(at("2026-10-23T02:00:00+02:00"), skopje))
        assertEquals("2025-12-31", DailyReleasePolicy.checkDay(at("2026-01-01T01:00:00+01:00"), skopje))
    }
}

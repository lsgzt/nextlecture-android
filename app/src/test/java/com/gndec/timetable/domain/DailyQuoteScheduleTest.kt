package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyQuoteScheduleTest {

    private val zone: ZoneId = DailyQuoteSchedule.ZONE

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun triggerIsFiveThirtyTwoIstRegardlessOfDeviceZone() {
        // 05:32 IST is 00:02 UTC. The boundary is pinned to Asia/Kolkata, so a device in
        // any other zone still gets the refresh at the same wall-clock IST moment.
        val trigger = at(2026, 9, 29, 5, 32)
        val utcHour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger), ZoneId.of("UTC"))
        assertEquals(0, utcHour.hour)
        assertEquals(2, utcHour.minute)
    }

    @Test
    fun nextTriggerIsTodayBeforeFiveThirtyTwo() {
        val now = at(2026, 9, 29, 4, 0)
        assertEquals(at(2026, 9, 29, 5, 32), DailyQuoteSchedule.nextTriggerAfter(now))
    }

    @Test
    fun nextTriggerIsTomorrowAfterFiveThirtyTwo() {
        val now = at(2026, 9, 29, 6, 0)
        assertEquals(at(2026, 9, 30, 5, 32), DailyQuoteSchedule.nextTriggerAfter(now))
    }

    @Test
    fun nextTriggerIsTomorrowWhenTheBoundaryIsHitExactly() {
        // A receiver re-arming itself at the instant its alarm fired must not schedule the
        // boundary it just consumed, or the fetch would run twice a minute apart forever.
        val now = at(2026, 9, 29, 5, 32)
        assertEquals(at(2026, 9, 30, 5, 32), DailyQuoteSchedule.nextTriggerAfter(now))
    }

    @Test
    fun dayKeyFollowsIstNotTheDeviceClock() {
        // 23:00 UTC on the 28th. That is still the 28th in Los Angeles but already the
        // 29th in IST, so two devices on the same instant can disagree about the day unless
        // the boundary is pinned to Asia/Kolkata. Pinning it means a student abroad counts
        // the same quote day as everyone on campus.
        val instant = ZonedDateTime.of(
            LocalDateTime.of(2026, 9, 28, 23, 0), ZoneId.of("UTC")
        ).toInstant().toEpochMilli()

        assertEquals("2026-09-28", ZonedDateTime.ofInstant(Instant.ofEpochMilli(instant), ZoneId.of("America/Los_Angeles")).toLocalDate().toString())
        assertEquals("2026-09-29", DailyQuoteSchedule.dayKey(instant))
    }

    @Test
    fun nothingCachedIsAlwaysDue() {
        assertTrue(DailyQuoteSchedule.isFetchDue("", at(2026, 9, 29, 4, 0)))
        assertTrue(DailyQuoteSchedule.isFetchDue("", at(2026, 9, 29, 18, 0)))
    }

    @Test
    fun todaysQuoteIsNeverFetchedTwice() {
        // The whole point of the design: 05:32 fired, then the student opens the app fifty
        // times. Each open sees today's dayKey and stays off the network.
        val afterBoundary = at(2026, 9, 29, 6, 0)
        assertFalse(DailyQuoteSchedule.isFetchDue("2026-09-29", afterBoundary))
        assertFalse(DailyQuoteSchedule.isFetchDue("2026-09-29", at(2026, 9, 29, 23, 59)))
    }

    @Test
    fun staleQuoteBeforeTheBoundaryIsKeptRatherThanRefetched() {
        // Between 00:00 and 05:32 IST there is nothing newer to fetch; yesterday's quote
        // stays on screen until today's boundary arrives.
        assertFalse(DailyQuoteSchedule.isFetchDue("2026-09-28", at(2026, 9, 29, 4, 0)))
    }

    @Test
    fun staleQuoteAfterTheBoundaryCatchesUp() {
        // The device was off at 05:32, so the one-shot alarm never ran. Opening the app
        // after the boundary owes a fetch.
        assertTrue(DailyQuoteSchedule.isFetchDue("2026-09-28", at(2026, 9, 29, 5, 33)))
    }

    @Test
    fun triggerAtMatchesTheNextTriggerComputation() {
        val date = LocalDate.of(2026, 10, 1)
        assertEquals(
            at(2026, 10, 1, 5, 32),
            DailyQuoteSchedule.triggerAt(date)
        )
    }
}

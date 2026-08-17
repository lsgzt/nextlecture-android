package com.gndec.timetable.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    private val now = 1_760_000_000_000L

    @Test
    fun freshnessText() {
        assertEquals("Timetable not fetched yet", Formatters.freshnessText(null, now))
        assertEquals("Updated just now", Formatters.freshnessText(now - 30_000, now))
        assertEquals("Updated 8 min ago", Formatters.freshnessText(now - 8 * 60_000, now))
        assertEquals("Updated 2 hours ago", Formatters.freshnessText(now - 2 * 3_600_000, now))
        assertEquals("Updated yesterday", Formatters.freshnessText(now - 25 * 3_600_000, now))
        assertEquals("Updated 3 days ago", Formatters.freshnessText(now - 3 * 86_400_000, now))
    }

    @Test
    fun staleDetection() {
        assertTrue(Formatters.isStale(null, now))
        assertFalse(Formatters.isStale(now - 3_600_000, now))
        assertTrue(Formatters.isStale(now - 3 * 86_400_000, now))
    }

    @Test
    fun timeFormatting() {
        assertEquals("8:30 AM", Formatters.hm(8 * 60 + 30))
        assertEquals("1:30 PM", Formatters.hm(13 * 60 + 30))
        assertEquals("12:00 PM", Formatters.hm(12 * 60))
        assertEquals("12:00 AM", Formatters.hm(0))
        assertEquals("10:30 AM – 11:30 AM", Formatters.range(630, 690))
    }

    @Test
    fun countdownFormatting() {
        assertEquals("18 min", Formatters.countdown(18))
        assertEquals("1 hr 5 min", Formatters.countdown(65))
        assertEquals("2 hr 0 min", Formatters.countdown(120))
    }
}

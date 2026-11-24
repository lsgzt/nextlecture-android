package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Which exam the big card shows as the clock moves through the sheet: the one
 * running now, then the next one today, then the next one on a later day.
 */
class ExamTimelineTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun exam(day: Int, start: Int, end: Int, subject: String) = Exam(
        date = LocalDate.of(2026, 11, day),
        startMinutes = start,
        endMinutes = end,
        subject = subject
    )

    private val sheet = listOf(
        exam(23, 555, 645, "Mathematics-I"),
        exam(25, 555, 645, "PPS"),
        exam(26, 765, 855, "Physics"),
        exam(27, 765, 855, "Economics"),
        exam(28, 660, 750, "EDG")
    )

    private fun at(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun beforeTheSheetStartsTheFirstExamIsFeatured() {
        val view = ExamTimeline.compute(sheet, at(20, 18, 0), zone)
        assertEquals("Mathematics-I", view.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.UPCOMING, view.featured.phase)
        assertEquals(3, view.featured.daysAhead)
        assertEquals(3 * 1440 + 555 - 18 * 60, view.featured.minutes)
        assertEquals(4, view.upcoming.size)
        assertEquals(0, view.finished)
        assertEquals(5, view.total)
        assertFalse(view.allOver)
    }

    @Test
    fun todaysExamIsFeaturedFromTheMorning() {
        val view = ExamTimeline.compute(sheet, at(26, 8, 0), zone)
        assertEquals("Physics", view.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.TODAY, view.featured.phase)
        assertEquals(0, view.featured.daysAhead)
        assertEquals(765 - 480, view.featured.minutes)
        assertEquals(listOf("Economics", "EDG"), view.upcoming.map { it.subject })
    }

    @Test
    fun whileTheExamRunsItIsFeaturedAndCountsDownToItsEnd() {
        val view = ExamTimeline.compute(sheet, at(26, 13, 0), zone)
        assertEquals("Physics", view.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.ONGOING, view.featured.phase)
        assertEquals(0, view.featured.daysAhead)
        assertEquals(855 - 780, view.featured.minutes)
        assertEquals(listOf("Economics", "EDG"), view.upcoming.map { it.subject })
        assertEquals(2, view.finished)
    }

    @Test
    fun theMinuteAnExamEndsTheNextOneTakesTheBigCard() {
        val lastMinute = ExamTimeline.compute(sheet, at(26, 14, 14), zone)
        assertEquals("Physics", lastMinute.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.ONGOING, lastMinute.featured.phase)
        assertEquals(1, lastMinute.featured.minutes)

        val over = ExamTimeline.compute(sheet, at(26, 14, 15), zone)
        assertEquals("Economics", over.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.UPCOMING, over.featured.phase)
        assertEquals(1, over.featured.daysAhead)
        assertEquals(1440 + 765 - 855, over.featured.minutes)
        assertEquals(listOf("EDG"), over.upcoming.map { it.subject })
        assertEquals(3, over.finished)
    }

    @Test
    fun twoExamsInADayAreShownOneAfterTheOther() {
        val day = listOf(exam(26, 555, 645, "Physics"), exam(26, 765, 855, "Chemistry"))
        val morning = ExamTimeline.compute(day, at(26, 10, 0), zone)
        assertEquals("Physics", morning.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.ONGOING, morning.featured.phase)
        assertEquals(listOf("Chemistry"), morning.upcoming.map { it.subject })

        val gap = ExamTimeline.compute(day, at(26, 12, 0), zone)
        assertEquals("Chemistry", gap.featured!!.exam.subject)
        assertEquals(ExamTimeline.Phase.TODAY, gap.featured.phase)
        assertEquals(765 - 720, gap.featured.minutes)
        assertTrue(gap.upcoming.isEmpty())
        assertEquals(1, gap.finished)
    }

    @Test
    fun onceTheLastExamIsOverTheSheetSaysSo() {
        val view = ExamTimeline.compute(sheet, at(28, 12, 31), zone)
        assertNull(view.featured)
        assertTrue(view.upcoming.isEmpty())
        assertEquals(5, view.finished)
        assertTrue(view.allOver)

        val weeksLater = ExamTimeline.compute(sheet, at(30, 9, 0), zone)
        assertTrue(weeksLater.allOver)
        assertEquals(5, weeksLater.finished)
    }

    @Test
    fun noExamsIsNotTheSameAsAllOver() {
        val view = ExamTimeline.compute(emptyList(), at(26, 9, 0), zone)
        assertNull(view.featured)
        assertTrue(view.upcoming.isEmpty())
        assertEquals(0, view.total)
        assertFalse(view.allOver)
    }

    @Test
    fun inputOrderDoesNotMatter() {
        val view = ExamTimeline.compute(sheet.reversed(), at(27, 9, 0), zone)
        assertEquals("Economics", view.featured!!.exam.subject)
        assertEquals(listOf("EDG"), view.upcoming.map { it.subject })
    }
}

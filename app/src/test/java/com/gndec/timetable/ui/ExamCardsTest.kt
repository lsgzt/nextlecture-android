package com.gndec.timetable.ui

import com.gndec.timetable.domain.Exam
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The plain-text parts of the exam cards: how a day is named, what a view with
 * no exam of its own says about the next one, and how a listed exam stands
 * against the clock of the day it is shown on.
 */
class ExamCardsTest {

    /** A Tuesday. */
    private val today = LocalDate.of(2026, 11, 24)

    private fun exam(date: String, start: Int, end: Int, subject: String) =
        Exam(LocalDate.parse(date), start, end, subject)

    private val physics = exam("2026-11-26", 12 * 60 + 45, 14 * 60 + 15, "Physics")
    private val english = exam("2026-11-27", 9 * 60 + 15, 10 * 60 + 45, "English")

    @Test
    fun daysAreNamedTodayTomorrowThenByDate() {
        assertEquals("Today", examDayLabel(today, today))
        assertEquals("Tomorrow", examDayLabel(today.plusDays(1), today))
        assertEquals("Thu 26 Nov", examDayLabel(today.plusDays(2), today))
        assertEquals("Mon 23 Nov", examDayLabel(today.minusDays(1), today))
    }

    @Test
    fun anEmptyTodayPointsAtTheNextExamOnAnyLaterDay() {
        assertEquals("Next exam: Physics · on Thu 26 Nov · 12:45 PM – 2:15 PM", nextExamLine(today, listOf(physics, english), today, 10 * 60))
    }

    @Test
    fun anEmptyTodayNamesTomorrowWhenThatIsWhereTheNextExamIs() {
        val chemistry = exam("2026-11-25", 9 * 60 + 15, 10 * 60 + 45, "Chemistry")
        assertEquals("Next exam: Chemistry · Tomorrow · 9:15 AM – 10:45 AM", nextExamLine(today, listOf(chemistry, physics), today, 10 * 60))
    }

    @Test
    fun anEmptyTomorrowSkipsPastItself() {
        // Tomorrow has nothing, so the line is about the exam after it.
        assertEquals("Next exam: Physics · on Thu 26 Nov · 12:45 PM – 2:15 PM", nextExamLine(today.plusDays(1), listOf(physics, english), today, 10 * 60))
    }

    @Test
    fun anExamStillRunningIsNotAnnouncedAsTheNextOne() {
        val maths = exam("2026-11-26", 9 * 60, 11 * 60, "Mathematics-I")
        val late = today.plusDays(2).atTime(10, 30)
        val nowMinutes = late.hour * 60 + late.minute
        assertEquals("Next exam: Physics · Today · 12:45 PM – 2:15 PM", nextExamLine(late.toLocalDate(), listOf(maths, physics), late.toLocalDate(), nowMinutes))
    }

    @Test
    fun aScheduleWithNothingLeftSaysSo() {
        assertEquals("No more exams in this schedule.", nextExamLine(today, emptyList(), today, 10 * 60))
        val over = exam("2026-11-24", 9 * 60, 10 * 60, "Mathematics-I")
        assertEquals("No more exams in this schedule.", nextExamLine(today, listOf(over), today, 10 * 60))
    }

    @Test
    fun anExamOnADayAlreadyPastReadsAsOver() {
        // A week opened on Tuesday still lists Monday's exam; it must not say "Upcoming".
        val monday = exam("2026-11-23", 9 * 60 + 15, 10 * 60 + 45, "Mathematics-I")
        assertEquals(ExamStatus.DONE, examStatus(monday, examDayClock(monday.date, today, 10 * 60)))
    }

    @Test
    fun anExamOnADayStillToComeHasNotStarted() {
        assertEquals(ExamStatus.UPCOMING, examStatus(physics, examDayClock(physics.date, today, 10 * 60)))
    }

    @Test
    fun todayIsReadAgainstTheClock() {
        val morning = exam("2026-11-24", 9 * 60 + 15, 10 * 60 + 45, "PPS")
        val afternoon = exam("2026-11-24", 12 * 60 + 45, 14 * 60 + 15, "Chemistry")
        fun at(hour: Int) = examDayClock(today, today, hour * 60)
        assertEquals(ExamStatus.RUNNING, examStatus(morning, at(10)))
        assertEquals(ExamStatus.UPCOMING, examStatus(afternoon, at(10)))
        assertEquals(ExamStatus.DONE, examStatus(morning, at(11)))
    }
}

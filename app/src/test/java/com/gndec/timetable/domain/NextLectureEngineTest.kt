package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextLectureEngineTest {

    private fun lec(day: Int, start: Int, end: Int, subject: String = "Subject$start") =
        LectureEntity(0, "ITB2", day, start, end, subject, "Teacher", "F106", "Lecture", "raw", 0)

    private val monday = listOf(
        lec(1, 9 * 60, 10 * 60, "Physics"),
        lec(1, 11 * 60, 12 * 60, "Mathematics"),
        lec(1, 14 * 60, 15 * 60, "Programming")
    )
    private val tuesday = listOf(lec(2, 8 * 60 + 30, 9 * 60 + 30, "Chemistry"))

    @Test
    fun beforeFirstLecture() {
        val s = NextLectureEngine.compute(monday, todayDow = 1, nowMinutes = 8 * 60)
        assertTrue(s is NextLectureEngine.Status.Next)
        assertEquals(60, (s as NextLectureEngine.Status.Next).startsInMinutes)
        assertEquals("Physics", s.lecture.subject)
    }

    @Test
    fun betweenLecturesPicksTheNextOne() {
        val s = NextLectureEngine.compute(monday, 1, 10 * 60 + 15)
        assertTrue(s is NextLectureEngine.Status.Next)
        assertEquals(45, (s as NextLectureEngine.Status.Next).startsInMinutes)
        assertEquals("Mathematics", s.lecture.subject)
    }

    @Test
    fun duringLectureReportsHappeningNow() {
        val s = NextLectureEngine.compute(monday, 1, 9 * 60 + 30)
        assertTrue(s is NextLectureEngine.Status.HappeningNow)
        assertEquals(30, (s as NextLectureEngine.Status.HappeningNow).endsInMinutes)
        assertEquals("Physics", s.lecture.subject)
    }

    @Test
    fun exactlyAtStartIsHappening() {
        assertTrue(NextLectureEngine.compute(monday, 1, 9 * 60) is NextLectureEngine.Status.HappeningNow)
    }

    @Test
    fun exactlyAtEndIsNotHappening() {
        val s = NextLectureEngine.compute(monday, 1, 10 * 60)
        assertTrue(s is NextLectureEngine.Status.Next) // next is 11:00
    }

    @Test
    fun afterLastLectureShowsTomorrow() {
        val s = NextLectureEngine.compute(monday + tuesday, 1, 15 * 60 + 30)
        assertTrue(s is NextLectureEngine.Status.DoneForToday)
        val next = (s as NextLectureEngine.Status.DoneForToday).next!!
        assertEquals(1, next.daysAhead)
        assertEquals("Chemistry", next.lecture.subject)
    }

    @Test
    fun dayWithNoLecturesIsFreeDay() {
        val s = NextLectureEngine.compute(monday + tuesday, todayDow = 3, nowMinutes = 10 * 60)
        assertTrue(s is NextLectureEngine.Status.FreeDay)
        assertEquals(5, (s as NextLectureEngine.Status.FreeDay).next!!.daysAhead) // Monday
    }

    @Test
    fun weekendWrapsToMonday() {
        val s = NextLectureEngine.compute(monday, todayDow = 6, nowMinutes = 12 * 60)
        assertTrue(s is NextLectureEngine.Status.FreeDay)
        val next = (s as NextLectureEngine.Status.FreeDay).next!!
        assertEquals(2, next.daysAhead)
        assertEquals("Physics", next.lecture.subject)
    }

    @Test
    fun emptyTimetableIsNoData() {
        assertEquals(
            NextLectureEngine.Status.NoData,
            NextLectureEngine.compute(emptyList(), 1, 600)
        )
    }

    @Test
    fun noLecturesAtAllLaterGivesNullNext() {
        val s = NextLectureEngine.compute(monday, 1, 16 * 60)
        assertTrue(s is NextLectureEngine.Status.DoneForToday)
        // wraps a full week, nothing after 16:00 Monday -> next Monday 9:00
        val next = (s as NextLectureEngine.Status.DoneForToday).next!!
        assertEquals(7, next.daysAhead)
        assertEquals("Physics", next.lecture.subject)
    }

    @Test
    fun freeGapIsDetected() {
        assertEquals(45, NextLectureEngine.freeGapMinutes(monday, 1, 10 * 60 + 15))
        assertNull(NextLectureEngine.freeGapMinutes(monday, 1, 9 * 60 + 30)) // during lecture
        assertNull(NextLectureEngine.freeGapMinutes(monday, 1, 16 * 60))     // done for today
    }
}

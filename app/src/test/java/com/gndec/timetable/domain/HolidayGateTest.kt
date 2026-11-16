package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holiday gate: which days count as full holidays, how the day-scoped
 * "show the timetable anyway" override interacts with them, and which lecture
 * comes next once a holiday (or run of holidays) is over.
 */
class HolidayGateTest {

    private fun holiday(
        date: String,
        name: String = "Holi",
        category: String = "Public holiday"
    ) = Holiday(
        id = "$name-$date",
        name = name,
        date = date,
        displayDate = date,
        weekday = "Monday",
        category = category,
        year = 2026
    )

    private fun lecture(dow: Int, start: Int = 540, subject: String = "Maths") = LectureEntity(
        groupName = "ITB2",
        dayOfWeek = dow,
        startMinutes = start,
        endMinutes = start + 60,
        subject = subject,
        teacher = "Dr. Sethi",
        venue = "F108",
        lectureType = "Lecture",
        rawText = "",
        fetchId = 1L
    )

    @Test
    fun onlyFullHolidaysQualify() {
        assertTrue(HolidayGate.isFullHolidayCategory("Public holiday"))
        assertTrue(HolidayGate.isFullHolidayCategory("Restricted holiday"))
        assertTrue(HolidayGate.isFullHolidayCategory(" Gazetted holiday "))
        assertFalse(HolidayGate.isFullHolidayCategory("Half-day holiday"))
        assertFalse(HolidayGate.isFullHolidayCategory("half day"))
        assertFalse(HolidayGate.isFullHolidayCategory(""))
        assertFalse(HolidayGate.isFullHolidayCategory("College closed"))
    }

    @Test
    fun severalHolidaysOnOneDateAreAllFoundAndJoined() {
        // 14 April 2026 really carries both in the official list.
        val list = listOf(
            holiday("2026-04-14", "Baisakhi"),
            holiday("2026-04-14", "Birthday Dr. B.R. Ambedkar"),
            holiday("2026-04-14", "Staff half day", category = "Half-day holiday")
        )
        val on = HolidayGate.fullHolidaysOn(list, "2026-04-14")
        assertEquals(2, on.size)
        assertEquals("Baisakhi and Birthday Dr. B.R. Ambedkar", HolidayGate.displayNames(on))
        assertTrue(HolidayGate.isFullHolidayOn(list, "2026-04-14"))
        assertFalse(HolidayGate.isFullHolidayOn(list, "2026-04-15"))
    }

    @Test
    fun halfDayHolidaysDoNotTriggerTheGate() {
        val list = listOf(holiday("2026-03-04", "Holi", category = "Half-day holiday"))
        assertTrue(HolidayGate.fullHolidaysOn(list, "2026-03-04").isEmpty())
        assertFalse(HolidayGate.isFullHolidayOn(list, "2026-03-04"))
        assertEquals("", HolidayGate.displayNames(emptyList()))
    }

    @Test
    fun decodeToleratesBlankBrokenAndUnknownInput() {
        assertEquals(0, HolidayGate.decode("").size)
        assertEquals(0, HolidayGate.decode("not json {").size)
        val json = """
            [{"id":"h1","name":"Diwali","date":"2026-11-08","displayDate":"November 8, 2026",
              "weekday":"Sunday","category":"Public holiday","year":2026,"source":"x","unknownField":1}]
        """.trimIndent()
        val decoded = HolidayGate.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("Diwali", decoded[0].name)
        assertEquals("2026-11-08", decoded[0].date)
        assertTrue(HolidayGate.isFullHolidayOn(decoded, "2026-11-08"))
    }

    @Test
    fun nextLectureOnASimpleHolidayIsTomorrow() {
        // Wednesday 2026-03-04 is Holi; Thursday has a 10:00 Physics lecture.
        val holidays = listOf(holiday("2026-03-04", "Holi"))
        val lectures = listOf(lecture(3), lecture(4, 600, "Physics"))
        val next = HolidayGate.nextLectureAfter(lectures, holidays, "2026-03-04")!!
        assertEquals("Physics", next.lecture.subject)
        assertEquals(1, next.daysAhead)
    }

    @Test
    fun nextLectureSkipsARunOfHolidays() {
        // Holi Wednesday plus a restricted holiday Thursday: first teaching day is Friday.
        val holidays = listOf(
            holiday("2026-03-04", "Holi"),
            holiday("2026-03-05", "Extended break", category = "Restricted holiday")
        )
        val lectures = listOf(lecture(3), lecture(5, 555, "DBMS"))
        val next = HolidayGate.nextLectureAfter(lectures, holidays, "2026-03-04")!!
        assertEquals("DBMS", next.lecture.subject)
        assertEquals(2, next.daysAhead)
    }

    @Test
    fun nextLectureWrapsToNextWeekWhenOnlyTodaysSlotsExist() {
        val holidays = listOf(holiday("2026-03-04", "Holi"))
        // Only Wednesday lectures exist; the same weekday returns next week.
        val next = HolidayGate.nextLectureAfter(listOf(lecture(3)), holidays, "2026-03-04")
        assertNotNull(next)
        assertEquals(7, next!!.daysAhead)
        assertEquals("Maths", next.lecture.subject)
    }

    @Test
    fun nextLectureIsNullWithNoTimetableOrNoTeachingDay() {
        val holidays = listOf(holiday("2026-03-04", "Holi"))
        assertNull(HolidayGate.nextLectureAfter(emptyList(), holidays, "2026-03-04"))
        assertNull(HolidayGate.nextLectureAfter(listOf(lecture(3)), emptyList(), "not-a-date"))
        assertNull(HolidayGate.nextLectureAfter(listOf(lecture(3)), holidays, ""))
    }

    @Test
    fun nonHolidayDatesKeepTheirOwnSchedule() {
        // A normal Saturday: the gate itself is not active (holiday is on Monday).
        val holidays = listOf(holiday("2026-11-16", "Martyrdom Day of S. Kartar Singh Sarabha Ji"))
        assertTrue(HolidayGate.fullHolidaysOn(holidays, "2026-11-14").isEmpty())
        // Only Monday classes exist. Monday 16 Nov is the holiday, so the next
        // teaching Monday is 23 Nov — offset 9 from Saturday 14 Nov.
        val mondayOnly = listOf(lecture(1, 540, "Monday class"))
        val next = HolidayGate.nextLectureAfter(mondayOnly, holidays, "2026-11-14")!!
        assertEquals(9, next.daysAhead)
        assertEquals("Monday class", next.lecture.subject)
    }
}

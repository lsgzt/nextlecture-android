package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FacultyBirthdaysTest {

    private fun teacher(name: String, dob: String, profileId: String = name.lowercase().replace(" ", "")) =
        FacultyRecord(name = name, profileId = profileId, dateOfBirth = dob)

    private val today = LocalDate.of(2026, 11, 14)

    @Test
    fun birthdayTodaySortsFirstWithZeroDays() {
        val list = FacultyBirthdays.upcoming(
            listOf(teacher("TEACHER A", "1990-11-20"), teacher("TEACHER B", "1985-11-14")),
            today
        )
        assertEquals("TEACHER B", list.first().record.name)
        assertEquals(0, list.first().daysUntil)
    }

    @Test
    fun passedThisYearRollsToNextYearAndComputesTurningAge() {
        // Born 16 July 1974; on 14 Nov 2026 the next occurrence is 16 July 2027 → turns 53.
        val list = FacultyBirthdays.upcoming(listOf(teacher("HARPREET", "1974-07-16")), today)
        val only = list.single()
        assertEquals(LocalDate.of(2027, 7, 16), only.nextDate)
        assertEquals(LocalDate.of(1974, 7, 16), only.birthDate)
        assertEquals(53, only.turningAge)
        assertEquals(244, only.daysUntil)
    }

    @Test
    fun ascendingOrderAcrossYearBoundary() {
        val list = FacultyBirthdays.upcoming(
            listOf(
                teacher("LATE_DECEMBER", "1980-12-30"),
                teacher("JANUARY", "1980-01-02"),
                teacher("TOMORROW", "1980-11-15")
            ),
            today
        )
        assertEquals(listOf("TOMORROW", "LATE_DECEMBER", "JANUARY"), list.map { it.record.name })
    }

    @Test
    fun blankAndMalformedDatesAreSkipped() {
        val list = FacultyBirthdays.upcoming(
            listOf(teacher("NO_DOB", ""), teacher("BAD_DOB", "14/11/1990"), teacher("GOOD", "1990-11-15")),
            today
        )
        assertEquals(listOf("GOOD"), list.map { it.record.name })
        assertNull(FacultyBirthdays.parse(""))
        assertNull(FacultyBirthdays.parse("not a date"))
        assertEquals(LocalDate.of(1990, 11, 15), FacultyBirthdays.parse("1990-11-15"))
    }

    @Test
    fun duplicatePersonAcrossDepartmentsListedOnce() {
        // The college files Dr. Jasbir Singh Saini under two departments with
        // the same DOB — one card per person.
        val list = FacultyBirthdays.upcoming(
            listOf(
                teacher("DR. JASBIR SINGH SAINI", "1974-05-20", profileId = "1"),
                teacher("DR. JASBIR SINGH SAINI", "1974-05-20", profileId = "25")
            ),
            today
        )
        assertEquals(1, list.size)
    }

    @Test
    fun sameDayBirthdaysBreakTieByName() {
        val list = FacultyBirthdays.upcoming(
            listOf(teacher("ZEYA", "1990-11-15"), teacher("ABHA", "1991-11-15")),
            today
        )
        assertEquals(listOf("ABHA", "ZEYA"), list.map { it.record.name })
    }

    @Test
    fun feb29BirthdayLandsOnFeb28InNonLeapYear() {
        // Born 29 Feb 2000 → 2027 is not a leap year, so the occurrence is 28 Feb 2027.
        val next = FacultyBirthdays.nextOccurrence(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 11, 14))
        assertEquals(LocalDate.of(2027, 2, 28), next)
    }

    @Test
    fun limitCapsTheResult() {
        val records = (1..10).map { teacher("T$it", "1990-11-%02d".format(15 + it)) }
        assertEquals(3, FacultyBirthdays.upcoming(records, today, limit = 3).size)
    }

    @Test
    fun wishIsStableForSameNameAndDayButVariesAcrossNames() {
        val a = birthdayWishFor("TEACHER", today)
        assertEquals(a, birthdayWishFor("TEACHER", today))
        // Across the whole directory the wishes should not collapse to one line.
        val distinct = (1..60).map { birthdayWishFor("TEACHER$it", today) }.distinct()
        assertEquals(true, distinct.size > 1)
    }
}

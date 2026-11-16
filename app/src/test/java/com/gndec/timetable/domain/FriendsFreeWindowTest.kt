package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The partial-overlap friends fix. The old whole-window check
 * ([freeFriendNames]) reported nothing for a friend free only PART of the
 * queried period — free 12:30-1:30 while the class runs 12:30-2:30 — which is
 * exactly the case a student wants to see. [friendsFreeWithin] subtracts each
 * friend's lectures from the window and reports the real free stretches.
 */
class FriendsFreeWindowTest {

    private fun lecture(start: Int, end: Int, group: String = "ITB2") = LectureEntity(
        groupName = group,
        dayOfWeek = 1,
        startMinutes = start,
        endMinutes = end,
        subject = "Subject",
        teacher = null,
        venue = null,
        lectureType = null,
        rawText = "-",
        fetchId = 0
    )

    private fun friendDay(name: String, vararg lectures: LectureEntity, hasData: Boolean = true) =
        FriendDay(Friend(name, "ITB2"), lectures.toList(), hasData)

    private val ClassStart = 12 * 60 + 30 // 12:30 PM
    private val ClassEnd = 14 * 60 + 30 // 2:30 PM

    @Test
    fun friendFreeTheWholePeriodReportsTheWholeWindow() {
        val days = listOf(friendDay("Aarav", lecture(15 * 60, 16 * 60)))
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        assertEquals(listOf(FriendFreeWindow("Aarav", ClassStart, ClassEnd)), windows)
    }

    @Test
    fun friendFreeForOnlyTheFirstHalfNowShowsUp() {
        // The user's report: 2-hour period 12:30-2:30, friend free 12:30-1:30 only.
        val days = listOf(friendDay("Aarav", lecture(13 * 60 + 30, ClassEnd)))
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        assertEquals(listOf(FriendFreeWindow("Aarav", ClassStart, 13 * 60 + 30)), windows)
    }

    @Test
    fun friendFreeForOnlyTheSecondHalfShowsTheLateWindow() {
        val days = listOf(friendDay("Meera", lecture(ClassStart, 13 * 60 + 30)))
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        assertEquals(listOf(FriendFreeWindow("Meera", 13 * 60 + 30, ClassEnd)), windows)
    }

    @Test
    fun friendBusyInTheMiddleSplitsIntoTwoWindows() {
        val days = listOf(friendDay("Karan", lecture(13 * 60, 13 * 60 + 30)))
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        assertEquals(
            listOf(
                FriendFreeWindow("Karan", ClassStart, 13 * 60),
                FriendFreeWindow("Karan", 13 * 60 + 30, ClassEnd)
            ),
            windows
        )
    }

    @Test
    fun friendBusyTheWholePeriodReportsNothing() {
        val days = listOf(friendDay("Aarav", lecture(ClassStart, ClassEnd)))
        assertTrue(days.friendsFreeWithin(ClassStart, ClassEnd).isEmpty())
    }

    @Test
    fun friendWithoutTimetableDataIsSkipped() {
        val days = listOf(friendDay("Aarav", hasData = false))
        assertTrue(days.friendsFreeWithin(ClassStart, ClassEnd).isEmpty())
    }

    @Test
    fun friendWithNoClassesThatDayIsFreeTheWholeWindow() {
        val days = listOf(friendDay("Aarav"))
        assertEquals(listOf(FriendFreeWindow("Aarav", ClassStart, ClassEnd)), days.friendsFreeWithin(ClassStart, ClassEnd))
    }

    @Test
    fun lecturesEntirelyOutsideTheWindowAreIgnored() {
        val days = listOf(
            friendDay("Aarav", lecture(8 * 60, 9 * 60), lecture(15 * 60, 16 * 60))
        )
        assertEquals(listOf(FriendFreeWindow("Aarav", ClassStart, ClassEnd)), days.friendsFreeWithin(ClassStart, ClassEnd))
    }

    @Test
    fun lectureOverlappingOnlyTheStartClipsToTheWindow() {
        // Class 12:00-13:00 overlaps the window's start: free stretch is 1:00 PM - 2:30 PM.
        val days = listOf(friendDay("Aarav", lecture(12 * 60, 13 * 60)))
        assertEquals(listOf(FriendFreeWindow("Aarav", 13 * 60, ClassEnd)), days.friendsFreeWithin(ClassStart, ClassEnd))
    }

    @Test
    fun tinySeamGapsBelowTheMinimumAreNotReported() {
        // Free 12:30-1:25 and 1:30-2:30, but the 5-minute seam never mattered.
        val days = listOf(friendDay("Aarav", lecture(13 * 60 + 25, 13 * 60 + 30)))
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        // The 55-minute and 60-minute stretches both show; nothing is lost to the seam filter.
        assertEquals(
            listOf(
                FriendFreeWindow("Aarav", ClassStart, 13 * 60 + 25),
                FriendFreeWindow("Aarav", 13 * 60 + 30, ClassEnd)
            ),
            windows
        )
    }

    @Test
    fun sliversShorterThanTheMinimumAreDropped() {
        // Friend free only 12:30-12:35 (5 minutes) — below MinUsefulFreeMinutes.
        val days = listOf(friendDay("Aarav", lecture(ClassStart + 5, ClassEnd)))
        assertTrue(days.friendsFreeWithin(ClassStart, ClassEnd).isEmpty())
    }

    @Test
    fun multipleFriendsEachReportTheirOwnWindows() {
        val days = listOf(
            friendDay("Aarav", lecture(13 * 60 + 30, ClassEnd)), // free 12:30-1:30
            friendDay("Meera"), // free the whole period
            friendDay("Karan", lecture(ClassStart, ClassEnd)) // busy throughout
        )
        val windows = days.friendsFreeWithin(ClassStart, ClassEnd)
        assertEquals(
            listOf(
                FriendFreeWindow("Aarav", ClassStart, 13 * 60 + 30),
                FriendFreeWindow("Meera", ClassStart, ClassEnd)
            ),
            windows
        )
    }

    @Test
    fun emptyWindowReturnsNothing() {
        val days = listOf(friendDay("Aarav"))
        assertTrue(days.friendsFreeWithin(ClassStart, ClassStart).isEmpty())
    }

    @Test
    fun wholeWindowCheckStillAnswersForFullyFreeFriends() {
        // freeFriendNames stays the whole-period filter, used for the grouped sentence.
        val days = listOf(
            friendDay("Aarav", lecture(13 * 60 + 30, ClassEnd)), // partial only
            friendDay("Meera") // full
        )
        assertEquals(listOf("Meera"), days.freeFriendNames(ClassStart, ClassEnd))
    }
}

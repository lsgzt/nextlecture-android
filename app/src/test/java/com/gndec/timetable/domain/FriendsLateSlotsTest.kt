package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom mirror of the friends-before note: [lateSlotsAfter] reports each
 * friend's latest class that still runs after the student's last lecture of
 * the day ends, so the Today timeline can say "Vishal has a lecture until
 * 5:30 PM" below the rail.
 */
class FriendsLateSlotsTest {

    private fun lecture(start: Int, end: Int, type: String? = null, group: String = "ITB2") = LectureEntity(
        groupName = group,
        dayOfWeek = 1,
        startMinutes = start,
        endMinutes = end,
        subject = "Subject",
        teacher = null,
        venue = null,
        lectureType = type,
        rawText = "-",
        fetchId = 0
    )

    private fun friendDay(name: String, vararg lectures: LectureEntity, hasData: Boolean = true) =
        FriendDay(Friend(name, "ITB2"), lectures.toList(), hasData)

    private val MyDayEnds = 16 * 60 // 4:00 PM

    @Test
    fun friendWithALaterLectureIsReported() {
        val days = listOf(friendDay("Vishal", lecture(16 * 60 + 30, 17 * 60 + 30)))
        val slots = days.lateSlotsAfter(MyDayEnds)
        assertEquals(listOf(FriendLateSlot("Vishal", "lecture", 17 * 60 + 30)), slots)
    }

    @Test
    fun friendWhoseDayEndsWithMineIsNotAfter() {
        // Touching endpoints are not "after": 3:00-4:00 ends exactly at 4:00.
        val days = listOf(friendDay("Vishal", lecture(15 * 60, MyDayEnds)))
        assertTrue(days.lateSlotsAfter(MyDayEnds).isEmpty())
    }

    @Test
    fun friendWhoEndsEarlierIsNotReported() {
        val days = listOf(friendDay("Meera", lecture(13 * 60, 14 * 60 + 30)))
        assertTrue(days.lateSlotsAfter(MyDayEnds).isEmpty())
    }

    @Test
    fun picksTheLatestEndingLectureNotTheLatestStartingOne() {
        // 4:15-5:30 ends later than 5:00-5:15 even though it starts earlier.
        val days = listOf(
            friendDay("Vishal", lecture(16 * 60 + 15, 17 * 60 + 30), lecture(17 * 60, 17 * 60 + 15))
        )
        val slots = days.lateSlotsAfter(MyDayEnds)
        assertEquals(listOf(FriendLateSlot("Vishal", "lecture", 17 * 60 + 30)), slots)
    }

    @Test
    fun classSpanningMyEndCountsAsAfter() {
        // 3:30-4:30 straddles my 4:00 end — still "in class after I'm done".
        val days = listOf(friendDay("Karan", lecture(15 * 60 + 30, 16 * 60 + 30)))
        assertEquals(
            listOf(FriendLateSlot("Karan", "lecture", 16 * 60 + 30)),
            days.lateSlotsAfter(MyDayEnds)
        )
    }

    @Test
    fun practicalAndTutorialLabelsAreCanonicalised() {
        val days = listOf(
            friendDay("Simran", lecture(16 * 60 + 30, 17 * 60 + 30, "PRACTICAL")),
            friendDay("Raman", lecture(17 * 60, 18 * 60, "Tutorial"))
        )
        val slots = days.lateSlotsAfter(MyDayEnds)
        assertEquals(
            listOf(
                FriendLateSlot("Simran", "lab", 17 * 60 + 30),
                FriendLateSlot("Raman", "tutorial", 18 * 60)
            ),
            slots
        )
    }

    @Test
    fun sortedByEndTimeThenName() {
        val days = listOf(
            friendDay("Zoya", lecture(17 * 60, 18 * 60)),
            friendDay("Aarav", lecture(16 * 60 + 30, 18 * 60)),
            friendDay("Meera", lecture(16 * 60 + 30, 17 * 60))
        )
        val slots = days.lateSlotsAfter(MyDayEnds)
        assertEquals(
            listOf(
                FriendLateSlot("Meera", "lecture", 17 * 60),
                FriendLateSlot("Aarav", "lecture", 18 * 60),
                FriendLateSlot("Zoya", "lecture", 18 * 60)
            ),
            slots
        )
    }

    @Test
    fun friendsWithoutTimetableDataAreSkipped() {
        val days = listOf(
            friendDay("Ghost", hasData = false),
            friendDay("Vishal", lecture(16 * 60 + 30, 17 * 60 + 30))
        )
        val slots = days.lateSlotsAfter(MyDayEnds)
        assertEquals(listOf(FriendLateSlot("Vishal", "lecture", 17 * 60 + 30)), slots)
    }

    @Test
    fun friendWithNoLecturesAtAllIsNotReported() {
        val days = listOf(friendDay("Idle"))
        assertTrue(days.lateSlotsAfter(MyDayEnds).isEmpty())
    }
}

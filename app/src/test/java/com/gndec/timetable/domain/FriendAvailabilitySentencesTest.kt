package com.gndec.timetable.domain

import com.gndec.timetable.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence builder for the friend note under a lecture card. Friends free
 * for only part of a 2-hour class used to be listed one clause per friend even
 * when they shared the exact same free hour — "Raman is free at 11:30-12:30 ·
 * Prince is free at 11:30-12:30". They now share one clause joined with "and".
 * Friends free for the WHOLE class say "free at this time slot" without the
 * time range — the lecture card above already carries the slot, so repeating
 * it is noise; only partial free windows keep their own shorter time range.
 */
class FriendAvailabilitySentencesTest {

    private val label = Formatters::hm

    private val ClassStart = 11 * 60 + 30 // 11:30 AM
    private val ClassEnd = 13 * 60 + 30   // 1:30 PM (a 2-hour class)

    @Test
    fun twoFriendsFreeOverTheSamePartialHourShareOneClause() {
        val windows = listOf(
            FriendFreeWindow("Raman", ClassStart, 12 * 60 + 30),
            FriendFreeWindow("Prince", ClassStart, 12 * 60 + 30)
        )
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(1, sentences.size)
        assertEquals("Raman and Prince are free at 11:30 AM - 12:30 PM", sentences[0])
    }

    @Test
    fun threeFriendsFreeOverTheSameHourUseTheOxfordJoin() {
        val windows = listOf(
            FriendFreeWindow("Raman", ClassStart, 12 * 60 + 30),
            FriendFreeWindow("Prince", ClassStart, 12 * 60 + 30),
            FriendFreeWindow("Simran", ClassStart, 12 * 60 + 30)
        )
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(listOf("Raman, Prince and Simran are free at 11:30 AM - 12:30 PM"), sentences)
    }

    @Test
    fun singleFriendKeepsTheSimpleSentence() {
        val windows = listOf(FriendFreeWindow("Raman", ClassStart, 12 * 60 + 30))
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(listOf("Raman is free at 11:30 AM - 12:30 PM"), sentences)
    }

    @Test
    fun differentWindowsKeepSeparateClausesInTimeOrder() {
        val windows = listOf(
            FriendFreeWindow("Prince", 12 * 60 + 30, ClassEnd),
            FriendFreeWindow("Raman", ClassStart, 12 * 60 + 30)
        )
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(
            listOf(
                "Raman is free at 11:30 AM - 12:30 PM",
                "Prince is free at 12:30 PM - 1:30 PM"
            ),
            sentences
        )
    }

    @Test
    fun friendsFreeForTheWholeClassKeepTheGroupedSentence() {
        val windows = listOf(
            FriendFreeWindow("Aarav", ClassStart, ClassEnd),
            FriendFreeWindow("Meera", ClassStart, ClassEnd)
        )
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(listOf("Aarav and Meera are free at this time slot"), sentences)
    }

    @Test
    fun wholeClassFriendsAndPartialFriendsAreReportedTogether() {
        val windows = listOf(
            FriendFreeWindow("Aarav", ClassStart, ClassEnd),
            FriendFreeWindow("Raman", ClassStart, 12 * 60 + 30),
            FriendFreeWindow("Prince", ClassStart, 12 * 60 + 30)
        )
        val sentences = friendAvailabilitySentences(windows, ClassStart, ClassEnd, label)
        assertEquals(
            listOf(
                "Aarav is free at this time slot",
                "Raman and Prince are free at 11:30 AM - 12:30 PM"
            ),
            sentences
        )
    }

    @Test
    fun noWindowsYieldNoSentences() {
        assertTrue(friendAvailabilitySentences(emptyList(), ClassStart, ClassEnd, label).isEmpty())
    }
}

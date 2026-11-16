package com.gndec.timetable.ui.search

import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.parse.AcRooms
import com.gndec.timetable.parse.GlobalRoomData
import com.gndec.timetable.parse.MergedRoom
import com.gndec.timetable.parse.RoomSourceSummary
import com.gndec.timetable.parse.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Searching for the snowflake lists every air-conditioned room — badged — and
 * nothing else; combining it with text narrows to the AC rooms matching that
 * text. Plain room queries stay unbadged.
 */
class AcRoomSearchTest {

    // The real merged AC rooms (merge key -> display name), plus near-miss
    // rooms that must never appear for a snowflake query.
    private val acRooms = listOf(
        "F101" to "F101",
        "F108" to "F108",
        "F109" to "F109",
        "F112" to "F112",
        "F113" to "F113",
        "F114" to "F114",
        "BEE LAB 1" to "BEE LAB 1",
        "CHEM LAB 1" to "CHEM LAB 1",
        "COMPUTATIONAL LAB" to "COMPUTATIONAL LAB",
        "HW LAB" to "HW LAB",
        "OS LAB CSE DEPT" to "OS LAB CSE DEPT",
        "OS1 LAB IT DEPT" to "OS1 LAB IT DEPT",
        "PROJ LAB" to "PROJ/L",
        "TNP SEMINAR HALL" to "TNP SEMINAR HALL",
        "CENTRAL LIBRARY" to "Central Library",
        "cse:OS LAB" to "OS LAB · CSE"
    )

    private val otherRooms = listOf(
        "F107" to "F107",
        "F108A" to "F108A",
        "TNP SEMINAR HALL 1" to "TNP SEMINAR HALL 1",
        "BEE LAB 2" to "BEE LAB 2",
        "it:OS LAB" to "OS_Lab · IT"
    )

    private fun roomOf(key: String, name: String) = MergedRoom(
        key = key,
        name = name,
        occupancy = List(7) { List(1) { null } } // one slot, no coverage
    )

    private val roomData = GlobalRoomData(
        sources = listOf(
            RoomSourceSummary(
                rootId = "appsc",
                kind = SourceKind.ROOMS,
                url = "https://appsc",
                roomCount = 21,
                generatedAtMillis = null,
                fetchedAtMillis = 0L
            )
        ),
        incompleteRoots = emptyList(),
        days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        slotStarts = listOf(510),
        rooms = (acRooms + otherRooms).map { (key, name) -> roomOf(key, name) },
        fetchedAtMillis = 0L
    )

    private fun snapshot(rooms: GlobalRoomData?) = SearchSnapshot(
        settings = AppSettings(),
        lectures = emptyList(),
        notices = emptyList(),
        holidays = emptyList(),
        attendance = emptyList(),
        rooms = rooms,
        faculty = emptyList(),
        today = LocalDate.of(2026, 10, 7),
        nowMinutes = 600
    )

    private fun roomHits(query: String) =
        search(query, snapshot(roomData)).filter { it.group == SearchGroup.ROOM }

    @Test
    fun snowflakeQueryListsEveryAcRoomBadged() {
        val results = roomHits(AcRooms.SNOWFLAKE)
        // All 16, not the usual top-8 cut.
        assertEquals(acRooms.size, results.size)
        assertEquals(acRooms.map { it.second }.toSet(), results.map { it.title.removePrefix(AcRooms.SNOWFLAKE) }.toSet())
        assertTrue(results.all { it.title.startsWith(AcRooms.SNOWFLAKE) })
    }

    @Test
    fun snowflakeQueryHidesEveryNonAcRoom() {
        val titles = roomHits(AcRooms.SNOWFLAKE).map { it.title.removePrefix(AcRooms.SNOWFLAKE) }
        assertTrue(otherRooms.none { it.second in titles })
        assertFalse(titles.contains("F108A"))
        assertFalse(titles.contains("OS_Lab · IT"))
    }

    @Test
    fun bareSnowflakeGlyphBehavesLikeTheEmoji() {
        assertEquals(roomHits(AcRooms.SNOWFLAKE).size, roomHits("❄").size)
    }

    @Test
    fun snowflakeWithRoomTextNarrowsToThatAcRoom() {
        val results = roomHits("${AcRooms.SNOWFLAKE}F108")
        assertEquals(1, results.size)
        assertEquals("${AcRooms.SNOWFLAKE}F108", results[0].title)
    }

    @Test
    fun snowflakeWithPartialRoomTextStillMatches() {
        assertTrue(roomHits("${AcRooms.SNOWFLAKE} f1").any { it.title == "${AcRooms.SNOWFLAKE}F108" })
        assertTrue(roomHits("❄ chem").any { it.title == "${AcRooms.SNOWFLAKE}CHEM LAB 1" })
        // …but a non-AC room stays hidden even when the text alone would match.
        assertFalse(roomHits("${AcRooms.SNOWFLAKE}F107").any { it.title.contains("F107") })
    }

    @Test
    fun plainRoomQueriesBadgeAcRoomsLikeTheRoomListDoes() {
        val results = roomHits("F107")
        assertEquals(1, results.size)
        assertEquals("F107", results[0].title) // no AC, no badge
        // "Same in search bar": AC rooms carry the snowflake on their titles
        // even when the query itself is plain text…
        assertTrue(roomHits("F108").any { it.title == "${AcRooms.SNOWFLAKE}F108" })
        assertTrue(roomHits("OS LAB").any { it.title == "${AcRooms.SNOWFLAKE}OS LAB CSE DEPT" })
        // …and non-AC lookalikes stay bare next to them.
        assertTrue(roomHits("F108").any { it.title == "F108A" })
        assertTrue(roomHits("OS LAB").any { it.title == "OS_Lab · IT" })
    }

    @Test
    fun snowflakeQueryShowsLoadingHintWhileRoomsDownload() {
        val results = search(AcRooms.SNOWFLAKE, snapshot(rooms = null))
        assertTrue(results.any { it.id == ROOMS_LOADING_HINT })
    }
}

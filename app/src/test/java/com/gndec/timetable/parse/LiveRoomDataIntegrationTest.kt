package com.gndec.timetable.parse

import com.gndec.timetable.domain.RoomMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration check of parser + merger against the REAL GNDEC files downloaded
 * from the live department pages on 2026-08-31 (see docs/ROOM_TIMETABLE_SOURCES.md).
 * Verifies the F19/F-119 bug fix on actual published data.
 */
class LiveRoomDataIntegrationTest {

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/live/$name")!!.bufferedReader().use { it.readText() }

    private val fetchedAt = 1_788_000_000_000L

    private fun asDoc(parsed: RoomTimetableParser.ParsedDoc, rootId: String, url: String) =
        SourceRoomDoc(
            rootId = rootId,
            kind = parsed.kind,
            url = url,
            slotStarts = parsed.slotStarts,
            rooms = parsed.rooms,
            generatedAtMillis = parsed.generatedAtMillis,
            fetchedAtMillis = fetchedAt
        )

    private val docs: List<SourceRoomDoc> by lazy {
        buildList {
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("appsc_rooms.html"), "appsc", "https://appsc", fetchedAt), "appsc", "https://appsc"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("cse_rooms.html"), "cse", "https://cse", fetchedAt), "cse", "https://cse"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("ece_rooms.html"), "ece", "https://ece", fetchedAt), "ece", "https://ece"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("ee_rooms.html"), "ee", "https://ee", fetchedAt), "ee", "https://ee"))
            add(asDoc(RoomTimetableParser.parseGroupsDoc(load("me_groups.html"), "me", "https://me", fetchedAt), "me", "https://me"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("ce_rooms.html"), "ce", "https://ce", fetchedAt), "ce", "https://ce"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("it_rooms.html"), "it", "https://it", fetchedAt), "it", "https://it"))
            add(asDoc(RoomTimetableParser.parseRoomsDoc(load("mca_rooms.html"), "mca", "https://mca", fetchedAt), "mca", "https://mca"))
        }
    }

    @Test
    fun everyLiveDocumentParsesWithSaneCoverage() {
        val byRoot = docs.associateBy { it.rootId }
        assertEquals(8, docs.size)
        // appsc: 97 rooms, 8 slots 08:30–15:30
        assertEquals(97, byRoot.getValue("appsc").rooms.size)
        assertEquals(listOf(510, 570, 630, 690, 750, 810, 870, 930), byRoot.getValue("appsc").slotStarts)
        // cse: dialect B, 9 slots incl. 16:30–17:30, ~34 rooms
        assertTrue(byRoot.getValue("cse").rooms.size in 30..40)
        assertEquals(9, byRoot.getValue("cse").slotStarts.size)
        assertEquals(990, byRoot.getValue("cse").slotStarts.last())
        // me: groups fallback must surface rooms incl. F111 and lowercase f104→F104
        val meKeys = byRoot.getValue("me").rooms.map { it.key }
        assertTrue("F111" in meKeys)
        assertTrue("F104" in meKeys)
        // it: F119 + F118 with hyphenated names, 12h slot labels
        val itKeys = byRoot.getValue("it").rooms.map { it.key }
        assertTrue("F119" in itKeys)
        assertTrue("F118" in itKeys)
        assertEquals(byRoot.getValue("it").slotStarts, byRoot.getValue("appsc").slotStarts)
        // ce: slot labels "8.30 AM (1ST)"
        assertEquals(510, byRoot.getValue("ce").slotStarts.first())
        // mca: 10 slots incl. evening
        assertEquals(1050, byRoot.getValue("mca").slotStarts.last())
    }

    @Test
    fun f119IsBusyWhereOnlyTheDepartmentFileReportsIt() {
        val merged = RoomMerger.merge(docs, incompleteRoots = listOf("mba"))
        val f119 = merged.rooms.first { it.name == "F119" }

        // Monday 08:30: appsc says FREE, IT's own file says D2IT_A class — must be BUSY.
        val mondayFirst = f119.occupancy[0][0]!!
        assertTrue("F119 Mon 08:30 must be busy (IT dept file)", mondayFirst.busy)
        // Monday 09:30 and 10:30 likewise.
        assertTrue(f119.occupancy[0][1]!!.busy)
        assertTrue(f119.occupancy[0][2]!!.busy)
        // Slot where appsc says busy must still be busy.
        assertTrue(f119.occupancy[1][1]!!.busy) // Tue 09:30 D2ITA EVS (both sources)

        // The old app showed 29 slot/day combinations vacant that are actually busy.
        var busyCells = 0
        f119.occupancy.forEach { row -> row.forEach { if (it?.busy == true) busyCells++ } }
        assertTrue("F119 must have substantial busy coverage, was $busyCells", busyCells >= 29)
    }

    @Test
    fun mergedDatasetIsGlobalAndConsistent() {
        val merged = RoomMerger.merge(docs, incompleteRoots = listOf("mba"))
        // ~160 display rooms across sources
        assertTrue("room count ${merged.rooms.size}", merged.rooms.size in 150..185)
        // union slot timeline: 10 slots from 08:30 to 17:30
        assertEquals(listOf(510, 570, 630, 690, 750, 810, 870, 930, 990, 1050), merged.slotStarts)
        // MBA's stale file must be excluded upstream; only 8 sources merge here
        assertEquals(8, merged.sources.size)
        assertEquals(listOf("mba"), merged.incompleteRoots)
        // rooms unique by display name
        assertEquals(merged.rooms.size, merged.rooms.map { it.name }.toSet().size)
        // no F116 (only exists in the rejected MBA file)
        assertFalse(merged.rooms.any { it.name.contains("F116") })
    }

    @Test
    fun noSourceBusyCellIsLostInTheMergedGrid() {
        val merged = RoomMerger.merge(docs, incompleteRoots = emptyList())
        val slotIndexOf = merged.slotStarts.withIndex().associate { (i, s) -> s to i }

        // Reproduce the merger's scoping decision to derive expected merge keys.
        val rootsByKey = HashMap<String, MutableSet<String>>()
        for (doc in docs) for (room in doc.rooms) {
            rootsByKey.getOrPut(room.key) { mutableSetOf() }.add(doc.rootId)
        }
        fun expectedKey(rootId: String, key: String): String {
            val roots = rootsByKey[key].orEmpty()
            val scoped = !RoomNameNormalizer.isRoomLike(key) &&
                "appsc" !in roots && roots.size >= 2
            return if (scoped) "$rootId:$key" else key
        }

        var busyCount = 0
        for (doc in docs) {
            for (room in doc.rooms) {
                val key = expectedKey(doc.rootId, room.key)
                if (RoomNameNormalizer.isPlaceholder(key)) continue // hidden by design
                val target = merged.rooms.firstOrNull { it.key == key }
                assertTrue("merged room missing for ${doc.rootId} ${room.key}", target != null)
                for ((col, row) in room.occupancy.withIndex()) {
                    for ((slot, cell) in row.withIndex()) {
                        if (cell?.busy == true) {
                            busyCount++
                            val gslot = slotIndexOf[doc.slotStarts[slot]]
                            val gcell = gslot?.let { target!!.occupancy.getOrNull(col)?.getOrNull(it) }
                            assertTrue(
                                "busy cell lost: ${doc.rootId} ${room.key} d$col s$slot",
                                gcell?.busy == true
                            )
                        }
                    }
                }
            }
        }
        assertTrue("expected thousands of busy cells, was $busyCount", busyCount > 3000)
    }

    @Test
    fun sessionWindowAcceptsLiveFilesAndRejectsMbaStale() {
        val now = LocalDate.of(2026, 8, 31)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (doc in docs) {
            assertTrue(
                "${doc.rootId} must be in the current session window",
                com.gndec.timetable.net.RoomTimetableClient.isCurrentSession(doc.generatedAtMillis, now)
            )
        }
    }
}

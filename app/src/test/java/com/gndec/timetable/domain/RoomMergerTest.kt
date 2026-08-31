package com.gndec.timetable.domain

import com.gndec.timetable.parse.RoomCell
import com.gndec.timetable.parse.RoomNameNormalizer
import com.gndec.timetable.parse.SourceKind
import com.gndec.timetable.parse.SourceRoom
import com.gndec.timetable.parse.SourceRoomDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMergerTest {

    private fun doc(
        rootId: String,
        slotStarts: List<Int> = listOf(510, 570, 630),
        rooms: List<SourceRoom>,
        kind: SourceKind = SourceKind.ROOMS,
        url: String = "https://x/$rootId.html",
        generatedAt: Long? = 1_000L
    ): SourceRoomDoc = SourceRoomDoc(
        rootId = rootId,
        kind = kind,
        url = url,
        slotStarts = slotStarts,
        rooms = rooms,
        generatedAtMillis = generatedAt,
        fetchedAtMillis = 5_000L
    )

    private fun room(
        name: String,
        // grid[day][slot-of-doc]
        grid: List<List<RoomCell?>>
    ): SourceRoom = SourceRoom(
        key = RoomNameNormalizer.canonical(name) ?: name,
        name = name,
        occupancy = grid
    )

    @Test
    fun busyWinsAcrossSources() {
        // appsc says F119 free on Monday 08:30, IT says busy (the reported bug).
        val appsc = doc(
            "appsc",
            rooms = listOf(room("F119", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))))
        )
        val it = doc(
            "it",
            rooms = listOf(
                room(
                    "F-119",
                    listOf(listOf(RoomCell(busy = true, subject = "D2IT_A DL")), listOf(null), listOf(null))
                )
            )
        )
        val merged = RoomMerger.merge(listOf(appsc, it), incompleteRoots = emptyList())
        val f119 = merged.rooms.first { it.name == "F119" }
        val mondayFirst = f119.occupancy[0][0]!!
        assertTrue(mondayFirst.busy)
        assertEquals("D2IT_A DL", mondayFirst.subject)
    }

    @Test
    fun unknownCoverageIsNeverFree() {
        // Only IT covers Monday 08:30; the room is busy there. A slot covered by
        // no source stays null ("NO DATA"), and free-count excludes it.
        val it = doc(
            "it",
            rooms = listOf(
                room(
                    "F119",
                    listOf(
                        listOf(RoomCell(busy = true, subject = "X"), RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE)
                    )
                )
            )
        )
        val merged = RoomMerger.merge(listOf(it), incompleteRoots = emptyList())
        val f119 = merged.rooms.first()
        assertTrue(f119.occupancy[0][0]!!.busy)
        assertTrue(f119.occupancy[0][1]!!.isFree)
    }

    @Test
    fun slotTimelineIsUnionOfAllSources() {
        val appsc = doc(
            "appsc",
            slotStarts = listOf(510, 570),
            rooms = listOf(room("G12", List(5) { listOf(RoomCell.FREE, RoomCell.FREE) }))
        )
        val mca = doc(
            "mca",
            slotStarts = listOf(510, 570, 630, 690, 750, 810, 870, 930, 990, 1050),
            rooms = listOf(room("G13", List(5) { listOf(RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE, RoomCell.FREE) }))
        )
        val merged = RoomMerger.merge(listOf(appsc, mca), incompleteRoots = emptyList())
        assertEquals(
            listOf(510, 570, 630, 690, 750, 810, 870, 930, 990, 1050),
            merged.slotStarts
        )
        val g12 = merged.rooms.first { it.name == "G12" }
        assertTrue(g12.occupancy[0][0]!!.isFree)
        assertNull(g12.occupancy[0][2]) // MCA-only slot: unknown for G12
        val g13 = merged.rooms.first { it.name == "G13" }
        assertTrue(g13.occupancy[0][9]!!.isFree)
    }

    @Test
    fun occupancyRowsAreCanonicalDayRows() {
        // The parser emits canonical rows (0=Monday…), so a doc whose row 1 is
        // busy must surface as Tuesday (index 1) in the merged dataset.
        val appsc = SourceRoomDoc(
            rootId = "appsc",
            kind = SourceKind.ROOMS,
            url = "https://x",
            slotStarts = listOf(510),
            rooms = listOf(
                SourceRoom(
                    key = "F119",
                    name = "F119",
                    occupancy = listOf(
                        listOf(RoomCell.FREE),
                        listOf(RoomCell(busy = true, subject = "TUESDAY CLASS"))
                    )
                )
            ),
            generatedAtMillis = 1L,
            fetchedAtMillis = 1L
        )
        val merged = RoomMerger.merge(listOf(appsc), incompleteRoots = emptyList())
        val f119 = merged.rooms.first()
        assertTrue(f119.occupancy[1][0]!!.busy)
        assertEquals("TUESDAY CLASS", f119.occupancy[1][0]!!.subject)
        assertTrue(f119.occupancy[0][0]!!.isFree)
    }

    @Test
    fun genericNameCollisionsAcrossDepartmentsAreScoped() {
        // CE and ECE both publish a plain "COMP LAB" — genuinely different rooms.
        val ce = doc(
            "ce",
            rooms = listOf(
                room(
                    "COMP LAB",
                    listOf(listOf(RoomCell(busy = true, subject = "CE CLASS")), listOf(null), listOf(null))
                )
            )
        )
        val ece = doc(
            "ece",
            rooms = listOf(
                room(
                    "COMP LAB",
                    listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))
                )
            )
        )
        val merged = RoomMerger.merge(listOf(ce, ece), incompleteRoots = emptyList())
        val compLabs = merged.rooms.filter { it.name.startsWith("COMP LAB") }
        assertEquals(2, compLabs.size)
        // CE's copy is busy, ECE's copy is free — no cross-contamination.
        assertTrue(compLabs.any { it.name.endsWith("CE") && it.occupancy[0][0]!!.busy })
        assertTrue(compLabs.any { it.name.endsWith("ECE") && it.occupancy[0][0]!!.isFree })
    }

    @Test
    fun genericNamesMergeWhenCollegeWideFileIsInvolved() {
        // "BEE LAB 1" appears in the college-wide file and in EE's — same room.
        val appsc = doc(
            "appsc",
            rooms = listOf(
                room(
                    "BEE LAB 1",
                    listOf(listOf(RoomCell(busy = true, subject = "BEE")), listOf(null), listOf(null))
                )
            )
        )
        val ee = doc(
            "ee",
            rooms = listOf(
                room(
                    "BEE LAB 1",
                    listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))
                )
            )
        )
        val merged = RoomMerger.merge(listOf(appsc, ee), incompleteRoots = emptyList())
        val beeLabs = merged.rooms.filter { it.name.contains("BEE LAB 1") }
        assertEquals(1, beeLabs.size)
        assertTrue(beeLabs[0].occupancy[0][0]!!.busy)
    }

    @Test
    fun placeholdersAreHidden() {
        val appsc = doc(
            "ce",
            rooms = listOf(
                room("GHOST ROOM", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("TEACH OFFICE", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("A", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("F119", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null)))
            )
        )
        val merged = RoomMerger.merge(listOf(appsc), incompleteRoots = emptyList())
        assertEquals(listOf("F119"), merged.rooms.map { it.name })
    }

    @Test
    fun displayNamesPreferCollegeWideThenShortest() {
        val appsc = doc(
            "appsc",
            rooms = listOf(room("F119", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))))
        )
        val it = doc(
            "it",
            rooms = listOf(room("F-119", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))))
        )
        val merged = RoomMerger.merge(listOf(appsc, it), incompleteRoots = emptyList())
        assertEquals("F119", merged.rooms.single().name)
    }

    @Test
    fun incompleteRootsAreCarriedIntoTheDataset() {
        val appsc = doc(
            "appsc",
            rooms = listOf(room("F119", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))))
        )
        val merged = RoomMerger.merge(listOf(appsc), incompleteRoots = listOf("mba", "me"))
        assertEquals(listOf("mba", "me"), merged.incompleteRoots)
        assertEquals(1, merged.sources.size)
        assertEquals(7, merged.days.size)
        assertEquals("Monday", merged.days.first())
    }

    @Test
    fun numberedClassroomsSortBeforeLabsAndByName() {
        val appsc = doc(
            "appsc",
            rooms = listOf(
                room("DBMS LAB", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("G14", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("F101", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("S205", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null))),
                room("G13", listOf(listOf(RoomCell.FREE), listOf(null), listOf(null)))
            )
        )
        val merged = RoomMerger.merge(listOf(appsc), incompleteRoots = emptyList())
        assertEquals(listOf("F101", "G13", "G14", "S205", "DBMS LAB"), merged.rooms.map { it.name })
    }

    @Test
    fun freeCountInvariantHoldsForTheValidatedScenario() {
        // End-to-end: appsc-only view vs merged view for the F119 Monday 08:30
        // case — the old implementation reported vacant, the new one must not.
        val appsc = doc(
            "appsc",
            rooms = listOf(
                room(
                    "F119",
                    listOf(
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE)
                    )
                )
            )
        )
        val it = doc(
            "it",
            rooms = listOf(
                room(
                    "F-119",
                    listOf(
                        listOf(
                            RoomCell(busy = true, subject = "D2IT_A Dr. Palwinder Kaur-PK DL L"),
                            RoomCell(busy = true, subject = "D2IT_A Dr. Mohanjit Kaur Kang-MJK DCCN L")
                        ),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE),
                        listOf(RoomCell.FREE, RoomCell.FREE)
                    )
                )
            )
        )
        val merged = RoomMerger.merge(listOf(appsc, it), incompleteRoots = emptyList())
        val f119 = merged.rooms.first { it.name == "F119" }
        assertFalse("F119 Monday 08:30 must be BUSY", f119.occupancy[0][0]!!.isFree)
        assertFalse("F119 Monday 09:30 must be BUSY", f119.occupancy[0][1]!!.isFree)
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}

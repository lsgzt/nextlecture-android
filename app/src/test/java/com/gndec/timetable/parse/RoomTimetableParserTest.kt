package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RoomTimetableParserTest {

    /** Mirrors the FET "rooms days horizontal" shape published by the college. */
    private fun fixture(): String = """
        <!DOCTYPE html><html><body id="top">
        <table><tr><th>Institution name:</th><td>GNDEC</td></tr></table>
        <ul>
          <li><a href="#table_1">F101</a></li>
          <li><a href="#table_2">S205</a></li>
        </ul>
        <table id="table_1" border="1">
          <caption><span class="institution">GNDEC</span><br /><span class="name">F101</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr>
              <th class="yAxis">08:30</th>
              <td class="empty"><span class="empty">---</span></td>
              <td class="s_4 at_1 ss_33 t_23"><div class="studentsset line1"><span class="ss_33">CSA</span></div><div class="teacher line2"><span class="t_23">Ms. MANJOT KAUR</span></div><div class="line3"><span class="subject"><span class="s_4">PROFESSIONAL ENGLISH</span></span><span class="activitytag"> <span class="at_1">L</span></span></div></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
            </tr>
            <tr>
              <th class="yAxis">09:30</th>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td rowspan="2" class="s_8 at_3 ss_13 t_47"><div class="studentsset line1"><span class="ss_13">CEB2</span></div><div class="teacher line2"><span class="t_47">ER. LAKHVEER SINGH</span></div><div class="line3"><span class="subject"><span class="s_8">ENGG DRAWING</span></span><span class="activitytag"> <span class="at_3">P</span></span></div></td>
              <td class="empty"><span class="empty">---</span></td>
            </tr>
            <tr>
              <th class="yAxis">10:30</th>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <!-- span -->
              <td class="empty"><span class="empty">---</span></td>
            </tr>
            <tr><td colspan="5">Timetable generated with FET 7.6.4</td></tr>
          </tbody>
        </table>
        <table id="table_2" border="1">
          <caption><span class="institution">GNDEC</span><br /><span class="name">S205</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr>
              <th class="yAxis">08:30</th>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
            </tr>
          </tbody>
        </table>
        </body></html>
    """.trimIndent()

    private fun parse(): RoomTimetableData =
        RoomTimetableParser.parse(fixture(), "https://appsc.gndec.ac.in/rooms.html", 1_700_000_000_000L)

    @Test
    fun parsesRoomNamesInDocumentOrderAndDeduplicatesAcrossTables() {
        val data = parse()
        assertEquals(listOf("F101", "S205"), data.rooms.map { it.name })
    }

    @Test
    fun parsesWeekdaysAndSlotLabels() {
        val data = parse()
        assertEquals(listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"), data.days)
        assertEquals(listOf("08:30", "09:30", "10:30"), data.slots)
        assertEquals("https://appsc.gndec.ac.in/rooms.html", data.sourceUrl)
    }

    @Test
    fun emptyCellsAreFreeAndStructuredCellsAreBusyWithDetails() {
        val data = parse()
        val f101 = data.rooms.first { it.name == "F101" }
        // Monday 08:30 — empty
        val mondayFirst = f101.occupancy[0][0]
        assertFalse(mondayFirst.busy)
        assertNull(mondayFirst.subject)
        // Tuesday 08:30 — lecture cell
        val tuesdayFirst = f101.occupancy[1][0]
        assertTrue(tuesdayFirst.busy)
        assertEquals("PROFESSIONAL ENGLISH", tuesdayFirst.subject)
        assertEquals("Ms. MANJOT KAUR", tuesdayFirst.teacher)
        assertEquals("CSA", tuesdayFirst.studentsSet)
        assertEquals("L", tuesdayFirst.activity)
    }

    @Test
    fun rowspanCarriesBusyStateIntoFollowingSlots() {
        val data = parse()
        val f101 = data.rooms.first { it.name == "F101" }
        // Thursday 09:30 rowspan=2 practical → also busy at 10:30
        val start = f101.occupancy[3][1]
        val carry = f101.occupancy[3][2]
        assertTrue(start.busy)
        assertEquals("ENGG DRAWING", start.subject)
        assertEquals("P", start.activity)
        assertTrue("rowspan continuation slot must be busy", carry.busy)
        assertEquals("ENGG DRAWING", carry.subject)
        assertEquals("ER. LAKHVEER SINGH", carry.teacher)
        // the <!-- span --> placeholder column must not shift alignment:
        // Thursday 10:30 is the carried cell (index 3), Friday 10:30 stays free
        assertFalse(f101.occupancy[4][2].busy)
    }

    @Test
    fun footerRowWithoutTimeHeaderIsIgnored() {
        val data = parse()
        // No "FET" text leaked into any cell and slot count unchanged
        assertEquals(3, data.slots.size)
        data.rooms.forEach { room ->
            room.occupancy.forEach { day -> day.forEach { cell ->
                assertFalse(cell.subject?.contains("FET", ignoreCase = true) == true)
            } }
        }
    }

    @Test(expected = ParseException::class)
    fun blankHtmlThrows() {
        RoomTimetableParser.parse("", "x", 0L)
    }

    @Test(expected = ParseException::class)
    fun documentWithoutRoomTablesThrows() {
        RoomTimetableParser.parse("<html><body><p>nothing</p></body></html>", "x", 0L)
    }

    // ---- VacantRoomsManager pure helpers ----

    @Test
    fun defaultDayIndexPrefersTodayAndFallsBackToFirstTeachingDay() {
        val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
        val monday = LocalDate.of(2026, 8, 31) // a Monday
        val saturday = LocalDate.of(2026, 9, 5)
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultDayIndex(days, monday))
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultDayIndex(days, saturday))
    }

    @Test
    fun defaultSlotIndexFollowsClock() {
        val slots = listOf("08:30", "09:30", "10:30", "11:30", "12:30", "13:30", "14:30", "15:30")
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, LocalTime.of(7, 0)))
        assertEquals(2, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, LocalTime.of(10, 37)))
        assertEquals(1, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, LocalTime.of(10, 0)))
        assertEquals(7, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, LocalTime.of(18, 0)))
    }

    @Test
    fun currentSlotDetectionUsesHalfOpenRange() {
        assertTrue(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot("10:30", LocalTime.of(10, 30)))
        assertTrue(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot("10:30", LocalTime.of(11, 29)))
        assertFalse(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot("10:30", LocalTime.of(11, 30)))
    }

    @Test
    fun slotStartAndEndParsing() {
        assertEquals(8 * 60 + 30, com.gndec.timetable.domain.VacantRoomsManager.slotStartMinutes("08:30"))
        assertEquals(9 * 60 + 30, com.gndec.timetable.domain.VacantRoomsManager.slotEndMinutes("08:30"))
        assertNull(com.gndec.timetable.domain.VacantRoomsManager.slotStartMinutes("bogus"))
    }
}

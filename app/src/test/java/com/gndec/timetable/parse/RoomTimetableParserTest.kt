package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTimetableParserTest {

    /** Mirrors the FET 7.x "rooms days horizontal" shape (appsc / ECE / IT …). */
    private fun dialectAFixture(): String = """
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
            <tr><td colspan="5">Timetable generated with FET 7.6.4 on 8/30/26 10:39&nbsp;PM</td></tr>
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

    /** Mirrors the FET 6.x shape published by CSE. */
    private fun dialectBFixture(): String = """
        <html><body>
        <table id="table_1" border="1" class="odd_table">
          <caption>GNDEC Ludhiana</caption>
          <thead>
            <tr><td rowspan="2"></td><th colspan="5">G12</th></tr>
            <tr>
              <!-- span -->
              <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
              <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th class="yAxis">08:30-09:30</th>
              <td>---</td>
              <td>---</td>
              <td>D3 CS C<br />Dr. Manpreet Kaur Mand (MKM)<br />DAA L<br /></td>
              <td>---</td>
              <td>D2 CS F1<br />Pf. Lakhvir Kaur (LKG)<br />CA T<br /></td>
            </tr>
            <tr>
              <th class="yAxis">09:30-10:30</th>
              <td rowspan="2">D3 CS B<br />Pf. Manjot Singh Bedi (MPE)<br />ERP L<br /></td>
              <td>---</td>
              <td>---</td>
              <td>---</td>
              <td>---</td>
            </tr>
            <tr>
              <th class="yAxis">10:30-11:30</th>
              <!-- span -->
              <td>---</td>
              <td>---</td>
              <td>---</td>
              <td>---</td>
            </tr>
            <tr><td colspan="5">Timetable generated with FET 6.13.2 on 8/24/26 1:24\u202fPM</td></tr>
          </tbody>
        </table>
        </body></html>
    """.trimIndent()

    private fun parseA(): RoomTimetableParser.ParsedDoc =
        RoomTimetableParser.parseRoomsDoc(dialectAFixture(), "appsc", "https://x/rooms.html", 1_700_000_000_000L)

    private fun parseB(): RoomTimetableParser.ParsedDoc =
        RoomTimetableParser.parseRoomsDoc(dialectBFixture(), "cse", "https://x/cse.html", 1_700_000_000_000L)

    // ---- dialect A ----

    @Test
    fun dialectAParsesRoomNamesSlotsAndDays() {
        val doc = parseA()
        assertEquals(listOf("F101", "S205"), doc.rooms.map { it.name })
        assertEquals(listOf(510, 570, 630), doc.slotStarts)
        assertEquals(listOf(0, 1, 2, 3, 4), doc.days)
    }

    @Test
    fun dialectACellsCarryStructuredDetails() {
        val doc = parseA()
        val f101 = doc.rooms.first { it.key == "F101" }
        val mondayFirst = f101.occupancy[0][0]
        assertTrue(mondayFirst!!.isFree)
        val tuesdayFirst = f101.occupancy[1][0]!!
        assertTrue(tuesdayFirst.busy)
        assertEquals("PROFESSIONAL ENGLISH", tuesdayFirst.subject)
        assertEquals("Ms. MANJOT KAUR", tuesdayFirst.teacher)
        assertEquals("CSA", tuesdayFirst.studentsSet)
        assertEquals("L", tuesdayFirst.activity)
    }

    @Test
    fun rowspanCarriesBusyStateIntoFollowingSlots() {
        val doc = parseA()
        val f101 = doc.rooms.first { it.key == "F101" }
        val start = f101.occupancy[3][1]!!
        val carry = f101.occupancy[3][2]!!
        assertTrue(start.busy)
        assertEquals("ENGG DRAWING", start.subject)
        assertTrue("rowspan continuation slot must be busy", carry.busy)
        assertEquals("ENGG DRAWING", carry.subject)
        // The <!-- span --> placeholder column must not shift alignment:
        // Friday 10:30 stays free.
        assertTrue(f101.occupancy[4][2]!!.isFree)
    }

    @Test
    fun footerRowWithoutTimeHeaderIsIgnored() {
        val doc = parseA()
        assertEquals(3, doc.slotStarts.size)
        doc.rooms.forEach { room ->
            room.occupancy.forEach { day ->
                day.forEach { cell -> assertNull(cell?.subject?.contains("FET")?.takeIf { it }) }
            }
        }
    }

    // ---- dialect B (CSE) ----

    @Test
    fun dialectBParsesColspanRoomName() {
        val doc = parseB()
        assertEquals(listOf("G12"), doc.rooms.map { it.name })
        assertEquals(listOf(510, 570, 630), doc.slotStarts)
    }

    @Test
    fun dialectBPlainCellsAreParsedIntoDetails() {
        val doc = parseB()
        val g12 = doc.rooms.first()
        val wednesday = g12.occupancy[2][0]!!
        assertTrue(wednesday.busy)
        assertEquals("DAA", wednesday.subject) // activity token stripped into tag
        assertEquals("Dr. Manpreet Kaur Mand (MKM)", wednesday.teacher)
        assertEquals("D3 CS C", wednesday.studentsSet)
        assertEquals("L", wednesday.activity)
        val friday = g12.occupancy[4][0]!!
        assertEquals("T", friday.activity)
        val monday = g12.occupancy[0][0]!!
        assertTrue(monday.isFree)
    }

    @Test
    fun dialectBRowspanCarryWorks() {
        val doc = parseB()
        val g12 = doc.rooms.first()
        assertTrue(g12.occupancy[0][1]!!.busy) // Monday 09:30 rowspan start
        assertTrue(g12.occupancy[0][2]!!.busy) // carried into 10:30
        assertTrue(g12.occupancy[1][2]!!.isFree) // Tuesday 10:30 untouched
    }

    // ---- slot label variants ----

    @Test
    fun slotLabelsAcrossDialectsParseToMinutes() {
        assertEquals(510, RoomTimetableParser.slotStartMinutes("08:30"))
        assertEquals(510, RoomTimetableParser.slotStartMinutes("8:30"))
        assertEquals(510, RoomTimetableParser.slotStartMinutes("08:30-09:30"))
        assertEquals(810, RoomTimetableParser.slotStartMinutes("1:30")) // IT's PM-implied
        assertEquals(930, RoomTimetableParser.slotStartMinutes("3:30"))
        assertEquals(510, RoomTimetableParser.slotStartMinutes("8.30 AM (1ST)")) // CE
        assertEquals(750, RoomTimetableParser.slotStartMinutes("12.30 PM (5TH)"))
        assertEquals(810, RoomTimetableParser.slotStartMinutes("1.30 PM (6TH)"))
        assertEquals(990, RoomTimetableParser.slotStartMinutes("16:30"))
        assertNull(RoomTimetableParser.slotStartMinutes("bogus"))
        assertNull(RoomTimetableParser.slotStartMinutes("Timetable generated with FET"))
    }

    // ---- day label variants ----

    @Test
    fun dayResolutionFallsBackToColumnPosition() {
        val html = """
            <table id="table_1"><caption><span class="name">F119</span></caption>
            <thead><tr><td></td>
            <th class="xAxis">M</th><th class="xAxis">T</th><th class="xAxis">W</th>
            <th class="xAxis">T</th><th class="xAxis">F</th></tr></thead>
            <tbody><tr><th class="yAxis">08:30</th>
            <td class="empty">---</td><td class="empty">---</td><td class="empty">---</td>
            <td class="empty">---</td><td class="empty">---</td></tr></tbody></table>
        """.trimIndent()
        val doc = RoomTimetableParser.parseRoomsDoc(html, "ece", "https://x", 0L)
        assertEquals(listOf(0, 1, 2, 3, 4), doc.days)
    }

    // ---- combined room captions ----

    @Test
    fun commaSeparatedCaptionProducesOneRoomPerName() {
        val html = """
            <table id="table_1"><caption><span class="name">S202, S203</span></caption>
            <thead><tr><td></td><th class="xAxis">Monday</th></tr></thead>
            <tbody><tr><th class="yAxis">08:30</th>
            <td class="s_1"><span class="subject"><span class="s_1">WS</span></span></td></tr></tbody></table>
        """.trimIndent()
        val doc = RoomTimetableParser.parseRoomsDoc(html, "cse", "https://x", 0L)
        assertEquals(listOf("S202", "S203"), doc.rooms.map { it.name })
        assertEquals(listOf("S202", "S203"), doc.rooms.map { it.key })
        assertTrue(doc.rooms[0].occupancy[0][0]!!.busy)
    }

    // ---- groups documents (ME fallback) ----

    @Test
    fun groupsDocExtractsRoomsFromCellSpans() {
        val html = """
            <table id="table_1"><caption><span class="name">D1 ME A</span></caption>
            <thead><tr><td></td><th class="xAxis">Monday</th><th class="xAxis">Tuesday</th></tr></thead>
            <tbody>
              <tr><th class="yAxis">08:30-09:30</th>
                <td><table class="detailed">
                  <tr class="line1"><td class="s_4"><span class="subject"><span class="s_4">PPS</span></span><span class="activitytag">P</span></td></tr>
                  <tr class="room line3"><td class="s_4"><span class="r_13">CG LAB</span></td></tr>
                </table></td>
                <td class="empty">---</td>
              </tr>
              <tr><th class="yAxis">09:30-10:30</th>
                <td rowspan="1"><table class="detailed">
                  <tr class="room line3"><td class="s_1"><span class="r_18">S202</span></td></tr>
                </table></td>
                <td rowspan="1"><table class="detailed">
                  <tr class="room line3"><td class="s_1"><span class="r_18">S202</span></td></tr>
                </table></td>
              </tr>
            </tbody></table>
        """.trimIndent()
        val doc = RoomTimetableParser.parseGroupsDoc(html, "me", "https://x", 0L)
        val cgLab = doc.rooms.first { it.key == "CG LAB" }
        assertTrue(cgLab.occupancy[0][0]!!.busy)
        assertTrue(cgLab.occupancy[0][1] == null || cgLab.occupancy[0][1]!!.isFree)
        val s202 = doc.rooms.first { it.key == "S202" }
        assertTrue(s202.occupancy[0][1]!!.busy)
        assertTrue(s202.occupancy[1][1]!!.busy)
    }

    // ---- generated-at parsing ----

    @Test
    fun parsesFetGenerationTimestamps() {
        val ts = RoomTimetableParser.parseGeneratedAtMillis(
            "Timetable generated with FET 7.6.4 on 8/30/26 10:39\u202fPM"
        )
        assertTrue(ts != null)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts!! }
        assertEquals(java.util.Calendar.AUGUST, cal.get(java.util.Calendar.MONTH))
        assertEquals(30, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(22, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(39, cal.get(java.util.Calendar.MINUTE))
        assertTrue(RoomTimetableParser.parseGeneratedAtMillis("no footer") == null)
    }

    // ---- errors ----

    @Test(expected = ParseException::class)
    fun blankHtmlThrows() {
        RoomTimetableParser.parseRoomsDoc("", "x", "x", 0L)
    }

    @Test(expected = ParseException::class)
    fun documentWithoutRoomTablesThrows() {
        RoomTimetableParser.parseRoomsDoc("<html><body><p>nothing</p></body></html>", "x", "x", 0L)
    }

    // ---- VacantRoomsManager pure helpers ----

    @Test
    fun defaultDayIndexPrefersTodayAndFallsBackToMondayOnWeekend() {
        val monday = java.time.LocalDate.of(2026, 8, 31)
        val saturday = java.time.LocalDate.of(2026, 9, 5)
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultDayIndex(monday))
        assertEquals(2, com.gndec.timetable.domain.VacantRoomsManager.defaultDayIndex(java.time.LocalDate.of(2026, 9, 2)))
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultDayIndex(saturday))
    }

    @Test
    fun defaultSlotIndexFollowsClock() {
        val slots = listOf(510, 570, 630, 690, 750, 810, 870, 930)
        assertEquals(0, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, at(7, 0)))
        assertEquals(2, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, at(10, 37)))
        assertEquals(1, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, at(10, 0)))
        assertEquals(7, com.gndec.timetable.domain.VacantRoomsManager.defaultSlotIndex(slots, at(18, 0)))
    }

    @Test
    fun currentSlotDetectionUsesHalfOpenRange() {
        assertTrue(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot(630, 630))
        assertTrue(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot(630, 689))
        assertFalse(com.gndec.timetable.domain.VacantRoomsManager.isCurrentSlot(630, 690))
    }

    private fun at(h: Int, m: Int): Long =
        java.time.LocalDateTime.of(2026, 8, 31, h, m)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacultyTimetableParserTest {

    /** Dialect A-simple (Applied Science / IT / EE / ECE): caption name + plain-text cells. */
    private fun plainDialectFixture(): String = """
        <!DOCTYPE html><html><body id="top">
        <ul>
          <li><a href="#table_1">DR D S PATHANIA</a></li>
          <li><a href="#table_2">Dr. Akshay Girdhar-AG</a></li>
        </ul>
        <table id="table_1" border="1" class="odd_table">
          <caption><span class="institution">GNDEC</span><br /><span class="name">DR D S PATHANIA</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr><th class="yAxis">08:30</th>
              <td>---</td><td>---</td><td>---</td><td>---</td><td>---</td></tr>
            <tr><th class="yAxis">09:30</th>
              <td>---</td>
              <td>ECB1<br />MATH I T<br />F113<br /></td>
              <td>---</td><td>---</td><td>---</td></tr>
            <tr><th class="yAxis">13:30</th>
              <td>CSA1<br />MATH I T<br />F106<br /></td>
              <td>---</td><td>---</td><td>---</td><td>---</td></tr>
            <tr class="foot"><td></td><td colspan="5">Timetable generated with FET 7.10.5 on 9/17/26 10:55 PM</td></tr>
          </tbody>
        </table>
        <table id="table_2" border="1" class="odd_table">
          <caption><span class="institution">GNDEC (IT)</span><br /><span class="name">Dr. Akshay Girdhar-AG</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr><th class="yAxis">12:30</th>
              <td rowspan="2">M.Tech_1<br />M.Tech_DIP_Lab P<br />HW_Lab<br /></td>
              <td>---</td><td>---</td><td>-x-</td><td>-x-</td></tr>
            <tr><th class="yAxis">1:30</th>
              <!-- span -->
              <td>---</td><td>---</td><td>-x-</td><td>-x-</td></tr>
            <tr class="foot"><td></td><td colspan="5">Timetable generated with FET 7.6.4 on 8/27/26 12:39 PM</td></tr>
          </tbody>
        </table>
        </body></html>
    """.trimIndent()

    /** Dialect B (CSE): institution caption + teacher in th[colspan] + range y-labels. */
    private fun cseDialectFixture(): String = """
        <html><body>
        <ul><li><a href="#table_1">Dr. Parminder Singh (PS)</a></li></ul>
        <table id="table_1" border="1" class="odd_table">
          <caption>GNDEC Ludhiana</caption>
          <thead>
            <tr><td rowspan="2"></td><th colspan="5">Dr. Parminder Singh (PS)</th></tr>
            <tr>
              <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
              <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
            </tr>
          </thead>
          <tbody>
            <tr><th class="yAxis">09:30-10:30</th>
              <td>---</td><td>---</td><td>---</td>
              <td>D3 CS D<br />TOC L<br />G14<br /></td>
              <td>---</td></tr>
            <tr><th class="yAxis">12:30-13:30</th>
              <td>---</td><td>---</td><td>---</td>
              <td>Dr. Parminder Singh (PS), Dr. Kiran Jyoti (KJ)<br />M3<br />P.TH. Pre Thesis<br />DEPT LIB<br /></td>
              <td>---</td></tr>
            <tr class="foot"><td></td><td colspan="5">Timetable generated with FET 6.13.2 on 8/24/26 1:02 PM</td></tr>
          </tbody>
        </table>
        </body></html>
    """.trimIndent()

    /** Dialect A-detailed (ME): bare code caption + structured cells. */
    private fun meDialectFixture(): String = """
        <html><body>
        <ul>
          <li><a href="#table_1">SSK (Dr. Sehijpal Singh)</a></li>
          <li><a href="#table_2">CH-1</a></li>
        </ul>
        <table id="table_1" border="1" class="odd_table">
          <caption><span class="institution">M&amp;PE Department, GNDEC</span><br /><span class="name">SSK</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr><th class="yAxis">08:30-09:30</th>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="empty"><span class="empty">---</span></td>
              <td class="s_27 at_1 ss_62 t_1"><div class="studentsset line1"><span class="ss_62">D4 ME MANUFACTURING</span></div><div class="line2"><span class="subject"><span class="s_27">MS</span></span><span class="activitytag"> <span class="at_1">L</span></span></div><div class="room line3"><span class="r_10">COMPUTATIONAL LAB</span></div></td>
            </tr>
            <tr class="foot"><td></td><td colspan="5">Timetable generated with FET 7.6.4 on 8/20/26 11:00 AM</td></tr>
          </tbody>
        </table>
        <table id="table_2" border="1" class="odd_table">
          <caption><span class="institution">M&amp;PE Department, GNDEC</span><br /><span class="name">CH-1</span></caption>
          <thead><tr>
            <td></td>
            <th class="xAxis">Monday</th><th class="xAxis">Tuesday</th><th class="xAxis">Wednesday</th>
            <th class="xAxis">Thursday</th><th class="xAxis">Friday</th>
          </tr></thead>
          <tbody>
            <tr><th class="yAxis">08:30-09:30</th>
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

    @Test
    fun parsesPlainDialectCellsIntoSubjectAndRoom() {
        val doc = FacultyTimetableParser.parse(plainDialectFixture(), "appsc", "https://appsc.example/x.html", 1000L)
        assertEquals(2, doc.tables.size)

        val pathania = doc.tables.first { it.code == null && it.teacherName == "DR D S PATHANIA" }
        val tueSlot = pathania.slots.first { it.day == 1 }
        assertEquals(9 * 60 + 30, tueSlot.startMinutes)
        assertEquals("MATH I T", tueSlot.subject)
        assertEquals("F113", tueSlot.room)
        assertEquals("ECB1", tueSlot.studentsSet)

        val monSlot = pathania.slots.first { it.day == 0 }
        assertEquals(13 * 60 + 30, monSlot.startMinutes)
    }

    @Test
    fun parsesItTwelveHourLabelsAndRowspanLabs() {
        val doc = FacultyTimetableParser.parse(plainDialectFixture(), "it", "https://it.example/x.html", 1000L)
        val girdhar = doc.tables.first { it.teacherName == "Dr. Akshay Girdhar" }
        assertEquals("AG", girdhar.code)

        // "1:30" with no meridiem and hour <= 7 means 13:30 in IT's file.
        val mondaySlots = girdhar.slots.filter { it.day == 0 }.sortedBy { it.startMinutes }
        assertEquals(2, mondaySlots.size)
        assertEquals(12 * 60 + 30, mondaySlots[0].startMinutes)
        assertEquals(13 * 60 + 30, mondaySlots[1].startMinutes) // rowspan carry
        assertEquals("HW_Lab", mondaySlots[0].room)
        assertEquals("HW_Lab", mondaySlots[1].room) // the 2-hour lab occupies both rows
    }

    @Test
    fun parsesCseDialectColspanNameAndRangeLabels() {
        val doc = FacultyTimetableParser.parse(cseDialectFixture(), "cse", "https://cse.example/x.html", 1000L)
        assertEquals(1, doc.tables.size)
        val ps = doc.tables[0]
        assertEquals("Dr. Parminder Singh", ps.teacherName)
        assertEquals("PS", ps.code)

        val thuSlot = ps.slots.first { it.day == 3 }
        assertEquals(9 * 60 + 30, thuSlot.startMinutes)
        assertEquals("TOC L", thuSlot.subject)
        assertEquals("G14", thuSlot.room)
        assertEquals("D3 CS D", thuSlot.studentsSet)

        // Four-line cell: teachers line, class, subject, room.
        val preThesis = ps.slots.first { it.startMinutes == 12 * 60 + 30 }
        assertEquals("P.TH. Pre Thesis", preThesis.subject)
        assertEquals("DEPT LIB", preThesis.room)
        assertEquals("M3", preThesis.studentsSet)
    }

    @Test
    fun parsesMeDetailedCellsFromTocFullName() {
        val doc = FacultyTimetableParser.parse(meDialectFixture(), "me", "https://me.example/x.html", 1000L)
        // "CH-1" is a pseudo entry (a room, not a teacher) but is harmless in
        // the table list — matching never resolves it to a directory name.
        val ssk = doc.tables.first { it.teacherName == "Dr. Sehijpal Singh" }
        assertEquals("Dr. Sehijpal Singh", ssk.teacherName)
        assertEquals("SSK", ssk.code)

        val fri = ssk.slots.first { it.day == 4 }
        assertEquals(8 * 60 + 30, fri.startMinutes)
        assertEquals("MS", fri.subject)
        assertEquals("COMPUTATIONAL LAB", fri.room)
        assertEquals("D4 ME MANUFACTURING", fri.studentsSet)
    }

    @Test
    fun collectsDistinctSlotStartsAndGeneratedTimestamp() {
        val doc = FacultyTimetableParser.parse(plainDialectFixture(), "appsc", "https://appsc.example/x.html", 1000L)
        assertEquals(listOf(8 * 60 + 30, 9 * 60 + 30, 12 * 60 + 30, 13 * 60 + 30), doc.slotStarts)
        assertTrue(doc.generatedAtMillis != null && doc.generatedAtMillis > 0L)
    }

    @Test
    fun emptyHtmlThrowsParseException() {
        var threw = false
        try {
            FacultyTimetableParser.parse("", "me", "u", 1L)
        } catch (expected: ParseException) {
            threw = true
        }
        assertTrue(threw)
    }
}

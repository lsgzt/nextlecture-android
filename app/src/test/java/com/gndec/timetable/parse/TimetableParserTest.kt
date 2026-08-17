package com.gndec.timetable.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableParserTest {

    private fun fixture(): String =
        javaClass.getResource("/timetable_full.html")!!.readText()

    @Test
    fun discoversGroupsDynamicallyFromTableOfContents() {
        val groups = TimetableParser.discoverGroups(Jsoup.parse(fixture()))
        assertTrue("expected many groups, got ${groups.size}", groups.size > 40)
        val itb2 = groups.find { it.name == "ITB2" }
        assertNotNull(itb2)
        assertEquals("#table_53", itb2!!.anchor)
        // group list must NOT be hard-coded — other years/degrees are discovered too
        assertNotNull(groups.find { it.name == "MEA1" })
        assertNotNull(groups.find { it.name == "D2CSA1" })
        assertNotNull(groups.find { it.name == "BCA1A1" })
    }

    @Test
    fun parsesItb2FirstLecture() {
        val itb2 = TimetableParser.parse(fixture()).getValue("ITB2")
        assertTrue(itb2.size >= 10)
        val chem = itb2.first { it.subjectHint == "CHEMISTRY" && it.dayOfWeek == 1 }
        assertEquals(8 * 60 + 30, chem.startMinutes)
        assertEquals(9 * 60 + 30, chem.endMinutes)
        assertEquals("DR AMANDEEP KAUR", chem.teacherHint)
        assertEquals("S205", chem.venueHint)
        assertEquals("L", chem.typeTag)
        assertTrue("fully structured cell must be high confidence", chem.confidence >= 0.6)
    }

    @Test
    fun mergedRowspanCellsBecomeTwoHourPracticals() {
        val itb2 = TimetableParser.parse(fixture()).getValue("ITB2")
        val practicals = itb2.filter { it.typeTag == "P" }
        assertTrue("expected practical/lab sessions", practicals.isNotEmpty())
        assertTrue(
            "rowspan=2 must produce a 120-minute lecture",
            practicals.any { it.endMinutes - it.startMinutes == 120 }
        )
        // a rowspan cell must NOT also produce a duplicate lecture in the next row
        val thursdayLabs = practicals.filter { it.dayOfWeek == 4 }
        assertTrue(thursdayLabs.any { it.startMinutes == 9 * 60 + 30 })
        assertTrue(thursdayLabs.none { it.startMinutes == 10 * 60 + 30 && it.dayOfWeek == 4 && it.typeTag == "P" && it.endMinutes - it.startMinutes == 60 })
    }

    @Test
    fun otherGroupsAreParsed() {
        val parsed = TimetableParser.parse(fixture())
        assertTrue("expected >= 40 groups with lectures, got ${parsed.size}", parsed.size >= 40)
        assertTrue(parsed.getValue("MEA1").isNotEmpty())
        assertTrue(parsed.getValue("ITB1").isNotEmpty())
        assertTrue(parsed.getValue("D2CSA1").isNotEmpty())
    }

    @Test
    fun emptyCellsProduceNoLectures() {
        val html = """<table id="table_1"><thead><tr><td></td>
            <th class="xAxis">Monday</th></tr></thead>
            <tbody><tr><th class="yAxis">08:30</th>
            <td class="empty"><span class="empty">---</span></td></tr></tbody></table>"""
        val table = Jsoup.parse(html).getElementById("table_1")!!
        assertTrue(TimetableParser.parseTable(table, "X").isEmpty())
    }

    @Test
    fun ambiguousCellGetsLowConfidenceAndKeepsRawText() {
        // Subject only, no teacher/venue/tag — must be routed to AI by the normalizer
        val html = """<table id="table_1"><thead><tr><td></td>
            <th class="xAxis">Tuesday</th></tr></thead>
            <tbody><tr><th class="yAxis">11:30</th>
            <td><div class="line1"><span class="subject"><span>MENTORING</span></span></div></td>
            </tr></tbody></table>"""
        val list = TimetableParser.parseTable(Jsoup.parse(html).getElementById("table_1")!!, "X")
        assertEquals(1, list.size)
        assertEquals(2, list[0].dayOfWeek)
        assertEquals(11 * 60 + 30, list[0].startMinutes)
        assertTrue(list[0].confidence < 0.6)
        assertTrue(list[0].rawText.contains("MENTORING"))
    }

    @Test
    fun rawTextIsPreservedForAiInput() {
        val itb2 = TimetableParser.parse(fixture()).getValue("ITB2")
        val lab = itb2.first { it.typeTag == "P" && it.dayOfWeek == 4 }
        assertTrue(lab.rawText.contains("PROGRAMMING FOR PROBLEM SOLVING"))
        assertTrue(lab.rawText.contains("PL1 LAB IT DEPT"))
    }

    @Test(expected = ParseException::class)
    fun emptyDocumentThrows() {
        TimetableParser.parse("")
    }

    @Test(expected = ParseException::class)
    fun documentWithoutTimetablesThrows() {
        TimetableParser.parse("<html><body><p>site under maintenance</p></body></html>")
    }

    @Test
    fun parsesTimeSafely() {
        assertEquals(510, TimetableParser.parseTime("08:30"))
        assertEquals(810, TimetableParser.parseTime("13:30"))
        assertNull(TimetableParser.parseTime("nope"))
        assertNull(TimetableParser.parseTime("25:99"))
    }

    @Test
    fun sha256IsStable() {
        assertEquals(TimetableParser.sha256("abc"), TimetableParser.sha256("abc"))
        assertEquals(64, TimetableParser.sha256("abc").length)
    }
}

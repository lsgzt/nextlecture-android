package com.gndec.timetable.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the REAL GNDEC departmental group timetables (August 2026 revisions,
 * stored under /live/) and validates structure, times, day columns and the
 * GroupMatcher integration — not just compilation.
 *
 * These documents cover every dialect: CSE dual-thead + nested detailed
 * tables + range labels, IT/EE/ECE plain-text <br/> cells with 12-hour
 * PM-implied labels, CE "8.30 AM (1ST)" labels and ME compact names.
 */
class TimetableParserDeptTest {

    private fun live(name: String): String =
        javaClass.getResource("/live/$name")!!.readText()

    private fun groupsOf(name: String): Map<String, List<RawLecture>> =
        TimetableParser.parse(live(name))

    // ---------- CSE: dialect B, nested detailed tables, "08:30-09:30" ----------

    @Test
    fun cseDiscoversAllYearGroups() {
        val parsed = groupsOf("cse_groups_2026.html")
        for (expected in listOf("D2 CS A", "D3 CS B", "D4 CS E", "D1 CS A1")) {
            assertNotNull("missing group $expected", parsed[expected])
        }
        assertTrue("D2 CS A should have a full week of lectures", parsed.getValue("D2 CS A").size >= 25)
    }

    @Test
    fun cseNestedTableCellExtractsSubjectTeacherRoom() {
        val lectures = groupsOf("cse_groups_2026.html").getValue("D2 CS A")
        // The Monday 08:30 cell is a nested detailed table (CA T / APP. MATH T parallel).
        val first = lectures.first { it.dayOfWeek == 1 && it.startMinutes == 8 * 60 + 30 }
        assertNotNull(first.subjectHint)
        assertTrue("rawText must keep every nested line", first.rawText.contains("APP. MATH") || first.rawText.contains("CA"))
        assertTrue(first.confidence >= 0.45)
    }

    @Test
    fun cseRangeLabelsParseToSlotStarts() {
        val lectures = groupsOf("cse_groups_2026.html").getValue("D2 CS A")
        // 08:30-09:30 rows must never be misread as an 09:30 start.
        assertTrue(lectures.none { it.startMinutes == 570 && (it.endMinutes - it.startMinutes) == 60 && it.rawText.contains("---") })
        val starts = lectures.map { it.startMinutes }.toSortedSet()
        assertTrue(starts.first() == 510)
        assertTrue(starts.contains(13 * 60 + 30))
    }

    @Test
    fun cseEmptyMarkersProduceNoLectures() {
        val lectures = groupsOf("cse_groups_2026.html").getValue("D2 CS A")
        assertTrue(lectures.none { it.rawText == "-x-" || it.rawText == "---" })
    }

    // ---------- IT: "D2IT_A", PM-implied "1:30" labels ----------

    @Test
    fun itDiscoversGroupsAndAfternoonSlots() {
        val parsed = groupsOf("it_groups_2026.html")
        assertNotNull(parsed["D2IT_A"])
        assertNotNull("single D4IT group must exist", parsed["D4IT"])
        val lectures = parsed.getValue("D2IT_A")
        assertTrue(lectures.size >= 25)
        // "1:30" row is the 13:30 slot.
        assertTrue(lectures.any { it.startMinutes == 13 * 60 + 30 })
        assertTrue(lectures.none { it.startMinutes == 90 })
    }

    @Test
    fun itPracticalCellKeepsFullRawText() {
        val lectures = groupsOf("it_groups_2026.html").getValue("D2IT_A")
        val practical = lectures.firstOrNull { it.typeTag == "P" }
        assertNotNull("IT timetable contains practicals", practical)
        assertTrue(practical!!.rawText.contains("LAB"))
    }

    // ---------- EE: "D2A" names, initials-only teachers ----------

    @Test
    fun eeDiscoversBareYearSectionGroups() {
        val parsed = groupsOf("ee_groups_2026.html")
        for (expected in listOf("D2A", "D2B", "D3A", "D4B")) {
            assertNotNull("missing EE group $expected", parsed[expected])
        }
        val lectures = parsed.getValue("D2A")
        assertTrue(lectures.size >= 20)
        assertTrue("EE '1:30' must be an afternoon slot", lectures.any { it.startMinutes == 13 * 60 + 30 })
    }

    // ---------- ECE: M/T/W/TH/F headers, "01:30" afternoon wrap ----------

    @Test
    fun eceDiscoversGroupsIncludingPracticalSubgroups() {
        val parsed = groupsOf("ece_groups_2026.html")
        for (expected in listOf("D2ECA", "D3ECB", "D4ECA1", "D4ECB3")) {
            assertNotNull("missing ECE group $expected", parsed[expected])
        }
        val lectures = parsed.getValue("D2ECA")
        assertTrue(lectures.size >= 15)
        // "01:30" after "12:30" must resolve to 13:30 — never 01:30 AM.
        assertTrue(lectures.none { it.startMinutes == 90 })
        assertTrue(lectures.any { it.startMinutes == 13 * 60 + 30 })
    }

    @Test
    fun eceSingleLetterDayHeadersMapPositionally() {
        val lectures = groupsOf("ece_groups_2026.html").getValue("D2ECA")
        // Monday..Friday are all represented; no lecture may land on a weekend.
        val days = lectures.map { it.dayOfWeek }.toSortedSet()
        assertTrue(days.none { it > 5 })
        assertTrue(days.contains(1))
    }

    // ---------- CE: "8.30 AM (1ST)" labels + dialect A cells ----------

    @Test
    fun ceParsesAmPmLabelsAndGroups() {
        val parsed = groupsOf("ce_groups_2026.html")
        for (expected in listOf("D2 CE A", "D2 CE B", "D3 CE A")) {
            assertNotNull("missing CE group $expected", parsed[expected])
        }
        val lectures = parsed.getValue("D2 CE A")
        assertTrue(lectures.size >= 20)
        assertEquals(510, lectures.map { it.startMinutes }.min())
        // CE labels are 12-hour with explicit AM/PM — 1.30 PM must be 810.
        assertTrue(lectures.any { it.startMinutes == 13 * 60 + 30 })
    }

    // ---------- ME: compact names + dialect A nested tables ----------

    @Test
    fun meParsesCompactAndStreamGroups() {
        val parsed = groupsOf("me_groups_2026.html")
        for (expected in listOf("D2MEA", "D2MEB", "D3ME A", "D4 ME MANUFACTURING")) {
            assertNotNull("missing ME group $expected", parsed[expected])
        }
        assertTrue(parsed.getValue("D2MEA").size >= 20)
    }

    // ---------- GroupMatcher over the real documents ----------

    @Test
    fun everyDepartmentDocumentValidatesForItsBranchAndYears() {
        val cases = listOf(
            "cse_groups_2026.html" to "CS",
            "it_groups_2026.html" to "IT",
            "ee_groups_2026.html" to "EE",
            "ce_groups_2026.html" to "CE",
            "me_groups_2026.html" to "ME",
            "ece_groups_2026.html" to "EC"
        )
        for ((file, dept) in cases) {
            val parsed = groupsOf(file)
            val names = parsed.keys.toList()
            for (year in 2..4) {
                assertTrue("$file must contain $dept year-$year groups", GroupMatcher.hasGroupsFor(names, dept, year))
            }
        }
    }

    @Test
    fun sectionChipsMapToRealLectureGroupsInEveryLiveDocument() {
        val cases = listOf(
            "cse_groups_2026.html" to "CS",
            "it_groups_2026.html" to "IT",
            "ee_groups_2026.html" to "EE",
            "ce_groups_2026.html" to "CE",
            "me_groups_2026.html" to "ME",
            "ece_groups_2026.html" to "EC"
        )
        for ((file, dept) in cases) {
            val parsed = groupsOf(file)
            val names = parsed.keys.toList()
            val sections = GroupMatcher.sectionsForYear(names, dept, 2)
            assertTrue("$file year-2 sections must not be empty", sections.isNotEmpty())
            for (section in sections) {
                val options = GroupMatcher.groupsForSection(names, dept, 2, section)
                assertTrue(options.isNotEmpty())
                for (group in options) {
                    assertTrue(
                        "$file: picked group $group must actually exist in the parsed document",
                        parsed.containsKey(group)
                    )
                }
            }
        }
    }

    @Test
    fun nestedTablesNeverShiftDayColumns() {
        // CSE table rows embed detailed tables; a lecture's day must stay the
        // column day even when sibling columns hold nested tables.
        val lectures = groupsOf("cse_groups_2026.html").getValue("D2 CS A")
        val byDay = lectures.groupBy { it.dayOfWeek }
        for (day in 1..5) {
            assertTrue("D2 CS A must have lectures on day $day", (byDay[day]?.size ?: 0) >= 3)
        }
        assertTrue(byDay.keys.none { it > 5 })
    }
}

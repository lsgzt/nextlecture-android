package com.gndec.timetable.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The published date-sheet syntax: groups under headings that end in a colon,
 * one exam per line as "DD Mon — Subject — time", optional sections in brackets.
 */
class ExamScheduleParserTest {

    /** The MSE-II sheet exactly as it stands in announcements.json. */
    private val dateSheet = """
        **Chemistry Group:**

        23 Nov — Mathematics-I — 9:15–10:45 AM
        25 Nov — PPS — 9:15–10:45 AM
        26 Nov — Chemistry — 9:15–10:45 AM
        27 Nov — English — 9:15–10:45 AM
        28 Nov — BEEE — 9:15–10:45 AM

        **Physics Group:**

        23 Nov — Mathematics-I — 9:15–10:45 AM
        25 Nov — PPS — 9:15–10:45 AM
        26 Nov — Physics — 12:45–2:15 PM
        27 Nov — Economics — 12:45–2:15 PM
        28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B, CE A, CE B, RAI)
        28 Nov — EDG — 1:00–2:30 PM (EE A, EE B, EC A, EC B)
    """.trimIndent()

    private val published = LocalDate.of(2026, 11, 12)

    private fun date(iso: String): LocalDate = LocalDate.parse(iso)

    @Test
    fun publishedSheetIsReadGroupByGroup() {
        val sheet = ExamScheduleParser.parse(dateSheet, published)

        assertEquals(listOf("Chemistry Group", "Physics Group"), sheet.groups)
        assertEquals(11, sheet.exams.size)
        assertEquals(0, sheet.unreadable)

        val chemistry = sheet.exams.filter { it.group == "Chemistry Group" }
        assertEquals(5, chemistry.size)
        assertEquals(listOf("2026-11-23", "2026-11-25", "2026-11-26", "2026-11-27", "2026-11-28"), chemistry.map { it.date.toString() })
        assertEquals(
            listOf("Mathematics-I", "PPS", "Chemistry", "English", "BEEE"),
            chemistry.map { it.subject }
        )
        assertTrue(chemistry.all { it.startMinutes == 9 * 60 + 15 && it.endMinutes == 10 * 60 + 45 })
        assertTrue(chemistry.all { it.sections.isEmpty() })

        val physics = sheet.exams.filter { it.group == "Physics Group" }
        assertEquals(6, physics.size)
        val edgMorning = physics[4]
        assertEquals("EDG", edgMorning.subject)
        assertEquals(11 * 60, edgMorning.startMinutes)
        assertEquals(12 * 60 + 30, edgMorning.endMinutes)
        assertEquals(listOf("ME A", "ME B", "CE A", "CE B", "RAI"), edgMorning.sections)
        val edgAfternoon = physics[5]
        assertEquals(13 * 60, edgAfternoon.startMinutes)
        assertEquals(14 * 60 + 30, edgAfternoon.endMinutes)
        assertEquals(listOf("EE A", "EE B", "EC A", "EC B"), edgAfternoon.sections)
    }

    @Test
    fun plainHeadingsWorkTooAndEmptyOnesAreNotGroups() {
        val sheet = ExamScheduleParser.parse(
            """
                Physics group:
                26 Nov — Physics — 12:45–2:15 PM

                Instructions:
                Carry your admit card and reach 30 minutes early.
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Physics group"), sheet.groups)
        assertEquals(1, sheet.exams.size)
        assertEquals("Physics group", sheet.exams[0].group)
    }

    @Test
    fun markdownAroundHeadingsAndExamLinesIsIgnored() {
        // The same sheet written with every kind of emphasis a notice editor produces.
        val sheet = ExamScheduleParser.parse(
            """
                ## Chemistry Group:

                **23 Nov — Mathematics-I — 9:15–10:45 AM**

                __Physics Group__：
                26 Nov — *Physics* — 12:45–2:15 PM

                _Electronics Group:_
                27 Nov — Data_Structures — 9–11 AM
                28 Nov — _English_ — 9–11 AM
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Chemistry Group", "Physics Group", "Electronics Group"), sheet.groups)
        // An underscore inside a word is part of the subject, not emphasis.
        assertEquals(listOf("Mathematics-I", "Physics", "Data_Structures", "English"), sheet.exams.map { it.subject })
        assertEquals("Electronics Group", sheet.exams.last().group)
    }

    @Test
    fun twentyFourHourTimesNeedNoMeridiem() {
        val sheet = ExamScheduleParser.parse("23 Nov — Physics — 14:00–16:30", published)
        val exam = sheet.exams.single()
        assertEquals(14 * 60 to 16 * 60 + 30, exam.startMinutes to exam.endMinutes)
    }

    @Test
    fun theDateSheetInTheReadmeParses() {
        // README.md is the published reference for this syntax and teaches it by
        // example, so its example has to read exactly as the README says it does.
        var dir: File? = File(System.getProperty("user.dir")!!)
        var text: String? = null
        while (dir != null && text == null) {
            val readme = File(dir, "README.md")
            if (readme.isFile && File(dir, "announcements.json").isFile) text = readme.readText()
            dir = dir.parentFile
        }
        val readme = requireNotNull(text) { "README.md not found above ${System.getProperty("user.dir")}" }
        // The first JSON block after the heading that introduces the example.
        val block = Regex("""```json\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL)
            .find(readme.substringAfter("### Publishing a date sheet"))!!.groupValues[1]
        val example = Json.parseToJsonElement(block).jsonObject
        val sheet = ExamScheduleParser.parse(
            example.getValue("message").jsonPrimitive.content,
            OffsetDateTime.parse(example.getValue("publishedAt").jsonPrimitive.content).toLocalDate()
        )

        assertEquals(listOf("Chemistry Group", "Physics Group"), sheet.groups)
        assertEquals(11, sheet.exams.size)
        assertEquals(0, sheet.unreadable)
        assertEquals(date("2026-11-23"), sheet.exams.first().date)
        assertEquals(date("2026-11-28"), sheet.exams.last().date)
        val edg = sheet.exams.filter { it.subject == "EDG" }
        assertEquals(listOf("ME A", "ME B", "CE A", "CE B", "RAI"), edg[0].sections)
        assertEquals(listOf("EE A", "EE B", "EC A", "EC B"), edg[1].sections)
    }

    @Test
    fun proseAroundTheScheduleIsIgnored() {
        val sheet = ExamScheduleParser.parse(
            """
                Dear students, the mid-semester examination begins on 23 November 2026.

                **Chemistry Group:**

                23 Nov — Mathematics-I — 9:15–10:45 AM
                Reporting time: 9:00 AM
                Bring your ID card — library cards are not accepted.

                Last date to pay fees is 30 Nov — pay at the accounts section.
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Chemistry Group"), sheet.groups)
        assertEquals(listOf("Mathematics-I"), sheet.exams.map { it.subject })
    }

    @Test
    fun aBrokenLineIsSkippedWithoutTakingTheOthersDown() {
        val sheet = ExamScheduleParser.parse(
            """
                **Chemistry Group:**

                25 Nov — PPS — 9:65–10:45 AM
                26 Nov — Chemistry — 10:45–9:15 AM
                27 Nov — English — in the morning
                27 Nov — Economics — 9:15
                28 Nov — BEEE — 9:15–10:45 AM
                31 Feb — Chemistry — 9:15–10:45 AM
                24 Nov — something with no date at all
            """.trimIndent(),
            published
        )
        assertEquals(listOf("BEEE"), sheet.exams.map { it.subject })
        // Every other line had the shape of an exam line, so each is reported
        // rather than dropped in silence.
        assertEquals(6, sheet.unreadable)
    }

    @Test
    fun proseIsNotMistakenForAMissedExamLine() {
        val sheet = ExamScheduleParser.parse(
            """
                Dear students, the mid-semester examination begins on 23 November 2026.

                **Chemistry Group:**

                23 Nov — Mathematics-I — 9:15–10:45 AM
                Reporting time: 9:00 AM
                Bring your ID card — library cards are not accepted.
                Last date to pay fees is 30 Nov — pay at the accounts section.
                The labs are open 9:00 AM to 4:00 PM.

                **Physics Group:**

                26 Nov — Physics — 12:45–2:15 PM
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Mathematics-I", "Physics"), sheet.exams.map { it.subject })
        assertEquals(0, sheet.unreadable)
    }

    @Test
    fun anExamLineInAnotherDateFormatIsReportedNotJustDropped() {
        val sheet = ExamScheduleParser.parse(
            """
                **Chemistry Group:**

                23/11 — Mathematics-I — 9:15–10:45 AM
                25 Nov — PPS — 9:15–10:45 AM
            """.trimIndent(),
            published
        )
        assertEquals(listOf("PPS"), sheet.exams.map { it.subject })
        assertEquals(1, sheet.unreadable)
    }

    @Test
    fun noExamEverEndsBeforeItStarts() {
        val sheet = ExamScheduleParser.parse(
            """
                23 Nov — Morning — 11:00 AM–12:30
                23 Nov — Single — 11–12 PM
                23 Nov — Late — 9:15 PM–10:45 AM
                23 Nov — Backwards — 9:15 PM–8:00 AM
                23 Nov — TwentyFour — 16:00–14:00
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Morning", "Single", "Late"), sheet.exams.map { it.subject })
        assertTrue(sheet.exams.all { it.startMinutes < it.endMinutes })
        assertEquals(2, sheet.unreadable)
    }

    @Test
    fun commonTimeSpellingsAllResolveToTheSameRange() {
        val lines = listOf(
            "23 Nov — A — 9:15–10:45 AM",
            "23 Nov — B — 9:15 AM to 10:45 AM",
            "23 Nov — C — 9:15 am - 10:45 am",
            "23 Nov — D — 09:15–10:45",
            "23 Nov — E — 9.15-10.45 am",
            "23 Nov — F — 9:15-10:45 a.m.",
            "23 Nov — G — 9:15 AM – 10:45 AM"
        )
        val sheet = ExamScheduleParser.parse(lines.joinToString("\n"), published)
        assertEquals(7, sheet.exams.size)
        assertTrue(sheet.exams.all { it.startMinutes == 555 && it.endMinutes == 645 })
    }

    @Test
    fun aSideWithoutAmOrPmTakesTheOtherSides() {
        val sheet = ExamScheduleParser.parse(
            """
                23 Nov — Morning — 11:00 AM–12:30
                23 Nov — Afternoon — 12:45–2:15 PM
                23 Nov — Noon — 12:00–1:00 PM
                23 Nov — Late — 9:15 PM–10:45 AM
                23 Nov — Single — 11–12 PM
                23 Nov — Whole — 9–5 PM
            """.trimIndent(),
            published
        )
        val range = sheet.exams.associate { it.subject to (it.startMinutes to it.endMinutes) }
        assertEquals(660 to 750, range["Morning"])   // 11:00 AM – 12:30 PM
        assertEquals(765 to 855, range["Afternoon"]) // 12:45 – 2:15 PM
        assertEquals(720 to 780, range["Noon"])      // 12:00 – 1:00 PM
        // 9:15 PM written on both clocks: the end is 10:45 PM, read again against the start.
        assertEquals(1275 to 1365, range["Late"])
        assertEquals(660 to 720, range["Single"])    // 11:00 AM – 12:00 PM
        assertEquals(540 to 1020, range["Whole"])    // 9:00 AM – 5:00 PM
    }

    @Test
    fun datesTakeTheYearNearestToTheDayTheSheetWasPublished() {
        val january = ExamScheduleParser.parse("2 Jan — Maths — 9–11 AM", date("2026-12-28"))
        assertEquals(date("2027-01-02"), january.exams[0].date)

        val december = ExamScheduleParser.parse("28 Dec — Maths — 9–11 AM", date("2027-01-03"))
        assertEquals(date("2026-12-28"), december.exams[0].date)

        val lastYear = ExamScheduleParser.parse("14 Apr — Maths — 9–11 AM", date("2026-04-10"))
        assertEquals(date("2026-04-14"), lastYear.exams[0].date)
    }

    @Test
    fun otherDateOrdersAndDecorationsAreTolerated() {
        val sheet = ExamScheduleParser.parse(
            """
                23rd November, 2026 — Maths — 9:15–10:45 AM
                Nov 23 — PPS — 9:15–10:45 AM
                - 23 Nov — Chemistry — 9:15–10:45 AM
                English — 23 Nov — 9:15–10:45 AM
                23-Nov-2026 — BEEE — 9:15–10:45 AM
            """.trimIndent(),
            // Published three days before the exams, so every year-less date lands on them.
            date("2026-11-20")
        )
        assertEquals(5, sheet.exams.size)
        assertTrue(sheet.exams.all { it.date == date("2026-11-23") })
        assertEquals(
            listOf("Maths", "PPS", "Chemistry", "English", "BEEE"),
            sheet.exams.map { it.subject }
        )
    }

    @Test
    fun subjectKeepsItsOwnDashesAndSpaces() {
        val sheet = ExamScheduleParser.parse(
            """
                23 Nov — Mathematics-I — 9:15–10:45 AM
                23 Nov — Engineering   Graphics — 11:00 AM–12:30 PM — Room 101
            """.trimIndent(),
            published
        )
        assertEquals(listOf("Mathematics-I", "Engineering Graphics"), sheet.exams.map { it.subject })
    }

    @Test
    fun sectionListsAreSplitOnEveryCommonSeparator() {
        val sheet = ExamScheduleParser.parse(
            """
                28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B; CE A | CE B)
                28 Nov — EDG — 1:00–2:30 PM (EE A & EE B and EC A)
                28 Nov — EDG — 3:00–4:30 PM (CSE1 only)
                28 Nov — EDG — 5:00–6:30 PM ( )
            """.trimIndent(),
            published
        )
        val sections = sheet.exams.map { it.sections }
        assertEquals(listOf("ME A", "ME B", "CE A", "CE B"), sections[0])
        assertEquals(listOf("EE A", "EE B", "EC A"), sections[1])
        assertEquals(listOf("CSE1"), sections[2])
        assertTrue(sections[3].isEmpty())
    }

    @Test
    fun nothingReadableYieldsAnEmptySchedule() {
        val sheet = ExamScheduleParser.parse("Exams begin soon. Dates will follow.", published)
        assertTrue(sheet.isEmpty)
        assertTrue(sheet.groups.isEmpty())
    }
}

package com.gndec.timetable.parse

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * One busy hour in one teacher's week. FET slots are one hour each, so a
 * rowspan="2" cell (a two-hour lab) becomes two consecutive slots.
 */
@Serializable
data class FacultySlot(
    /** 0 = Monday … 6 = Sunday. */
    val day: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val subject: String? = null,
    val room: String? = null,
    val studentsSet: String? = null
)

/**
 * One teacher's table from a departmental "teachers days horizontal" document.
 * [teacherName] is the best full name the document offers (Table of contents
 * first, caption/colspan header second) — null when the department publishes
 * only a short code ("HVS" at Civil). [code] is that short code when present.
 */
@Serializable
data class TeacherTable(
    val teacherName: String? = null,
    val code: String? = null,
    val slots: List<FacultySlot> = emptyList()
)

/** Parsed + normalized snapshot of ONE departmental teacher timetable document. */
@Serializable
data class ParsedFacultyDoc(
    val rootId: String,
    val url: String,
    /** Distinct slot start times in minutes, ascending, across the document. */
    val slotStarts: List<Int>,
    val tables: List<TeacherTable>,
    /** FET footer generation timestamp, when present. */
    val generatedAtMillis: Long?,
    val fetchedAtMillis: Long
)

/**
 * Parses the FET "teachers days horizontal" exports that GNDEC departments
 * publish. Three dialects appear across the live files (see the prototype
 * findings in scripts/fetch_faculty_timetables.py):
 *
 *  Dialect A-simple (Applied Science, IT, EE, CE, ECE):
 *    caption `<span class="name">NAME</span>`, plain-text cells split by <br/>
 *    into group / subject / room lines; yAxis "08:30", "1:30", "8.30 AM (1ST)".
 *
 *  Dialect B (CSE, FET 6.x): caption holds the institution; the teacher name is
 *    the `<th colspan="N">` of the first header row; yAxis "08:30-09:30" ranges;
 *    cells plain text.
 *
 *  Dialect A-detailed (ME): caption `<span class="name">CODE</span>` (initials
 *    only); cells carry div.studentsset / span.subject / div.room.
 *
 * Every document also embeds a table of contents whose labels are richer than
 * the captions: ME writes "SSK (Dr. Sehijpal Singh)", EE/ECE write
 * "Dr. Kanwardeep Singh (KDS)". The TOC is therefore the primary name source;
 * the caption is the fallback.
 */
object FacultyTimetableParser {

    private val EMPTY_CELL_TEXT = setOf("---", "-x-", "-", "--", "x", "not available", "na", "n/a")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** One parsed cell while walking a table's grid. */
    private data class Cell(
        val subject: String? = null,
        val room: String? = null,
        val studentsSet: String? = null
    ) {
        val busy: Boolean get() = subject != null || room != null || studentsSet != null
    }

    fun parse(html: String, rootId: String, url: String, fetchedAtMillis: Long): ParsedFacultyDoc {
        if (html.isBlank()) throw ParseException("empty faculty timetable html")
        val doc = Jsoup.parse(html)

        val toc = mutableMapOf<Int, String>()
        for (anchor in doc.select("a[href^=#table_]")) {
            val id = anchor.attr("href").removePrefix("#table_").toIntOrNull() ?: continue
            val label = anchor.text().replace('\u00A0', ' ').trim()
            if (label.isNotEmpty()) toc[id] = label
        }

        val slotStarts = sortedSetOf<Int>()
        val tables = mutableListOf<TeacherTable>()

        for (table in doc.select("table[id^=table_]")) {
            val id = table.id().removePrefix("table_").toIntOrNull() ?: continue
            val days = resolveDays(table)
            if (days.none { it in 0..5 }) continue
            val starts = slotStartsOf(table)
            if (starts.isEmpty()) continue
            slotStarts += starts

            val label = toc[id] ?: tableName(table) ?: continue
            if (isPseudoTeacher(label)) continue
            val (fullName, code) = FacultyNameMatcher.extractFullName(label)

            val grid = parseTableGrid(table, days, starts)
            val slots = mutableListOf<FacultySlot>()
            for (col in grid.indices) {
                val day = days.getOrNull(col) ?: continue
                if (day !in 0..6) continue
                for (slotIdx in grid[col].indices) {
                    val cell = grid[col][slotIdx] ?: continue
                    if (!cell.busy) continue
                    slots += FacultySlot(
                        day = day,
                        startMinutes = starts[slotIdx],
                        endMinutes = starts[slotIdx] + 60,
                        subject = cell.subject,
                        room = cell.room,
                        studentsSet = cell.studentsSet
                    )
                }
            }
            if (fullName == null && code == null) continue
            tables += TeacherTable(teacherName = fullName, code = code, slots = slots.sortedWith(compareBy({ it.day }, { it.startMinutes })))
        }

        if (tables.isEmpty()) throw ParseException("no teacher tables parsed from faculty document")

        return ParsedFacultyDoc(
            rootId = rootId,
            url = url,
            slotStarts = slotStarts.toList(),
            tables = tables,
            generatedAtMillis = RoomTimetableParser.parseGeneratedAtMillis(html),
            fetchedAtMillis = fetchedAtMillis
        )
    }

    /** Tables like "ME(faculty)", "M.Tech_(ME)" or "SELF STUDY" are not teachers. */
    private val NON_TEACHER_HINTS = listOf(
        "m.tech", "mtech", "self study", "faculty", "deptt", "workshop", "combined",
        "guide", "detained", "other dept",
        // Civil's subject-service tables ("STK(APPLIED SCI.)", "RJK(Applied Sci)",
        // "PDT2") are first-year service entries, not teachers.
        "applied", "pdt"
    )

    private fun isPseudoTeacher(label: String): Boolean {
        val low = label.lowercase()
        return NON_TEACHER_HINTS.any { it in low }
    }

    /** Dialect A caption name, else the CSE dialect-B colspan header. */
    private fun tableName(table: Element): String? {
        val captionName = table.selectFirst("caption span.name")?.text()?.trim()
        if (!captionName.isNullOrEmpty()) return captionName
        val colspanHeader = table.selectFirst("thead th[colspan]")?.text()?.trim()
        return colspanHeader?.takeIf { it.isNotEmpty() }
    }

    // --- day columns ---------------------------------------------------------

    private fun resolveDays(table: Element): List<Int?> {
        val labels = table.select("thead th.xAxis").map { it.text().trim() }
        return labels.mapIndexed { index, label -> dayIndexFor(label, index, labels.size) }
    }

    private fun dayIndexFor(label: String, column: Int, columnCount: Int): Int? {
        val token = label.split(" ").firstOrNull()?.uppercase().orEmpty()
        when (token) {
            "MONDAY" -> return 0
            "TUESDAY", "TUES", "TUE" -> return 1
            "WEDNESDAY", "WED" -> return 2
            "THURSDAY", "THURS", "THU" -> return 3
            "FRIDAY", "FRI" -> return 4
            "SATURDAY", "SAT" -> return 5
            "SUNDAY", "SUN" -> return 6
        }
        // Single-letter columns (ECE's "M T W TH F") fall back to position;
        // teaching weeks run Monday..Saturday so the index maps directly.
        if (token.length <= 2 && columnCount in 5..6 && column < 6) return column
        return null
    }

    // --- slots ---------------------------------------------------------------

    private fun slotStartsOf(table: Element): List<Int> {
        val starts = mutableListOf<Int>()
        for (row in table.select("tbody tr")) {
            val label = row.selectFirst("th.yAxis")?.text() ?: continue
            val minutes = RoomTimetableParser.slotStartMinutes(label) ?: continue
            if (minutes !in starts) starts.add(minutes)
        }
        return starts
    }

    // --- grid with rowspan carry ----------------------------------------------

    /**
     * Rows = source day columns, inner lists = slot rows. Handles rowspan carry
     * the same way [RoomTimetableParser] does: a rowspan=N cell occupies N
     * consecutive slot rows of the same day column.
     */
    private fun parseTableGrid(table: Element, days: List<Int?>, starts: List<Int>): List<List<Cell?>> {
        val rows = table.select("tbody tr").filter { it.selectFirst("th.yAxis") != null }
        val slotIndexOf = HashMap<Int, Int>()
        starts.forEachIndexed { i, minutes -> slotIndexOf[minutes] = i }

        val grid = List(days.size) { MutableList<Cell?>(starts.size) { null } }
        val spanLeft = IntArray(days.size)
        val spanCarry = arrayOfNulls<Cell>(days.size)

        var rowIndex = 0
        for (tr in rows) {
            val label = tr.selectFirst("th.yAxis")?.text().orEmpty()
            val slotIndex = slotIndexOf[RoomTimetableParser.slotStartMinutes(label) ?: -1]
                ?: rowIndex.takeIf { it < starts.size }
                ?: continue
            rowIndex++

            val cells = tr.children().filter { it.tagName() == "td" }
            var column = 0
            var cellIndex = 0
            while (column < days.size) {
                if (spanLeft[column] > 0) {
                    grid[column][slotIndex] = spanCarry[column] ?: Cell()
                    spanLeft[column]--
                    column++
                    continue
                }
                if (cellIndex >= cells.size) break
                val td = cells[cellIndex++]
                val rowspan = td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val cell = parseCell(td)
                grid[column][slotIndex] = cell
                if (rowspan > 1) {
                    spanLeft[column] = rowspan - 1
                    spanCarry[column] = cell
                }
                column++
            }
            while (column < days.size && spanLeft[column] > 0) {
                grid[column][slotIndex] = spanCarry[column] ?: Cell()
                spanLeft[column]--
                column++
            }
        }
        return grid
    }

    private fun parseCell(td: Element): Cell? {
        val text = td.text().trim()
        if (td.hasClass("empty") || text.isEmpty() || text.lowercase() in EMPTY_CELL_TEXT) return null

        // Dialect A-detailed (ME): structured divs/spans.
        val studentsSets = td.select("div.studentsset").map { it.text().trim() }.filter { it.isNotEmpty() }
        val subjects = td.select("span.subject").map { it.text().trim() }.filter { it.isNotEmpty() }
        val rooms = td.select("div.room span[class^=r_]").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (studentsSets.isNotEmpty() || subjects.isNotEmpty() || rooms.isNotEmpty()) {
            return Cell(
                subject = subjects.joinToString(" · ").ifEmpty { null },
                room = rooms.joinToString(" · ").ifEmpty { null },
                studentsSet = studentsSets.joinToString(" · ").ifEmpty { null }
            )
        }

        // Dialect A-simple / B: plain-text lines split by <br/>:
        // 3 lines = group / subject / room; 4+ lines = teachers / group /
        // subject / room (CSE's multi-teacher cells) — the class is lines[1].
        val lines = BR.split(td.html())
            .map { Jsoup.parse(it).text().trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in EMPTY_CELL_TEXT }
        if (lines.isEmpty()) return null
        return when {
            lines.size == 1 -> Cell(subject = lines[0])
            lines.size == 2 -> Cell(subject = lines[0], room = lines[1])
            else -> Cell(
                subject = lines[lines.size - 2],
                room = lines.last(),
                studentsSet = if (lines.size >= 4) lines[1] else lines.first()
            )
        }
    }
}

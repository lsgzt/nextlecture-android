package com.gndec.timetable.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

class ParseException(message: String) : Exception(message)

data class GroupRef(val name: String, val anchor: String)

/**
 * One raw timetable cell before AI normalization.
 * group/day/start/end are ALWAYS determined here and are authoritative —
 * the AI is never allowed to change them.
 */
data class RawLecture(
    val groupName: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val subjectHint: String?,
    val teacherHint: String?,
    val venueHint: String?,
    val typeTag: String?,
    val rawText: String,
    val confidence: Double
)

/**
 * Multi-group FET timetable parser covering every dialect GNDEC publishes:
 *
 *  Dialect A (appsc 1st-year, CE, ME): structured cells with `span.subject`,
 *  `div.teacher`, `div.room`, `div.studentsset`, `span.activitytag`; cells may
 *  embed a nested "detailed" table with parallel activities.
 *
 *  Dialect B (CSE, IT, EE, ECE dept exports): plain-text cells whose lines
 *  ("D2 CS A1 / CA T / Pf. Harmanpreet Kaur (HPK) / G16") are separated by
 *  `<br/>` or nested table rows; CSE adds a colspan group-name header row and
 *  range labels ("08:30-09:30").
 *
 * Robustness rules baked in (validated against the live 2026 documents):
 *  - only DIRECT child <td>s of a row are grid cells — nested tables must not
 *    shift the day columns (every department file contains nested tables);
 *  - 12-hour labels without meridiem ("1:30", "01:30" after "12:30") resolve
 *    to the afternoon slot; CE's "8.30 AM (1ST)" style is understood;
 *  - "-x-", "---" and blank cells are free slots, never lectures;
 *  - day headers may be full names or positional single letters (M/T/W/TH/F).
 */
object TimetableParser {

    const val PARSER_VERSION = 2
    const val SLOT_MINUTES = 60

    private val TIME_IN_LABEL = Regex("""(\d{1,2})[.:](\d{2})(?:\s*([AaPp][Mm]))?""")
    private val DAY_ORDER = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )
    private val KNOWN_TAGS = setOf("L", "P", "T")
    private val WS = Regex("\\s+")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    // Dialect-B classification helpers.
    private val EMPTY_CELL_TEXT = setOf("-x-", "---", "-", "x", "--", "not available", "na", "n/a")
    private val TEACHER_PREFIX = Regex("""^(Pf|Dr|Mr|Mrs|Ms|Er|Prof|Ern)\.?\s+""")
    private val TEACHER_INITIALS = Regex("""^[A-Z]{2,6}((\s*,\s*|\s+)[A-Z]{2,6})*$""")
    private val TEACHER_PAREN = Regex("""\([A-Za-z]{2,8}\)\s*$""")
    private val ROOM_SHORT = Regex("""^[A-Z]{1,3}\s?-?\d{1,3}[A-Z]?$""")
    private val ROOM_LAB = Regex("""^[A-Z&]{2,5}/L(\([A-Z]{1,6}\))?$""")
    private val ROOM_TOKEN = Regex("""^[A-Z]{1,3}\s?-?\d{1,3}[A-Z]?(?:/L)?$""")
    private val TYPE_SUFFIX = Regex("""[\s.]+([LPT])\.?\s*$""")
    private val GROUP_TOKEN = Regex("""^(D[1-4]|M[123]|PHD)[A-Z0-9]*$""")
    private val RANGE_TIME = Regex("""^\s*\d{1,2}[.:]\d{2}\s*-\s*\d{1,2}[.:]\d{2}\s*$""")

    /**
     * Parse the full multi-group document.
     * @return map of group name -> lectures (only groups with at least one lecture)
     * @throws ParseException if the document contains no usable timetable
     */
    fun parse(html: String): Map<String, List<RawLecture>> {
        if (html.isBlank()) throw ParseException("empty html")
        val doc = Jsoup.parse(html)
        val refs = discoverGroups(doc).ifEmpty { discoverGroupsFromTables(doc) }
        if (refs.isEmpty()) throw ParseException("no groups discovered in document")
        val result = LinkedHashMap<String, List<RawLecture>>()
        for (ref in refs) {
            val table = doc.getElementById(ref.anchor.removePrefix("#")) ?: continue
            val lectures = parseTable(table, ref.name)
            if (lectures.isNotEmpty()) result[ref.name] = lectures
        }
        if (result.isEmpty()) throw ParseException("no lecture cells parsed from any group table")
        return result
    }

    /** Dynamically discover groups from the document's table of contents. */
    fun discoverGroups(doc: Document): List<GroupRef> {
        val seen = LinkedHashMap<String, GroupRef>()
        for (a in doc.select("ul a[href^=#table_]")) {
            val name = a.text().trim()
            if (name.isNotEmpty()) seen.putIfAbsent(name, GroupRef(name, a.attr("href").trim()))
        }
        return seen.values.toList()
    }

    /**
     * Fallback discovery when there is no table of contents. Group names come
     * from (in order of reliability): the caption's span.name (dialect A), the
     * caption text's trailing group token (IT/ECE: "…(Department of Information
     * Technology)D2IT_A"), or the thead's colspan header row (CSE: "D2 CS A").
     */
    fun discoverGroupsFromTables(doc: Document): List<GroupRef> {
        val out = mutableListOf<GroupRef>()
        val seen = mutableSetOf<String>()
        for (t in doc.select("table[id^=table_]")) {
            val name = groupNameOf(t) ?: continue
            if (name.isNotEmpty() && seen.add(name)) out.add(GroupRef(name, "#" + t.id()))
        }
        return out
    }

    private fun groupNameOf(table: Element): String? {
        val spanName = table.selectFirst("caption span.name")?.text()?.trim()
        if (!spanName.isNullOrEmpty()) return spanName
        val caption = table.selectFirst("caption")
        if (caption != null) {
            val text = caption.text().replace('\u00A0', ' ').trim()
            if (text.isNotEmpty()) {
                // "(Department of Information Technology)D2IT_A" → after the last ')'.
                val afterParen = text.substringAfterLast(')', "").trim()
                if (afterParen.isNotEmpty()) return afterParen
                val lastToken = text.split(" ").lastOrNull()?.trim().orEmpty()
                if (lastToken.any { it.isDigit() }) return lastToken
            }
        }
        // CSE: thead row 0 carries the group name in a th WITHOUT the xAxis class.
        val headerRow = table.select("thead tr").firstOrNull() ?: return null
        val headerTh = headerRow.children().firstOrNull {
            it.tagName() == "th" && !it.hasClass("xAxis") && it.text().trim().isNotEmpty()
        } ?: return null
        return headerTh.text().trim()
    }

    /**
     * Parse one group's timetable grid.
     * Rows = time slots (th.yAxis), columns = days (thead th.xAxis).
     * Handles rowspan (multi-hour practicals), nested detailed tables,
     * plain-text dialect B cells and empty markers.
     */
    fun parseTable(table: Element, group: String): List<RawLecture> {
        val dayHeaders = table.select("thead th.xAxis").map { it.text().trim() }
        if (dayHeaders.isEmpty()) return emptyList()
        val days = dayHeaders.mapIndexed { i, name -> dayIndexFor(name, i, dayHeaders.size) }
        val nCols = days.size
        val slotStarts = resolveSlotStarts(table)
        if (slotStarts.isEmpty()) return emptyList()

        val rowSpanLeft = IntArray(nCols)
        val out = mutableListOf<RawLecture>()
        for (tr in table.select("tbody tr")) {
            // DIRECT child only — nested tables contain their own th/td structure.
            val yAxis = tr.children().firstOrNull { it.tagName() == "th" && it.hasClass("yAxis") } ?: continue
            val label = yAxis.text().trim()
            // Rows whose label is unusable still consume their cells positionally:
            // map them to the next unresolved slot (FET never reorders rows).
            val start = labelStartMinutes(label, slotStarts) ?: continue
            // Direct children only — nested "detailed" tables must NOT become day cells.
            val tds = tr.children().filter { it.tagName() == "td" }
            var col = 0
            var ti = 0
            while (col < nCols) {
                if (rowSpanLeft[col] > 0) { rowSpanLeft[col]--; col++; continue }
                if (ti >= tds.size) break
                val td = tds[ti++]
                val rowspan = td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                if (!isEmptyCell(td)) {
                    extractCell(td, group, days[col], start, start + SLOT_MINUTES * rowspan)
                        ?.let { out.add(it) }
                }
                if (rowspan > 1) rowSpanLeft[col] = rowspan - 1
                col++
            }
        }
        return out
    }

    /** Free-slot detection across dialects: class="empty" or the classic markers. */
    private fun isEmptyCell(td: Element): Boolean {
        if (td.hasClass("empty")) return true
        val text = td.text().replace(WS, " ").trim()
        if (text.isEmpty()) return true
        return text.lowercase() in EMPTY_CELL_TEXT
    }

    /**
     * Sequential slot resolution: FET prints rows in chronological order; 12-hour
     * exports drop the meridiem after noon ("08:30 … 12:30 … 01:30"), so a label
     * that lands before its predecessor belongs to the afternoon (+12h). Labels
     * that repeat (two rows sharing a start) resolve to their first occurrence.
     */
    private fun resolveSlotStarts(table: Element): List<Int> {
        val starts = mutableListOf<Int>()
        for (row in table.select("tbody tr")) {
            val th = row.children().firstOrNull { it.tagName() == "th" && it.hasClass("yAxis") } ?: continue
            val minutes = baseStartMinutes(th.text()) ?: continue
            val resolved = if (starts.isNotEmpty() && minutes <= starts.last()) minutes + 720 else minutes
            if (resolved !in starts) starts.add(resolved)
        }
        return starts
    }

    private fun labelStartMinutes(label: String, resolved: List<Int>): Int? {
        val base = baseStartMinutes(label) ?: return null
        return resolved.firstOrNull { it % 720 == base % 720 || it == base } ?: base
    }

    /** "08:30", "8:30", "1:30" (PM implied), "8.30 AM (1ST)", "08:30-09:30" → minutes. */
    fun baseStartMinutes(label: String): Int? {
        val m = TIME_IN_LABEL.find(label.trim()) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val meridiem = m.groupValues[3].uppercase().takeIf { it.isNotEmpty() }
        when {
            meridiem == "PM" && hour != 12 -> hour += 12
            meridiem == "AM" && hour == 12 -> hour = 0
            meridiem == null && hour <= 7 -> hour += 12 // IT/EE's "1:30"–"3:30" are afternoon slots
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Deterministic parse entry kept for callers/tests; understands the same label set. */
    fun parseTime(text: String): Int? = baseStartMinutes(text)

    /** Canonical day index for a header label; single letters fall back to position. */
    private fun dayIndexFor(label: String, column: Int, columnCount: Int): Int {
        val token = label.split(" ").firstOrNull()?.trim()?.uppercase().orEmpty()
        DAY_ORDER.indexOf(token.lowercase()).takeIf { it >= 0 }?.let { return it + 1 }
        when (token) {
            "MON" -> return 1
            "TUES", "TUE" -> return 2
            "WED" -> return 3
            "THURS", "THU" -> return 4
            "FRI" -> return 5
            "SAT" -> return 6
            "SUN" -> return 7
        }
        // Position fallback for single/double-letter columns (M T W TH F) —
        // teaching weeks run Monday..Saturday, so the column index maps directly.
        if (token.length <= 2 && columnCount in 5..6 && column < 6) return column + 1
        return column + 1
    }

    private fun extractCell(td: Element, group: String, day: Int, start: Int, end: Int): RawLecture? {
        val subject = td.selectFirst("span.subject")?.text()?.trim().nullIfBlank()
        val teacher = td.selectFirst("div.teacher")?.text()?.trim().nullIfBlank()
        val venue = td.selectFirst("div.room")?.text()?.trim().nullIfBlank()
        val tag = td.selectFirst("span.activitytag")?.text()?.trim()?.uppercase()
            ?.takeIf { it in KNOWN_TAGS }
        val rawText = td.text().replace(WS, " ").trim()
        return if (subject != null || teacher != null || venue != null) {
            build(group, day, start, end, subject, teacher, venue, tag, rawText)
        } else {
            extractDialectBCell(td, group, day, start, end, rawText)
        }
    }

    /**
     * Dialect-B extraction (CSE/IT/EE/ECE): lines are nested-table rows or
     * <br/> segments — [students set?, subject+type, teacher?, room?]. Each line
     * is classified by shape; the first line that is neither teacher, room nor
     * students-set becomes the subject.
     */
    private fun extractDialectBCell(
        td: Element, group: String, day: Int, start: Int, end: Int, rawText: String
    ): RawLecture? {
        val text = rawText.lowercase()
        if (text.isEmpty() || text in EMPTY_CELL_TEXT) return null

        val nested = td.selectFirst("table")
        val lines: List<String> = if (nested != null) {
            nested.select("tr").map { tr ->
                tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }
                    .joinToString(" · ") { it.text().replace(WS, " ").trim() }
                    .trim()
            }.filter { it.isNotEmpty() }
        } else {
            BR.split(td.html())
                .map { Jsoup.parse(it).text().replace(WS, " ").trim() }
                .filter { it.isNotEmpty() }
        }
        if (lines.isEmpty()) return null

        var teacher: String? = null
        var venue: String? = null
        val subjects = mutableListOf<String>()
        for (line in lines) {
            val low = line.lowercase()
            when {
                low in EMPTY_CELL_TEXT -> Unit
                teacher == null && isTeacherLine(line) -> teacher = line
                venue == null && isRoomLine(line) -> venue = line
                isStudentsSetLine(line, group) -> Unit
                else -> subjects.add(line)
            }
        }

        var subject: String? = null
        var tag: String? = null
        val subjectLine = subjects.firstOrNull()
        if (subjectLine != null) {
            val typeMatch = TYPE_SUFFIX.find(subjectLine)
            if (typeMatch != null) {
                tag = typeMatch.groupValues[1].takeIf { it in KNOWN_TAGS }
                subject = subjectLine.substringBefore(typeMatch.value).trim().ifEmpty { null }
            }
            if (subject == null) subject = subjectLine
        }
        if (subject == null && teacher == null && venue == null) return null
        return build(group, day, start, end, subject, teacher, venue, tag, rawText)
    }

    private fun build(
        group: String, day: Int, start: Int, end: Int,
        subject: String?, teacher: String?, venue: String?, tag: String?, rawText: String
    ): RawLecture {
        var confidence = 0.0
        if (subject != null) confidence += 0.45
        if (venue != null) confidence += 0.20
        if (teacher != null) confidence += 0.20
        if (tag != null) confidence += 0.15
        return RawLecture(group, day, start, end, subject, teacher, venue, tag, rawText, confidence)
    }

    private fun isTeacherLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2 || t.length > 80) return false
        if (TEACHER_PREFIX.containsMatchIn(t)) return true
        if (TEACHER_PAREN.containsMatchIn(t) && !t.contains('/')) return true
        // "KSK", "KSK, GS", "NSG, HKA" — initials, optionally several teachers.
        return t.split("·").map { it.trim() }.filter { it.isNotEmpty() }.all { TEACHER_INITIALS.matches(it) }
    }

    private fun isRoomLine(line: String): Boolean {
        // A room line may carry parallel rooms of a nested activity row ("G16 · S214").
        val segments = line.trim().split("·").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > 4) return false
        return segments.all { t ->
            t.length in 2..20 && (ROOM_SHORT.matches(t) || ROOM_LAB.matches(t) || ROOM_TOKEN.matches(t))
        }
    }

    private fun isStudentsSetLine(line: String, group: String): Boolean {
        val n = line.replace(Regex("[\\s·,]+"), "").uppercase()
        if (n.isEmpty()) return false
        val g = group.replace(Regex("[\\s_\\-]"), "").uppercase()
        // Every token must look like a group code (D2…, M1…, PHD…).
        val tokens = line.split(Regex("[\\s·,]+")).filter { it.isNotBlank() }
        if (tokens.isNotEmpty() && tokens.all { GROUP_TOKEN.matches(it.uppercase()) }) return true
        return g.length >= 3 && n.startsWith(g.substring(0, g.length.coerceAtMost(4))) && n != g
    }

    fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}

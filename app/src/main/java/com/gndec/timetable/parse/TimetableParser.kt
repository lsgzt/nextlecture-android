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
 * Multi-group FET timetable parser covering every dialect GNDEC publishes.
 *
 * Dialect A (older structured FET): span.subject / div.teacher / div.room.
 *
 * Dialect B (2026-09+ appsc plain-text + CSE/IT/EE/ECE exports):
 *   lines split by <br/> in fixed order observed on live documents:
 *     [students-set?]  subject[+type]  [teacher?]  [venue?]
 *   Example:
 *     ITB
 *     MATH I L
 *     SUKHMINDER SINGH
 *     F112
 *
 * Students-set tokens are short section codes (ITB, MEA, CEA) — never long
 * subject words. Teachers may lack titles. Rooms include free-form labs.
 */
object TimetableParser {

    const val PARSER_VERSION = 4
    const val SLOT_MINUTES = 60

    private val TIME_IN_LABEL = Regex("""(\d{1,2})[.:](\d{2})(?:\s*([AaPp][Mm]))?""")
    private val DAY_ORDER = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )
    private val KNOWN_TAGS = setOf("L", "P", "T")
    private val WS = Regex("""\s+""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    private val EMPTY_CELL_TEXT = setOf("-x-", "---", "-", "x", "--", "not available", "na", "n/a")
    private val SUBJECT_WITH_TYPE = Regex("""^(.+?)\s+([LPT])\.?\s*$""")
    private val TEACHER_PREFIX = Regex(
        """^(Pf|Dr|Mr|Mrs|Ms|Er|Prof|Ern)\.?\s+""",
        RegexOption.IGNORE_CASE
    )
    private val TEACHER_PAREN = Regex("""\([A-Za-z]{2,10}\)\s*$""")
    private val TEACHER_INITIALS = Regex("""^[A-Z]{2,6}((\s*,\s*|\s+)[A-Z]{2,6})*$""")

    // Short codes only — must NOT match ECONOMICS via EC prefix.
    private val SECTION_CODE = Regex(
        """^(?:D[1-4][A-Z0-9_]{0,6}|(?:ME|CE|EE|EC|IT|CS|RAI|ECBM)[A-Z0-9]{0,4}|M[123][A-Z0-9]{0,4}|PHD[A-Z0-9]{0,4})$""",
        RegexOption.IGNORE_CASE
    )

    private val ROOM_PATTERN = Regex(
        """(?i)^(?:[A-Z]{1,3}\s?-?\d{1,3}[A-Z]?|S-?\d{2,4}|[A-Z]{1,3}\d{0,3}\s*\([^)]{2,40}\)|W/?S\s+SEMINAR\s+HALL|WORKSHOPS?|(?:PHY|CHEM|COMP|ENG|BEE|PE|CGL|DBMS|HPC|WD|OS\d*|PL\d*)\s*LAB(?:[\s/()\-A-Z0-9]*)?|[A-Z0-9][A-Z0-9\s/()\-]{0,30}\s+LAB(?:[\s/()\-A-Z0-9]*)?)$"""
    )

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

    fun discoverGroups(doc: Document): List<GroupRef> {
        val seen = LinkedHashMap<String, GroupRef>()
        for (a in doc.select("ul a[href^=#table_]")) {
            val name = a.text().trim()
            if (name.isNotEmpty()) seen.putIfAbsent(name, GroupRef(name, a.attr("href").trim()))
        }
        return seen.values.toList()
    }

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
                val afterParen = text.substringAfterLast(')', "").trim()
                if (afterParen.isNotEmpty()) return afterParen
                val lastToken = text.split(" ").lastOrNull()?.trim().orEmpty()
                if (lastToken.any { it.isDigit() }) return lastToken
            }
        }
        val headerRow = table.select("thead tr").firstOrNull() ?: return null
        val headerTh = headerRow.children().firstOrNull {
            it.tagName() == "th" && !it.hasClass("xAxis") && it.text().trim().isNotEmpty()
        } ?: return null
        return headerTh.text().trim()
    }

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
            val yAxis = tr.children().firstOrNull { it.tagName() == "th" && it.hasClass("yAxis") } ?: continue
            val label = yAxis.text().trim()
            val start = labelStartMinutes(label, slotStarts) ?: continue
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

    private fun isEmptyCell(td: Element): Boolean {
        if (td.hasClass("empty")) return true
        val text = td.text().replace(WS, " ").trim()
        if (text.isEmpty()) return true
        return text.lowercase() in EMPTY_CELL_TEXT
    }

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

    fun baseStartMinutes(label: String): Int? {
        val m = TIME_IN_LABEL.find(label.trim()) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val meridiem = m.groupValues[3].uppercase().takeIf { it.isNotEmpty() }
        when {
            meridiem == "PM" && hour != 12 -> hour += 12
            meridiem == "AM" && hour == 12 -> hour = 0
            meridiem == null && hour <= 7 -> hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun parseTime(text: String): Int? = baseStartMinutes(text)

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

    private fun extractDialectBCell(
        td: Element, group: String, day: Int, start: Int, end: Int, rawText: String
    ): RawLecture? {
        val text = rawText.lowercase()
        if (text.isEmpty() || text in EMPTY_CELL_TEXT) return null
        if (text.startsWith("timetable generated")) return null

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

        val content = lines.filterNot { isStudentsSetLine(it, group) }
        if (content.isEmpty()) return null

        var subject: String? = null
        var tag: String? = null
        val rest = mutableListOf<String>()

        val typedIdx = content.indexOfFirst { SUBJECT_WITH_TYPE.matches(it.trim()) }
        if (typedIdx >= 0) {
            val m = SUBJECT_WITH_TYPE.find(content[typedIdx].trim())!!
            subject = m.groupValues[1].trim().ifEmpty { null }
            tag = m.groupValues[2].uppercase().takeIf { it in KNOWN_TAGS }
            content.forEachIndexed { i, line -> if (i != typedIdx) rest.add(line) }
        } else {
            val first = content.first()
            if (!isRoomLine(first)) {
                subject = first
                rest.addAll(content.drop(1))
            } else {
                rest.addAll(content)
            }
        }

        var teacher: String? = null
        var venue: String? = null
        for (line in rest) {
            when {
                venue == null && isRoomLine(line) -> venue = line
                teacher == null && isTeacherLine(line) -> teacher = line
                teacher == null && looksLikePersonName(line) -> teacher = line
                venue == null && !isTeacherLine(line) -> venue = line
            }
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

    private fun isStudentsSetLine(line: String, group: String): Boolean {
        val tokens = line.split(Regex("""[\s·,]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.all { it.length <= 8 && SECTION_CODE.matches(it) }) return true
        val g = group.replace(Regex("""[\s_\-]"""), "").uppercase()
        if (tokens.size == 1) {
            val n = tokens[0].replace(Regex("""[\s_\-]"""), "").uppercase()
            if (n.length in 2..6 && (g == n || g.startsWith(n))) return true
        }
        return false
    }

    private fun isRoomLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2 || t.length > 50) return false
        val segments = t.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > 4) return false
        return segments.all { ROOM_PATTERN.matches(it) }
    }

    private fun isTeacherLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2 || t.length > 100) return false
        if (isRoomLine(t)) return false
        if (SECTION_CODE.matches(t)) return false
        val parts = t.split(Regex("""[\s·,]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty() && parts.all { it.length <= 8 && SECTION_CODE.matches(it) }) return false
        if (TEACHER_PREFIX.containsMatchIn(t)) return true
        if (TEACHER_PAREN.containsMatchIn(t) && !t.any { it.isDigit() }) return true
        if (parts.all { TEACHER_INITIALS.matches(it) } && parts.none { SECTION_CODE.matches(it) }) return true
        return false
    }

    private fun looksLikePersonName(line: String): Boolean {
        val t = line.trim()
        if (t.length < 5 || t.length > 80) return false
        if (isRoomLine(t) || SECTION_CODE.matches(t)) return false
        if (t.any { it.isDigit() }) return false
        if (SUBJECT_WITH_TYPE.matches(t)) return false
        val words = t.replace(".", " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size < 2) return false
        return words.all { w -> w.first().isLetter() }
    }

    fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}

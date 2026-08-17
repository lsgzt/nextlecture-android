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

object TimetableParser {

    const val PARSER_VERSION = 1
    const val SLOT_MINUTES = 60

    private val TIME_REGEX = Regex("""(\d{1,2}):(\d{2})""")
    private val DAY_ORDER = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )
    private val KNOWN_TAGS = setOf("L", "P", "T")
    private val WS = Regex("\\s+")

    /**
     * Parse the full multi-group document.
     * @return map of group name -> lectures (only groups with at least one lecture)
     * @throws ParseException if the document contains no usable timetable
     */
    fun parse(html: String): Map<String, List<RawLecture>> {
        if (html.isBlank()) throw ParseException("empty html")
        val doc = Jsoup.parse(html)
        val refs = discoverGroups(doc)
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

    /** Fallback discovery when there is no table of contents: use table captions. */
    fun discoverGroupsFromTables(doc: Document): List<GroupRef> {
        val out = mutableListOf<GroupRef>()
        for (t in doc.select("table[id^=table_]")) {
            val name = t.selectFirst("caption span.name")?.text()?.trim() ?: continue
            if (name.isNotEmpty()) out.add(GroupRef(name, "#" + t.id()))
        }
        return out
    }

    /**
     * Parse one group's timetable grid.
     * Rows = time slots (th.yAxis), columns = days (thead th.xAxis).
     * Handles rowspan (multi-hour practicals) and empty cells.
     */
    fun parseTable(table: Element, group: String): List<RawLecture> {
        val dayHeaders = table.select("thead th.xAxis").map { it.text().trim() }
        if (dayHeaders.isEmpty()) return emptyList()
        val days = dayHeaders.mapIndexed { i, name ->
            val idx = DAY_ORDER.indexOf(name.lowercase())
            if (idx >= 0) idx + 1 else i + 1
        }
        val nCols = days.size
        val rowSpanLeft = IntArray(nCols)
        val out = mutableListOf<RawLecture>()
        for (tr in table.select("tbody tr")) {
            val yAxis = tr.selectFirst("th.yAxis") ?: continue
            val start = parseTime(yAxis.text()) ?: continue
            val tds = tr.select("td")
            var col = 0
            var ti = 0
            while (col < nCols) {
                if (rowSpanLeft[col] > 0) { rowSpanLeft[col]--; col++; continue }
                if (ti >= tds.size) break
                val td = tds[ti++]
                val rowspan = td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                if (!td.hasClass("empty")) {
                    extractCell(td, group, days[col], start, start + SLOT_MINUTES * rowspan)
                        ?.let { out.add(it) }
                }
                if (rowspan > 1) rowSpanLeft[col] = rowspan - 1
                col++
            }
        }
        return out
    }

    private fun extractCell(
        td: Element, group: String, day: Int, start: Int, end: Int
    ): RawLecture? {
        val subject = td.selectFirst("span.subject")?.text()?.trim().nullIfBlank()
        val teacher = td.selectFirst("div.teacher")?.text()?.trim().nullIfBlank()
        val venue = td.selectFirst("div.room")?.text()?.trim().nullIfBlank()
        val tag = td.selectFirst("span.activitytag")?.text()?.trim()?.uppercase()
            ?.takeIf { it in KNOWN_TAGS }
        val rawText = td.text().replace(WS, " ").trim()
        if (subject == null && teacher == null && venue == null) return null
        var confidence = 0.0
        if (subject != null) confidence += 0.45
        if (venue != null) confidence += 0.20
        if (teacher != null) confidence += 0.20
        if (tag != null) confidence += 0.15
        return RawLecture(group, day, start, end, subject, teacher, venue, tag, rawText, confidence)
    }

    fun parseTime(text: String): Int? {
        val m = TIME_REGEX.find(text.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}

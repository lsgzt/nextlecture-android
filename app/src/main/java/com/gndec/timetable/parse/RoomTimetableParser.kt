package com.gndec.timetable.parse

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One room's state in a single 1-hour slot of the weekly room timetable. */
@Serializable
data class SlotOccupancy(
    val busy: Boolean = false,
    val subject: String? = null,
    val teacher: String? = null,
    val studentsSet: String? = null,
    val activity: String? = null
)

/** A single room: occupancy is indexed [dayIndex][slotIndex]. */
@Serializable
data class RoomSchedule(
    val name: String,
    val occupancy: List<List<SlotOccupancy>>
)

/**
 * The complete parsed room timetable document.
 * [days] are weekday labels ("Monday"…), [slots] are slot START times ("08:30"…).
 * Room order is preserved from the source document (block-wise grouping).
 */
@Serializable
data class RoomTimetableData(
    val sourceUrl: String,
    val fetchedAtMillis: Long,
    val days: List<String>,
    val slots: List<String>,
    val rooms: List<RoomSchedule>
)

/**
 * Parses the FET-generated "rooms days horizontal" HTML that the college
 * publishes under the Room Time Table link on https://appsc.gndec.ac.in/time_tables.
 *
 * Document shape (verified against the live weekly files):
 *  - one `<table id="table_N">` per room, with `<caption><span class="name">ROOM</span></caption>`
 *  - `<thead>` holds one `<th class="xAxis">` per weekday column
 *  - `<tbody>` rows start with `<th class="yAxis">HH:MM</th>` followed by one `<td>` per day
 *  - an empty slot is `<td class="empty">---`
 *  - a multi-slot practical is a single `<td rowspan="N">`; continuation rows carry a
 *    `<!-- span -->` comment placeholder at the spanned position, so column alignment
 *    must be tracked manually (same technique as [TimetableParser.parseTable])
 *  - each table ends with a footer row `<td colspan="5">Timetable generated…` that has
 *    no yAxis header and is therefore skipped
 */
object RoomTimetableParser {

    /** @throws ParseException when the document contains no usable room tables. */
    fun parse(html: String, sourceUrl: String, fetchedAtMillis: Long): RoomTimetableData {
        if (html.isBlank()) throw ParseException("empty room timetable html")
        val doc = Jsoup.parse(html)
        val roomTables = doc.select("table[id^=table_]").filter { table ->
            table.selectFirst("caption span.name") != null
        }
        if (roomTables.isEmpty()) throw ParseException("no room tables discovered in document")

        val first = roomTables.first()
        val days = first.select("thead th.xAxis").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (days.isEmpty()) throw ParseException("no weekday columns found")
        val slotRows = first.select("tbody tr").filter { it.selectFirst("th.yAxis") != null }
        val slots = slotRows.mapNotNull { slotLabel(it) }
        if (slots.isEmpty()) throw ParseException("no time slots found")

        val rooms = mutableListOf<RoomSchedule>()
        val seen = HashSet<String>()
        for (table in roomTables) {
            val name = table.selectFirst("caption span.name")?.text()?.trim().orEmpty()
            if (name.isEmpty() || !seen.add(name)) continue
            val occupancy = parseOccupancy(table, days.size, slots) ?: continue
            rooms.add(RoomSchedule(name = name, occupancy = occupancy))
        }
        if (rooms.isEmpty()) throw ParseException("no room schedules parsed from document")

        return RoomTimetableData(
            sourceUrl = sourceUrl,
            fetchedAtMillis = fetchedAtMillis,
            days = days,
            slots = slots,
            rooms = rooms
        )
    }

    private fun slotLabel(row: Element): String? {
        val text = row.selectFirst("th.yAxis")?.text()?.trim().orEmpty()
        return text.takeIf { Regex("""\d{1,2}:\d{2}""").containsMatchIn(it) }
    }

    /**
     * Builds the [day][slot] occupancy grid for one room table, carrying rowspanned
     * practicals forward into the slots they occupy.
     */
    private fun parseOccupancy(table: Element, dayCount: Int, slots: List<String>): List<List<SlotOccupancy>>? {
        val rows = table.select("tbody tr").filter { it.selectFirst("th.yAxis") != null }
        if (rows.isEmpty()) return null

        val grid = List(dayCount) { MutableList(slots.size) { SlotOccupancy() } }
        val spanLeft = IntArray(dayCount)
        val spanCarry = arrayOfNulls<SlotOccupancy>(dayCount)

        rows.forEachIndexed { rowIndex, tr ->
            val label = slotLabel(tr)
            val slotIndex = label?.let { slots.indexOf(it) }?.takeIf { it >= 0 }
                ?: rowIndex.takeIf { it < slots.size }
                ?: return@forEachIndexed

            val tds = tr.select("td")
            var col = 0
            var ti = 0
            while (col < dayCount) {
                if (spanLeft[col] > 0) {
                    grid[col][slotIndex] = spanCarry[col] ?: SlotOccupancy(busy = true)
                    spanLeft[col]--
                    col++
                    continue
                }
                if (ti >= tds.size) break
                val td = tds[ti++]
                val rowspan = td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val cell = parseCell(td)
                grid[col][slotIndex] = cell
                if (rowspan > 1) {
                    spanLeft[col] = rowspan - 1
                    spanCarry[col] = cell
                }
                col++
            }
            // Trailing spans: a rowspanned cell that began earlier in this row's day columns.
            while (col < dayCount && spanLeft[col] > 0) {
                grid[col][slotIndex] = spanCarry[col] ?: SlotOccupancy(busy = true)
                spanLeft[col]--
                col++
            }
        }
        return grid
    }

    private fun parseCell(td: Element): SlotOccupancy {
        val text = td.text().trim()
        if (td.hasClass("empty") || text.isEmpty() || text == "---") return SlotOccupancy(busy = false)
        val subject = td.selectFirst("span.subject")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val teacher = td.selectFirst("div.teacher")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val studentsSet = td.selectFirst("div.studentsset")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val activity = td.selectFirst("span.activitytag")?.text()?.trim()?.uppercase()
            ?.takeIf { it in setOf("L", "P", "T") }
        return SlotOccupancy(
            busy = true,
            subject = subject,
            teacher = teacher,
            studentsSet = studentsSet,
            activity = activity
        )
    }
}

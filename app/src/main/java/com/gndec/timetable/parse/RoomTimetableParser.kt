package com.gndec.timetable.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses the FET-generated timetable HTML files that GNDEC departments publish.
 *
 * Two export dialects plus one hybrid appear across the live files
 * (see docs/ROOM_TIMETABLE_SOURCES.md):
 *
 *  Dialect A — FET 7.x "rooms days horizontal" (appsc, ECE, EE, CE, IT, MCA, MBA):
 *    `<caption><span class="institution">…</span><br/><span class="name">ROOM</span></caption>`,
 *    `<th class="yAxis">08:30</th>` (also "8:30", "1:30", CE's "8.30 AM (1ST)"),
 *    `<td class="empty">---` for free slots, busy cells carry `span.subject`,
 *    `div.teacher`, `div.studentsset`, `span.activitytag` and (in group exports)
 *    room spans `<span class="r_N">F119</span>`. Cells may contain a nested
 *    `<table class="detailed">` with several parallel activities (ME groups export).
 *
 *  Dialect B — FET 6.x (CSE): caption holds only the institution name and the
 *    room name is the `<th colspan="N">G12</th>` of the first header row;
 *    `<th class="yAxis">08:30-09:30</th>` range labels; cells are plain text
 *    separated by `<br/>` ("D3 CS C / Dr. X (MKM) / DAA L") with `---` for free.
 *
 * Every document ends with a footer row "Timetable generated with FET … on
 * M/D/YY H:MM[ ]AM/PM" whose timestamp is used to reject stale sessions.
 */
object RoomTimetableParser {

    /** One parsed source document, before merging. */
    data class ParsedDoc(
        val kind: SourceKind,
        /** Canonical day index (0=Monday…6=Sunday) per source column; null = unknown. */
        val days: List<Int?>,
        val slotStarts: List<Int>,
        val rooms: List<SourceRoom>,
        val generatedAtMillis: Long?
    )

    /** Internal cell shape while parsing one table's grid. */
    private data class GridCell(
        val busy: Boolean,
        val subject: String? = null,
        val teacher: String? = null,
        val studentsSet: String? = null,
        val activity: String? = null,
        val roomNames: List<String> = emptyList()
    ) {
        companion object {
            val FREE = GridCell(busy = false)
        }
    }

    private val GENERATED_ON = Regex(
        """generated with FET\s+\S+\s+on\s+(\d{1,2})/(\d{1,2})/(\d{2,4})\s+(\d{1,2}):(\d{2})[\u202F\u00A0 ]*([AaPp][Mm])?"""
    )
    private val TIME_IN_LABEL = Regex("""(\d{1,2})[.:](\d{2})(?:\s*([AaPp][Mm]))?""")
    private val DIALECT_B_ACTIVITY = Regex("""\(?([LPT])\)?\s*\.?\s*$""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /**
     * Parses a ROOMS document (one table per room). Room captions containing a
     * comma (e.g. "S202, S203") produce one room per name sharing the grid.
     */
    fun parseRoomsDoc(html: String, rootId: String, url: String, fetchedAtMillis: Long): ParsedDoc =
        parseDoc(html, rootId, url, fetchedAtMillis, SourceKind.ROOMS)

    /**
     * Parses a GROUPS document: rooms are taken from room spans inside the
     * cells of every group table (fallback for departments without a rooms
     * export, e.g. Mechanical & Production).
     */
    fun parseGroupsDoc(html: String, rootId: String, url: String, fetchedAtMillis: Long): ParsedDoc =
        parseDoc(html, rootId, url, fetchedAtMillis, SourceKind.GROUPS_CELLS)

    private fun parseDoc(
        html: String,
        rootId: String,
        url: String,
        fetchedAtMillis: Long,
        kind: SourceKind
    ): ParsedDoc {
        if (html.isBlank()) throw ParseException("empty room timetable html")
        val doc = Jsoup.parse(html)
        val tables = doc.select("table[id^=table_]").filter { tableName(it) != null }
        if (tables.isEmpty()) throw ParseException("no room tables discovered in document")

        val first = tables.first()
        val days = resolveDays(first)
        if (days.none { it in 0..5 }) throw ParseException("no weekday columns found")

        val slotStarts = slotStartsOf(first)
        if (slotStarts.isEmpty()) throw ParseException("no time slots found")

        val generatedAt = parseGeneratedAtMillis(html)
        val dayRowCount = 7
        val byKey = LinkedHashMap<String, SourceRoom>()

        for (table in tables) {
            val grid = parseTableGrid(table, days, slotStarts.size) ?: continue
            when (kind) {
                SourceKind.ROOMS -> {
                    val rawName = tableName(table) ?: continue
                    val names = if (rawName.contains(',')) {
                        rawName.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        listOf(rawName)
                    }
                    for (name in names) {
                        val key = RoomNameNormalizer.canonical(name) ?: continue
                        if (byKey.containsKey(key)) continue
                        // Re-index source columns into canonical day rows.
                        val canonical = List(dayRowCount) {
                            MutableList<RoomCell?>(slotStarts.size) { null }
                        }
                        for ((col, dayIdx) in days.withIndex()) {
                            if (dayIdx == null || dayIdx !in canonical.indices) continue
                            val row = grid.getOrNull(col) ?: continue
                            for (slot in row.indices) {
                                canonical[dayIdx][slot] = row[slot]?.toRoomCell()
                            }
                        }
                        byKey[key] = SourceRoom(key = key, name = name, occupancy = canonical)
                    }
                }

                SourceKind.GROUPS_CELLS -> {
                    for (colIdx in grid.indices) {
                        val dayIdx = days.getOrNull(colIdx) ?: continue
                        if (dayIdx !in 0..6) continue
                        for (slotIdx in grid[colIdx].indices) {
                            val cell = grid[colIdx][slotIdx] ?: continue
                            if (!cell.busy) continue
                            for (rawRoom in cell.roomNames) {
                                val key = RoomNameNormalizer.canonical(rawRoom) ?: continue
                                val existing = byKey[key]
                                val rows: MutableList<MutableList<RoomCell?>> = mutableListOf()
                                if (existing != null) {
                                    for (rowCells in existing.occupancy) {
                                        rows.add(rowCells.toMutableList())
                                    }
                                } else {
                                    for (d in 0 until dayRowCount) {
                                        val blankRow: MutableList<RoomCell?> =
                                            MutableList(slotStarts.size) { null }
                                        rows.add(blankRow)
                                    }
                                }
                                val current = rows[dayIdx][slotIdx]
                                if (current == null || !current.busy) {
                                    rows[dayIdx][slotIdx] = cell.toRoomCell()
                                    byKey[key] = SourceRoom(
                                        key = key,
                                        name = existing?.name ?: rawRoom,
                                        occupancy = rows
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (byKey.isEmpty()) throw ParseException("no room schedules parsed from document")

        return ParsedDoc(
            kind = kind,
            days = days,
            slotStarts = slotStarts,
            rooms = byKey.values.toList(),
            generatedAtMillis = generatedAt
        )
    }

    private fun GridCell?.toRoomCell(): RoomCell? = when {
        this == null || !busy -> RoomCell.FREE
        else -> RoomCell(
            busy = true,
            subject = subject,
            teacher = teacher,
            studentsSet = studentsSet,
            activity = activity
        )
    }

    /** Raw room/group name of a table: dialect A caption, else dialect B colspan header. */
    private fun tableName(table: Element): String? {
        val captionName = table.selectFirst("caption span.name")?.text()?.trim()
        if (!captionName.isNullOrEmpty()) return captionName
        val colspanHeader = table.selectFirst("thead th[colspan]")?.text()?.trim()
        return colspanHeader?.takeIf { it.isNotEmpty() }
    }

    /**
     * Maps the table's day columns to canonical day indices (0=Monday…).
     * Full/abbreviated names are parsed directly; single-letter headers
     * (ECE's "M T W T F") fall back to column position. A column that cannot
     * be placed on the week is null and is skipped downstream.
     */
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
        // Position fallback for single-letter columns (M/T/W/T/F) — teaching
        // weeks run Monday..Saturday, so the column index maps directly.
        if (token.length <= 2 && columnCount in 5..6 && column < 6) return column
        return null
    }

    private fun slotStartsOf(table: Element): List<Int> {
        val starts = mutableListOf<Int>()
        for (row in table.select("tbody tr")) {
            val label = row.selectFirst("th.yAxis")?.text() ?: continue
            val minutes = slotStartMinutes(label) ?: continue
            if (minutes !in starts) starts.add(minutes)
        }
        return starts
    }

    /** "08:30", "8:30", "1:30" (PM implied), "8.30 AM (1ST)", "08:30-09:30" → minutes. */
    fun slotStartMinutes(label: String): Int? {
        val m = TIME_IN_LABEL.find(label) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val meridiem = m.groupValues[3].uppercase().takeIf { it.isNotEmpty() }
        when {
            meridiem == "PM" && hour != 12 -> hour += 12
            meridiem == "AM" && hour == 12 -> hour = 0
            meridiem == null && hour <= 7 -> hour += 12 // IT's "1:30"–"3:30" are afternoon slots
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** "Timetable generated with FET 7.6.4 on 8/30/26 10:39 PM" → epoch millis. */
    fun parseGeneratedAtMillis(html: String): Long? {
        val m = GENERATED_ON.find(html) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        var year = m.groupValues[3].toIntOrNull() ?: return null
        if (year in 0..99) year += 2000
        var hour = m.groupValues[4].toIntOrNull() ?: return null
        val minute = m.groupValues[5].toIntOrNull() ?: return null
        val meridiem = m.groupValues[6].uppercase().takeIf { it.isNotEmpty() }
        when {
            meridiem == "PM" && hour != 12 -> hour += 12
            meridiem == "AM" && hour == 12 -> hour = 0
        }
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        return runCatching {
            java.util.Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis
        }.getOrNull()
    }

    /**
     * Builds the raw working grid for one table: rows = source day columns,
     * inner lists = slot rows. Handles rowspan carry identical to the merged
     * output semantics and tolerates nested "detailed" tables (Jsoup keeps
     * them inside the outer td).
     */
    private fun parseTableGrid(
        table: Element,
        days: List<Int?>,
        slotCount: Int
    ): List<List<GridCell?>>? {
        val rows = table.select("tbody tr").filter { it.selectFirst("th.yAxis") != null }
        if (rows.isEmpty()) return null

        val sourceDayCount = days.size
        val grid = List(sourceDayCount) { MutableList<GridCell?>(slotCount) { null } }
        val spanLeft = IntArray(sourceDayCount)
        val spanCarry = arrayOfNulls<GridCell>(sourceDayCount)
        val slotIndexOf = HashMap<Int, Int>()
        slotStartsOf(table).forEachIndexed { i, minutes -> slotIndexOf[minutes] = i }

        var rowIndex = 0
        for (tr in rows) {
            val label = tr.selectFirst("th.yAxis")?.text().orEmpty()
            val slotIndex = slotIndexOf[slotStartMinutes(label) ?: -1]
                ?: rowIndex.takeIf { it < slotCount }
                ?: continue
            rowIndex++

            var column = 0
            // Direct children only — nested "detailed" tables contain their own
            // td/tr structure that must NOT be treated as day columns.
            val cells = tr.children().filter { it.tagName() == "td" }
            var cellIndex = 0
            while (column < sourceDayCount) {
                if (spanLeft[column] > 0) {
                    grid[column][slotIndex] = spanCarry[column] ?: GridCell(busy = true)
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
            while (column < sourceDayCount && spanLeft[column] > 0) {
                grid[column][slotIndex] = spanCarry[column] ?: GridCell(busy = true)
                spanLeft[column]--
                column++
            }
        }
        return grid
    }

    private fun parseCell(td: Element): GridCell? {
        val text = td.text().trim()
        if (td.hasClass("empty") || text.isEmpty() || text == "---") return GridCell.FREE

        val subjects = td.select("span.subject").map { it.text().trim() }.filter { it.isNotEmpty() }
        val teachers = td.select("div.teacher").map { it.text().trim() }.filter { it.isNotEmpty() }
        val sets = td.select("div.studentsset").map { it.text().trim() }.filter { it.isNotEmpty() }
        val activity = td.select("span.activitytag").firstOrNull()?.text()?.trim()
            ?.uppercase()?.takeIf { it in setOf("L", "P", "T") }
        val roomNames = td.select("span[class^=r_]").map { it.text().trim() }.filter { it.isNotEmpty() }

        if (subjects.isEmpty() && teachers.isEmpty() && sets.isEmpty() && roomNames.isEmpty()) {
            // Dialect B (plain text lines): "D3 CS C / Dr. X (MKM) / DAA L"
            val lines = BR.split(td.html())
                .map { Jsoup.parse(it).text().trim() }
                .filter { it.isNotEmpty() && it != "---" }
            if (lines.isEmpty()) return null
            var subject: String? = null
            var act: String? = null
            val subjectLine = lines.last()
            val activityMatch = DIALECT_B_ACTIVITY.find(subjectLine)
            if (activityMatch != null) {
                act = activityMatch.groupValues[1]
                val stripped = subjectLine.substringBefore(activityMatch.value).trim()
                subject = stripped.ifEmpty { subjectLine }
            } else {
                subject = subjectLine
            }
            val teacher = lines.getOrNull(lines.size - 2)
            val studentsSet = lines.getOrNull(lines.size - 3)
            return GridCell(
                busy = true,
                subject = subject,
                teacher = teacher,
                studentsSet = studentsSet,
                activity = act,
                roomNames = emptyList()
            )
        }

        return GridCell(
            busy = true,
            subject = subjects.joinToString(" · ").ifEmpty { null },
            teacher = teachers.joinToString(" · ").ifEmpty { null },
            studentsSet = sets.joinToString(" · ").ifEmpty { null },
            activity = activity,
            roomNames = roomNames
        )
    }
}

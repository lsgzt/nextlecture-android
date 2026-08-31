package com.gndec.timetable.parse

import kotlinx.serialization.Serializable

/**
 * State of one physical room in one 1-hour slot, merged across every published
 * GNDEC room timetable. A `null` cell (absent from the list) means NO source
 * covers that day/slot for the room — it must never be reported vacant.
 */
@Serializable
data class RoomCell(
    val busy: Boolean,
    val subject: String? = null,
    val teacher: String? = null,
    val studentsSet: String? = null,
    val activity: String? = null
) {
    val isFree: Boolean get() = !busy

    companion object {
        val FREE = RoomCell(busy = false)
    }
}

/**
 * One room of one source document. [key] is the normalized name used for
 * merging across sources; [name] is the raw name exactly as published.
 * Occupancy is indexed [dayIndex][slotIndex] where dayIndex is 0=Monday…4=Friday
 * and slotIndex positions into [SourceRoomDoc.slotStarts]; `null` = not covered.
 */
@Serializable
data class SourceRoom(
    val key: String,
    val name: String,
    val occupancy: List<List<RoomCell?>>
)

/** How a source document describes room usage. */
enum class SourceKind {
    /** FET "rooms days horizontal" export: one table per room. */
    ROOMS,

    /** FET groups export: rooms extracted from the cells of group tables. */
    GROUPS_CELLS
}

/**
 * Parsed + normalized snapshot of ONE departmental (or college-wide) room
 * timetable document. [slotStarts] are minutes-since-midnight slot START times.
 */
@Serializable
data class SourceRoomDoc(
    val rootId: String,
    val kind: SourceKind,
    val url: String,
    val slotStarts: List<Int>,
    val rooms: List<SourceRoom>,
    /** FET footer generation timestamp, when present (ms since epoch). */
    val generatedAtMillis: Long?,
    val fetchedAtMillis: Long
)

/** UI-facing summary of one contributing source. */
@Serializable
data class RoomSourceSummary(
    val rootId: String,
    val kind: SourceKind,
    val url: String,
    val roomCount: Int,
    val generatedAtMillis: Long?,
    val fetchedAtMillis: Long
)

/** One physical room in the merged global dataset. */
@Serializable
data class MergedRoom(
    /** Stable merge key (normalized name, department-scoped for ambiguous labs). */
    val key: String,
    val name: String,
    val occupancy: List<List<RoomCell?>>
)

/**
 * The global GNDEC room availability dataset: every room covered by every
 * valid published timetable, merged busy-wins across sources.
 * Occupancy is [dayIndex 0=Monday…][slotIndex into slotStarts]; `null` = unknown.
 */
@Serializable
data class GlobalRoomData(
    val sources: List<RoomSourceSummary>,
    /** Roots that could not be checked during the last refresh (may be empty). */
    val incompleteRoots: List<String>,
    val days: List<String>,
    val slotStarts: List<Int>,
    val rooms: List<MergedRoom>,
    val fetchedAtMillis: Long
)

package com.gndec.timetable.domain

import com.gndec.timetable.parse.MergedRoom
import com.gndec.timetable.parse.GlobalRoomData
import com.gndec.timetable.parse.RoomCell
import com.gndec.timetable.parse.RoomNameNormalizer
import com.gndec.timetable.parse.RoomSourceSummary
import com.gndec.timetable.parse.SourceRoomDoc

/**
 * Merges every valid departmental room-timetable document into one global
 * GNDEC room availability dataset.
 *
 * Semantics (validated against the live published files — see
 * docs/ROOM_TIMETABLE_SOURCES.md):
 *  - days are canonical Monday..Sunday rows; every source's columns are mapped;
 *  - the global slot timeline is the sorted union of all sources' slot starts;
 *  - a room is BUSY at a slot when ANY source has an activity there (busy wins);
 *  - a cell stays `null` when no source covers that day/slot — unknown, never vacant;
 *  - generic (non room-like) names colliding across >= 2 department files — with
 *    the college-wide appsc file absent — are scoped per department because they
 *    can be genuinely different rooms (CE's vs ECE's "COMP LAB"); collisions that
 *    include the college-wide file merge globally;
 *  - FET placeholder rooms (GHOST ROOM, TEACH OFFICE, …) are hidden.
 */
object RoomMerger {

    /** Display order / display-name priority of the discovery roots. */
    val ROOT_ORDER: List<String> = listOf(
        "appsc", "cse", "ece", "ee", "me", "ce", "it", "mca", "mba"
    )

    private val DAY_NAMES = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    fun merge(docs: List<SourceRoomDoc>, incompleteRoots: List<String>): GlobalRoomData {
        val orderedDocs = docs.sortedBy { rootPriority(it.rootId) }

        val slotUnion = orderedDocs.flatMap { it.slotStarts }.distinct().sorted()
        val slotIndexOf = slotUnion.withIndex().associate { (i, s) -> s to i }

        val rooms = LinkedHashMap<String, MergedRoomData>()

        // Pass 1: which canonical keys come from which roots (for ambiguity scoping).
        val rootsByKey = HashMap<String, MutableSet<String>>()
        for (doc in orderedDocs) {
            for (room in doc.rooms) {
                rootsByKey.getOrPut(room.key) { mutableSetOf() }.add(doc.rootId)
            }
        }
        val scopedKey = HashMap<Pair<String, String>, String>() // (rootId, key) -> scoped key
        for ((key, roots) in rootsByKey) {
            if (!RoomNameNormalizer.isRoomLike(key) && "appsc" !in roots && roots.size >= 2) {
                for (root in roots) scopedKey[root to key] = "$root:$key"
            }
        }

        // Pass 2: busy-wins merge in root priority order.
        for (doc in orderedDocs) {
            for (room in doc.rooms) {
                val key = scopedKey[doc.rootId to room.key] ?: room.key
                val entry = rooms.getOrPut(key) {
                    MergedRoomData(
                        names = mutableListOf(),
                        occupancy = List(7) { MutableList<RoomCell?>(slotUnion.size) { null } }
                    )
                }
                if (entry.names.none { it.second == room.name }) {
                    entry.names.add(rootPriority(doc.rootId) to room.name)
                }
                for ((col, cells) in room.occupancy.withIndex()) {
                    if (col !in 0..6) continue
                    for ((slot, cell) in cells.withIndex()) {
                        val globalSlot = slotIndexOf[doc.slotStarts.getOrNull(slot)] ?: continue
                        val current = entry.occupancy[col][globalSlot]
                        if (cell == null) continue
                        entry.occupancy[col][globalSlot] = when {
                            current?.busy == true -> current
                            cell.busy -> cell
                            else -> current ?: cell
                        }
                    }
                }
            }
        }

        val mergedRooms = rooms.entries
            .filter { !RoomNameNormalizer.isPlaceholder(it.key) }
            .map { (key, data) ->
                val displayName = if (scopedKey.values.contains(key)) {
                    displayNameForScoped(key, data.names)
                } else {
                    RoomNameNormalizer.displayName(data.names)
                }
                MergedRoom(key = key, name = displayName, occupancy = data.occupancy.map { it.toList() })
            }
            .sortedWith(roomSort)

        return GlobalRoomData(
            sources = orderedDocs.map { doc ->
                RoomSourceSummary(
                    rootId = doc.rootId,
                    kind = doc.kind,
                    url = doc.url,
                    roomCount = doc.rooms.size,
                    generatedAtMillis = doc.generatedAtMillis,
                    fetchedAtMillis = doc.fetchedAtMillis
                )
            },
            incompleteRoots = incompleteRoots.distinct().sorted(),
            days = DAY_NAMES,
            slotStarts = slotUnion,
            rooms = mergedRooms,
            fetchedAtMillis = orderedDocs.maxOf { it.fetchedAtMillis }
        )
    }

    private class MergedRoomData(
        val names: MutableList<Pair<Int, String>>,
        val occupancy: List<MutableList<RoomCell?>>
    )

    private fun displayNameForScoped(scopedKey: String, names: List<Pair<Int, String>>): String {
        val rootId = scopedKey.substringBefore(':')
        val raw = RoomNameNormalizer.displayName(names)
        return "$raw · ${rootLabel(rootId)}"
    }

    /** Short human label for a discovery root, used in scoped display names. */
    fun rootLabel(rootId: String): String = when (rootId) {
        "appsc" -> "College"
        "cse" -> "CSE"
        "ece" -> "ECE"
        "ee" -> "EE"
        "me" -> "ME"
        "ce" -> "CE"
        "it" -> "IT"
        "mca" -> "MCA"
        "mba" -> "MBA"
        else -> rootId.uppercase()
    }

    private fun rootPriority(rootId: String): Int =
        ROOT_ORDER.indexOf(rootId).takeIf { it >= 0 } ?: ROOT_ORDER.size

    /** Numbered classrooms first (F101…, G1…, S205…), then the rest alphabetically. */
    private val ROOM_LIKE = Regex("""^([A-Z]{1,3})(\d{1,4})([A-Z]{0,2})$""")

    private val roomSort = Comparator<MergedRoom> { a, b ->
        // Scoped display names ("COMP LAB · CE") sort by their base name.
        val ka = a.name.substringBefore(" · ").trim()
        val kb = b.name.substringBefore(" · ").trim()
        val ma = ROOM_LIKE.matchEntire(ka)
        val mb = ROOM_LIKE.matchEntire(kb)
        when {
            ma != null && mb != null -> {
                val prefix = ma.groupValues[1].compareTo(mb.groupValues[1])
                if (prefix != 0) prefix
                else {
                    val num = ma.groupValues[2].toInt() - mb.groupValues[2].toInt()
                    if (num != 0) num
                    else ma.groupValues[3].compareTo(mb.groupValues[3])
                }
            }

            ma != null -> -1
            mb != null -> 1
            else -> a.name.compareTo(b.name, ignoreCase = true)
        }
    }
}

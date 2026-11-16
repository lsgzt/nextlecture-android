package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import kotlinx.serialization.Serializable

/**
 * A classmate the student has added locally, so their free periods can be
 * compared against the student's own. Purely on-device: nothing about a friend
 * is ever sent anywhere, and removing one removes it permanently.
 */
@Serializable
data class Friend(
    val name: String,
    /** Exact timetable group, as it appears in the parsed document (e.g. "ITB2"). */
    val group: String
) {
    val isUsable: Boolean get() = name.isNotBlank() && group.isNotBlank()
}

/**
 * One friend resolved against a particular day.
 *
 * [hasData] matters more than it looks. The local `lectures` table holds every
 * parsed group, so a group the device has never parsed simply comes back empty —
 * and reporting that as "your friend is free all day" would be a confident
 * invention. Callers must skip friends whose group has no timetable at all.
 */
data class FriendDay(
    val friend: Friend,
    val lectures: List<LectureEntity>,
    val hasData: Boolean
) {
    /**
     * True when nothing in [startMinutes]..[endMinutes] overlaps one of this
     * friend's lectures. Touching endpoints do not count as an overlap, so a
     * friend whose class ends exactly when the slot starts is free.
     */
    fun isFreeDuring(startMinutes: Int, endMinutes: Int): Boolean =
        lectures.none { it.startMinutes < endMinutes && it.endMinutes > startMinutes }
}

/** Names of the friends who are free for the whole of [startMinutes]..[endMinutes]. */
fun List<FriendDay>.freeFriendNames(startMinutes: Int, endMinutes: Int): List<String> =
    filter { it.hasData && it.isFreeDuring(startMinutes, endMinutes) }
        .map { it.friend.name }

/**
 * A stretch of time a friend is actually free, clamped to the period that was
 * asked about. [startMinutes]..[endMinutes] never extends past the queried
 * window, so a "(12:30 PM - 1:30 PM)" label under a 12:30-2:30 class is honest.
 */
data class FriendFreeWindow(
    val name: String,
    val startMinutes: Int,
    val endMinutes: Int
)

/**
 * Free slivers shorter than this are not worth reporting — they come from odd
 * timetable seams (a 5-minute gap between two lectures), not from real free
 * time a friend could spend with you.
 */
const val MinUsefulFreeMinutes = 10

/**
 * Every friend's actual free time inside [startMinutes]..[endMinutes], as one
 * entry per maximal free stretch.
 *
 * The whole-window form of this check ([freeFriendNames]) only reports friends
 * free for the ENTIRE period, which hides the common case: a 2-hour period
 * where the friend is free for the first hour only. This walk subtracts the
 * friend's lectures from the window, so partial overlap shows up as its own
 * window — "Aarav is free 12:30 PM - 1:30 PM" — and a friend free the whole
 * period reports exactly that window. Friends without timetable data are
 * skipped, same as everywhere else.
 */
fun List<FriendDay>.friendsFreeWithin(startMinutes: Int, endMinutes: Int): List<FriendFreeWindow> {
    if (endMinutes <= startMinutes) return emptyList()
    val windows = mutableListOf<FriendFreeWindow>()
    for (day in this) {
        if (!day.hasData) continue
        var cursor = startMinutes
        for (lecture in day.lectures.sortedBy { it.startMinutes }) {
            if (cursor >= endMinutes) break
            // Clip this lecture to the window; lectures entirely outside it do not move the cursor.
            val busyStart = maxOf(lecture.startMinutes, startMinutes)
            val busyEnd = minOf(lecture.endMinutes, endMinutes)
            if (busyEnd <= busyStart) continue
            if (busyStart - cursor >= MinUsefulFreeMinutes) {
                windows += FriendFreeWindow(day.friend.name, cursor, busyStart)
            }
            cursor = maxOf(cursor, busyEnd)
        }
        if (endMinutes - cursor >= MinUsefulFreeMinutes) {
            windows += FriendFreeWindow(day.friend.name, cursor, endMinutes)
        }
    }
    return windows
}

/**
 * "Aarav and Meera" / "Aarav, Meera and Karan" / "Aarav". Oxford-comma style so
 * a long list of friends still reads as a sentence.
 */
fun friendNameList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** Joins a friend list into a clause: "Aarav and Meera are", "Aarav is". */
fun friendVerb(names: List<String>): String = if (names.size == 1) "is" else "are"

/**
 * Sentence(s) for the friend note under one of the student's own classes.
 *
 * Friends free for the WHOLE class keep the grouped sentence WITHOUT a time
 * range — the lecture card right above the note already says the slot, so
 * “free at this time slot 1:30 PM - 2:30 PM” would just parrot it. Friends
 * free for only PART of the class do get the time, because their free
 * stretch is a different, shorter window inside the class (a friend free
 * for one hour of a two-hour lecture needs “free at 11:30 AM - 12:30 PM” to
 * be actionable) — they are grouped by the exact free window they share, so
 * two friends free over the same stretch read as one clause with “and” —
 * “Raman and Prince are free at 11:30 AM - 12:30 PM” — instead of one clause
 * per friend. Different windows still get their own clause, in time order.
 *
 * [timeLabel] renders minutes-since-midnight for display.
 */
fun friendAvailabilitySentences(
    windows: List<FriendFreeWindow>,
    startMinutes: Int,
    endMinutes: Int,
    timeLabel: (Int) -> String
): List<String> {
    val fullNames = windows
        .filter { it.startMinutes <= startMinutes && it.endMinutes >= endMinutes }
        .map { it.name }
        .distinct()
    val sentences = mutableListOf<String>()
    if (fullNames.isNotEmpty()) {
        sentences += "${friendNameList(fullNames)} ${friendVerb(fullNames)} free at this time slot"
    }
    windows.filter { it.name !in fullNames }
        .groupBy { it.startMinutes to it.endMinutes }
        .entries
        .sortedBy { it.key.first }
        .forEach { (window, same) ->
            val names = same.map { it.name }.distinct()
            sentences += "${friendNameList(names)} ${friendVerb(names)} free at " +
                "${timeLabel(window.first)} - ${timeLabel(window.second)}"
        }
    return sentences
}

/**
 * A friend's earliest class that starts before the student's first lecture of the day.
 * Used on the Today/Tomorrow timeline to surface "Vishal has a lecture at 8:30 AM"
 * above the student's own rail.
 */
data class FriendEarlySlot(
    val name: String,
    /** Canonical type label: lecture / tutorial / lab. */
    val typeLabel: String,
    val startMinutes: Int
)

/**
 * For each friend with timetable data, pick their earliest lecture that starts
 * strictly before [beforeMinutes]. Sorted by start time, then name.
 */
fun List<FriendDay>.earlySlotsBefore(beforeMinutes: Int): List<FriendEarlySlot> =
    filter { it.hasData }
        .mapNotNull { day ->
            val first = day.lectures
                .filter { it.startMinutes < beforeMinutes }
                .minByOrNull { it.startMinutes }
                ?: return@mapNotNull null
            val typeLabel = when (first.lectureType?.trim()?.uppercase()) {
                "TUTORIAL" -> "tutorial"
                "PRACTICAL" -> "lab"
                else -> "lecture"
            }
            FriendEarlySlot(day.friend.name, typeLabel, first.startMinutes)
        }
        .sortedWith(compareBy({ it.startMinutes }, { it.name }))

/**
 * A friend's latest class that still runs after the student's last lecture of
 * the day ends. The mirror of [FriendEarlySlot]: it surfaces below the
 * Today/Tomorrow timeline as "Vishal has a lecture until 5:30 PM" once the
 * student's own day is over.
 */
data class FriendLateSlot(
    val name: String,
    /** Canonical type label: lecture / tutorial / lab. */
    val typeLabel: String,
    /** When that class ends — the time the bottom note displays. */
    val endMinutes: Int
)

/**
 * For each friend with timetable data, pick their latest-ending lecture that
 * ends strictly after [afterMinutes] (touching endpoints do not count, so a
 * class ending exactly when the student's day ends is not "after"). Sorted by
 * end time, then name.
 */
fun List<FriendDay>.lateSlotsAfter(afterMinutes: Int): List<FriendLateSlot> =
    filter { it.hasData }
        .mapNotNull { day ->
            val last = day.lectures
                .filter { it.endMinutes > afterMinutes }
                .maxByOrNull { it.endMinutes }
                ?: return@mapNotNull null
            val typeLabel = when (last.lectureType?.trim()?.uppercase()) {
                "TUTORIAL" -> "tutorial"
                "PRACTICAL" -> "lab"
                else -> "lecture"
            }
            FriendLateSlot(day.friend.name, typeLabel, last.endMinutes)
        }
        .sortedWith(compareBy({ it.endMinutes }, { it.name }))

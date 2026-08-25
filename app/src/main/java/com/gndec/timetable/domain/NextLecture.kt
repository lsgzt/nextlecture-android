package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity

object NextLectureEngine {

    data class TimedLecture(val lecture: LectureEntity, val daysAhead: Int)

    sealed class Status {
        /** A lecture is happening right now. */
        data class HappeningNow(val lecture: LectureEntity, val endsInMinutes: Int) : Status()
        /** Next lecture today (may start in a while — a free gap). */
        data class Next(val lecture: LectureEntity, val startsInMinutes: Int) : Status()
        /** Nothing left today; next lecture is on a later day. */
        data class DoneForToday(val next: TimedLecture?) : Status()
        /** No lectures at all today; next lecture is on a later day (if any). */
        data class FreeDay(val next: TimedLecture?) : Status()
        /** No timetable data at all. */
        object NoData : Status()
    }

    fun compute(
        lectures: List<LectureEntity>,
        todayDow: Int,   // 1=Monday .. 7=Sunday
        nowMinutes: Int  // minutes since midnight
    ): Status {
        if (lectures.isEmpty()) return Status.NoData
        val todays = lectures.filter { it.dayOfWeek == todayDow }.sortedBy { it.startMinutes }

        val current = todays.firstOrNull { it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes }
        if (current != null) {
            return Status.HappeningNow(current, current.endMinutes - nowMinutes)
        }
        val nextToday = todays.firstOrNull { it.startMinutes > nowMinutes }
        if (nextToday != null) {
            return Status.Next(nextToday, nextToday.startMinutes - nowMinutes)
        }
        // Today is over (or empty): search the coming week for the next lecture.
        val later = findNextAfter(lectures, todayDow, nowMinutes)
        return if (todays.isEmpty()) Status.FreeDay(later) else Status.DoneForToday(later)
    }

    /** Next lecture strictly after (todayDow, nowMinutes), wrapping around the week. */
    fun findNextAfter(
        lectures: List<LectureEntity>,
        todayDow: Int,
        nowMinutes: Int
    ): TimedLecture? {
        for (offset in 1..7) {
            val dow = ((todayDow - 1 + offset) % 7) + 1
            val candidates = lectures.filter { it.dayOfWeek == dow }
                .sortedBy { it.startMinutes }
            if (candidates.isNotEmpty()) return TimedLecture(candidates.first(), offset)
        }
        return null
    }

    /** Explicit free-gap detection: minutes of free time before the next lecture today. */
    fun freeGapMinutes(
        lectures: List<LectureEntity>,
        todayDow: Int,
        nowMinutes: Int
    ): Int? {
        val todays = lectures.filter { it.dayOfWeek == todayDow }.sortedBy { it.startMinutes }
        if (todays.any { it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes }) return null
        val next = todays.firstOrNull { it.startMinutes > nowMinutes } ?: return null
        return next.startMinutes - nowMinutes
    }
}

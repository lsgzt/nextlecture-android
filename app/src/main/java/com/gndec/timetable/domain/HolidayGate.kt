package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Holiday-aware timetable gate.
 *
 * The college publishes an official list of holidays (see [HolidayManager]). On a
 * FULL holiday no class runs, so every surface that would otherwise show today's
 * schedule shows the holiday notice instead, and reminders for that date are
 * neither scheduled nor allowed to fire. Half-day holidays deliberately do NOT
 * trigger the gate: classes still run, only the day is shortened.
 *
 * "Not a holiday?" is the escape hatch for a wrong entry: the student can ask for
 * the timetable anyway for that one day (day-scoped preference, closable again),
 * because the list is fetched ahead of time and can drift from reality.
 */
object HolidayGate {

    /**
     * How far ahead the "next lecture after the holiday" search runs — the same
     * horizon [AlarmScheduler] uses when arming reminders.
     */
    private const val HORIZON_DAYS = 14

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether a feed category means a full, college-wide day off. */
    fun isFullHolidayCategory(category: String): Boolean {
        val value = category.trim()
        if (value.isEmpty()) return false
        if (value.contains("half", ignoreCase = true)) return false
        return value.contains("holiday", ignoreCase = true)
    }

    /** Every full holiday on [isoDate] (yyyy-MM-dd). Several can share one date. */
    fun fullHolidaysOn(holidays: List<Holiday>, isoDate: String): List<Holiday> =
        holidays.filter { it.date == isoDate && isFullHolidayCategory(it.category) }

    /** Whether reminders for [isoDate] must stay silent. */
    fun isFullHolidayOn(holidays: List<Holiday>, isoDate: String): Boolean =
        fullHolidaysOn(holidays, isoDate).isNotEmpty()

    /** "Baisakhi and Birthday Dr. B.R. Ambedkar" for a date carrying several holidays. */
    fun displayNames(holidays: List<Holiday>): String =
        holidays.map { it.name.trim() }.filter { it.isNotBlank() }.joinToString(" and ")

    /** Decodes the cached holiday JSON kept in settings; blank or broken input yields nothing. */
    fun decode(jsonValue: String): List<Holiday> = runCatching {
        if (jsonValue.isBlank()) emptyList() else json.decodeFromString<List<Holiday>>(jsonValue)
    }.getOrDefault(emptyList())

    /**
     * The first lecture after the holiday (or run of holidays) containing [todayIso].
     * Days that are themselves full holidays are skipped, so a two-day break lands on
     * the first teaching day after it. Null when nothing is scheduled in the horizon.
     */
    fun nextLectureAfter(
        lectures: List<LectureEntity>,
        holidays: List<Holiday>,
        todayIso: String
    ): NextLectureEngine.TimedLecture? {
        val today = runCatching { LocalDate.parse(todayIso) }.getOrNull() ?: return null
        for (offset in 1..HORIZON_DAYS) {
            val date = today.plusDays(offset.toLong())
            if (isFullHolidayOn(holidays, date.toString())) continue
            val candidates = lectures
                .filter { it.dayOfWeek == date.dayOfWeek.value }
                .sortedBy { it.startMinutes }
            if (candidates.isNotEmpty()) return NextLectureEngine.TimedLecture(candidates.first(), offset)
        }
        return null
    }
}

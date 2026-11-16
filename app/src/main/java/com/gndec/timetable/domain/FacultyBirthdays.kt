package com.gndec.timetable.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One teacher's upcoming birthday: the directory record plus the derived
 * calendar facts the birthday section shows.
 *
 * [daysUntil] is 0 when the birthday is today — today must sort first, and
 * the hero badge says "TODAY" rather than "in 0 days".
 */
data class FacultyBirthday(
    val record: FacultyRecord,
    val birthDate: LocalDate,
    /** Next occurrence of the birthday, never before [today] (today counts). */
    val nextDate: LocalDate,
    /** Whole days from today until [nextDate]; 0 = today. */
    val daysUntil: Int,
    /** Age in whole years the teacher reaches on [nextDate]. */
    val turningAge: Int
)

/**
 * Birthday ordering and projection over the bundled faculty directory.
 *
 * Pure date math over already-parsed [FacultyRecord]s — no I/O, so the UI can
 * run it inside `remember` on the composition without a dispatcher, and the
 * same logic is unit-testable without Android.
 */
object FacultyBirthdays {

    /**
     * Upcoming birthdays across [records], soonest first, capped at [limit].
     *
     * - Records without a parseable `dateOfBirth` are skipped (the directory
     *   publishes none for roughly a fifth of the teachers, and timetable-
     *   discovered extras carry none at all).
     * - The college files a few teachers under two departments with identical
     *   DOB and name (Dr. Jasbir Singh Saini sits in both Computer Center and
     *   CSE); one card per person is enough, so identical name+DOB collapses
     *   to the first record.
     * - Same-day birthdays are ordered alphabetically so the list is stable.
     */
    fun upcoming(records: List<FacultyRecord>, today: LocalDate, limit: Int = 7): List<FacultyBirthday> {
        val seen = HashSet<Pair<String, LocalDate>>()
        return records.asSequence()
            .mapNotNull { record -> parse(record.dateOfBirth)?.let { record to it } }
            .filterNot { (record, birth) -> !seen.add(record.name.trim().uppercase() to birth) }
            .map { (record, birth) ->
                val next = nextOccurrence(birth, today)
                FacultyBirthday(
                    record = record,
                    birthDate = birth,
                    nextDate = next,
                    // Period.days is only the leftover day component (so
                    // Nov 16 → Mar 16 incorrectly becomes 0); use total days.
                    daysUntil = ChronoUnit.DAYS.between(today, next).toInt(),
                    turningAge = next.year - birth.year
                )
            }
            .sortedWith(compareBy({ it.daysUntil }, { it.record.name.trim().lowercase() }))
            .take(limit)
            .toList()
    }

    /**
     * Next occurrence of the annual birthday, today included: a birthday
     * today is *today*, one already passed this year rolls to next year.
     *
     * A Feb-29 birthday in a non-leap year lands on Feb-28 — the directory
     * currently has none, but a future refresh could add one and a crash on
     * the faculty screen would be a poor way to find out.
     */
    fun nextOccurrence(birth: LocalDate, today: LocalDate): LocalDate {
        fun inYear(year: Int): LocalDate =
            runCatching { birth.withYear(year) }
                .getOrDefault(LocalDate.of(year, 2, 28)) // Feb 29 in a non-leap year
        // Compare only the annual birthday month/day. The birth year must never
        // influence whether this year's birthday is still upcoming.
        var next = inYear(today.year)
        if (next.monthValue < today.monthValue ||
            (next.monthValue == today.monthValue && next.dayOfMonth < today.dayOfMonth)
        ) {
            next = inYear(today.year + 1)
        }
        return next
    }

    /** Parses the directory's ISO `YYYY-MM-DD`; null for blank or malformed. */
    fun parse(raw: String): LocalDate? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * Short birthday wish shown on the hero card. "Random" but deterministic per
 * teacher per day: the pick is seeded by name + today, so the line does not
 * reshuffle on every recomposition or navigation, yet it changes daily.
 */
fun birthdayWishFor(name: String, today: LocalDate): String {
    val wishes = listOf(
        "Wishing you a day as wonderful as your lectures!",
        "May this year bring you success, health and happiness!",
        "Have an amazing day — you deserve it!",
        "May the year ahead be your best one yet!",
        "Sending warm wishes on your special day!",
        "May your day be filled with joy and celebration!",
        "Here's to another great year of inspiring students!",
        "Wishing you good health and happiness always!",
        "May all your wishes come true today!",
        "Have a fantastic birthday and a brilliant year ahead!"
    )
    val seed = name.trim().uppercase().hashCode() * 31 + today.toEpochDay().hashCode()
    return wishes[Math.floorMod(seed, wishes.size)]
}

package com.gndec.timetable.domain

import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.data.prefs.SettingsManager
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** What the exam-mode screens render: the date sheet that is on, read for this student. */
data class ExamState(
    val announcement: Announcement,
    val schedule: ExamSchedule,
    /** Group whose exams are shown. Null until the student picks one. */
    val group: String?,
    /** The exams this student sits, earliest first. Empty while the group is still to be picked. */
    val exams: List<Exam>,
    /** The sheet is split into groups and the student has not yet said which one is theirs. */
    val needsGroupPick: Boolean
) {
    /** At least one exam line was read, so the dedicated exam UI can stand in for the notice card. */
    val readable: Boolean get() = !schedule.isEmpty

    /** The sheet was read only in part: the exams on some exam-shaped lines are missing from [exams]. */
    val partial: Boolean get() = schedule.unreadable > 0
}

/**
 * The exam-mode gate. An announcement with `exam_mode: "on"` switches the whole
 * app over for every student it reaches: reminders and the timetable pause, and
 * its body is the date sheet the home page shows.
 *
 * It is read from the device's cached feed rather than the network, so alarms,
 * receivers and the UI all agree, online or not.
 */
object ExamGate {

    /** Spellings of `exam_mode` that mean on. Anything else, including a missing key, is off. */
    private val ON = setOf("on", "true", "1", "yes", "enabled", "active")

    fun isOn(raw: String?): Boolean = (raw?.trim()?.lowercase(Locale.ROOT) ?: "") in ON

    /** The newest announcement that switched exam mode on, or null when exams are not on. */
    fun activeAnnouncement(announcements: List<Announcement>): Announcement? =
        announcements.filter { isOn(it.examMode) }.maxByOrNull { it.publishedAt }

    /** Same, read from the cached feed — the form every non-UI path uses. */
    fun active(settings: AppSettings): Announcement? = activeAnnouncement(decodeAnnouncements(settings.announcementsJson))

    fun isActive(settings: AppSettings): Boolean = active(settings) != null

    /** The date sheet that is on, read for this student — null while exams are off. */
    fun resolve(settings: AppSettings, today: LocalDate): ExamState? =
        active(settings)?.let { resolve(it, settings, today) }

    /**
     * Reads the date sheet of [announcement] for this student, or null when the
     * announcement does not switch exam mode on. [today] is only the fallback for
     * dating the sheet when it carries no publish time.
     */
    fun resolve(announcement: Announcement, settings: AppSettings, today: LocalDate): ExamState? {
        if (!isOn(announcement.examMode)) return null
        val schedule = ExamScheduleParser.parse(announcement.message, publishedOn(announcement.publishedAt, today))
        val groups = schedule.groups
        val group = when {
            groups.size <= 1 -> groups.firstOrNull()
            // A pick belongs to the sheet it was made for: each newly published date sheet asks again.
            settings.examGroupAnnouncementId != announcement.id -> null
            else -> groups.firstOrNull { sameGroup(it, settings.examGroupName) }
        }
        val forGroup = schedule.exams.filter { it.group.isEmpty() || it.group == group }
        return ExamState(
            announcement = announcement,
            schedule = schedule,
            group = group,
            exams = applySections(forGroup, settings),
            needsGroupPick = groups.size > 1 && group == null
        )
    }

    /** Remembers the group the student sits with, for the date sheet that is currently published. */
    suspend fun rememberGroup(settings: SettingsManager, group: String) {
        val announcement = active(settings.flow.first()) ?: return
        settings.setExamGroupChoice(announcement.id, group)
    }

    /**
     * Keeps the exams this student sits. A line that names sections belongs to
     * those sections only; a line that names none belongs to the whole group. A
     * student whom none of a day's lines name still sees that day's lines: a sheet
     * that lists fewer sections than actually sit the exam must not hide it.
     */
    internal fun applySections(exams: List<Exam>, settings: AppSettings): List<Exam> {
        val mine = studentTokens(settings)
        return exams.groupBy { it.date }.flatMap { (_, thatDay) ->
            val tagged = thatDay.filter { it.sections.isNotEmpty() }
            val forMe = tagged.filter { exam -> exam.sections.any { namesStudent(it, mine) } }
            when {
                tagged.isEmpty() || forMe.isEmpty() -> thatDay
                else -> thatDay.filter { it.sections.isEmpty() || it in forMe }
            }
        }.sortedWith(compareBy({ it.date }, { it.startMinutes }))
    }

    /** "ME A", "ME-A" and "MEA" are one section: lower-case, without spaces, hyphens or underscores. */
    private fun token(raw: String?): String? {
        val code = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(" ", "")?.replace("-", "")?.replace("_", "") ?: return null
        return code.ifEmpty { null }
    }

    private fun studentTokens(settings: AppSettings): Set<String> = listOf(
        settings.branch, settings.studentSection, settings.studentSubsection, settings.studentGroup, settings.group
    ).mapNotNullTo(mutableSetOf()) { token(it) }

    /**
     * Whether a section listed on a line covers this student. Short codes must
     * match exactly ("CE"), longer ones also match a subgroup of that section
     * ("ME A" covers ME A1).
     */
    private fun namesStudent(listed: String, mine: Set<String>): Boolean {
        val code = token(listed) ?: return false
        return mine.any { it == code || (code.length >= 3 && it.startsWith(code)) }
    }

    private fun sameGroup(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** The local date an announcement was published on; [fallback] when its time is missing or unreadable. */
    private fun publishedOn(value: String, fallback: LocalDate): LocalDate {
        val zone = ZoneId.systemDefault()
        return runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate() }
            .recoverCatching { Instant.parse(value).atZone(zone).toLocalDate() }
            .getOrDefault(fallback)
    }
}

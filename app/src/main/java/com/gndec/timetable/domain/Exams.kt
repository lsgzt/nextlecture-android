package com.gndec.timetable.domain

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** One line of a published date sheet, resolved to a real date and 24-hour times. */
data class Exam(
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
    val subject: String,
    /** Sections named in brackets at the end of the line, as written. Empty means the whole group sits it. */
    val sections: List<String> = emptyList(),
    /** Group heading the line was published under. Empty when the schedule has no headings. */
    val group: String = ""
)

/** Everything the parser could read out of an exam-mode announcement. */
data class ExamSchedule(
    /** Group headings that have exams under them, in publication order. */
    val groups: List<String>,
    val exams: List<Exam>,
    /**
     * Lines shaped like an exam line that did not read: a misspelt month, an
     * impossible date, a missing end time. Their exams are missing from [exams],
     * so the screens say how many lines were lost.
     */
    val unreadable: Int = 0
) {
    val isEmpty: Boolean get() = exams.isEmpty()
}

/**
 * Reads the date sheet published in an exam-mode announcement.
 *
 * The body is one exam per line, optionally grouped under headings:
 *
 *     **Chemistry Group:**
 *
 *     23 Nov — Mathematics-I — 9:15–10:45 AM
 *     28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B, CE A, CE B, RAI)
 *
 *     **Physics Group:**
 *     26 Nov — Physics — 12:45–2:15 PM
 *
 * A line ending in a colon names the group every line after it belongs to, until
 * the next heading. Anything else that does not look like an exam (greetings,
 * instructions, blank lines) is ignored, and a line with a typo is skipped on its
 * own rather than taking the rest of the sheet down with it. README.md, section
 * "Exam mode", is the published reference for this syntax.
 */
object ExamScheduleParser {

    /** Every dash a keyboard produces between the date, the subject and the times. */
    private const val DASH_CHARS = "—–‒―‐‑-"
    private const val DASH = "[$DASH_CHARS]"
    private const val BULLET = """(?:[-*•·]\s+)?"""
    private const val ORDINAL = """(?:st|nd|rd|th)?"""
    private const val YEAR = """(?:[\s,.\-]*(?<year>\d{4}))?"""

    /** "23 Nov — …", "23rd November, 2026 — …" */
    private val DAY_FIRST = Regex(
        """^$BULLET(?<day>\d{1,2})$ORDINAL[\s.-]*(?<month>[A-Za-z]{3,9})\.?$YEAR\s*$DASH\s*(?<rest>.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** "Nov 23 — …", tolerated for sheets written month first. */
    private val MONTH_FIRST = Regex(
        """^$BULLET(?<month>[A-Za-z]{3,9})\.?[\s.-]*(?<day>\d{1,2})$ORDINAL$YEAR\s*$DASH\s*(?<rest>.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** "Mathematics-I — 23 Nov — …", tolerated for sheets written subject first. */
    private val SUBJECT_FIRST = Regex(
        """^$BULLET(?<subject>.+?)\s*$DASH\s*(?<day>\d{1,2})$ORDINAL[\s.-]*(?<month>[A-Za-z]{3,9})\.?$YEAR\s*$DASH\s*(?<rest>.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** "9:15–10:45 AM (ME A, ME B)", with an optional remark after a further dash. */
    private val TIME_RANGE = Regex(
        """^\s*(?<sh>\d{1,2})(?:[.:](?<sm>\d{2}))?\s*(?<smer>[ap]\.?[m]\.?)?\s*(?:$DASH|to)\s*""" +
            """(?<eh>\d{1,2})(?:[.:](?<em>\d{2}))?\s*(?<emer>[ap]\.?[m]\.?)?""" +
            """\s*(?:\((?<sections>[^)]*)\))?\s*(?:$DASH.*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val SECTION_SPLIT = Regex("""\s*(?:[,;|]|&|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val ONLY_SUFFIX = Regex("""\s+only$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    /** Markdown heading hashes: "## Chemistry Group:". */
    private val HEADING_MARKS = Regex("""^\s*#{1,6}\s+""")
    /**
     * Markdown emphasis written with underscores. An underscore only opens or closes
     * emphasis beside a space or a punctuation mark, which leaves the underscore in a
     * subject such as "Data_Structures" alone.
     */
    private val UNDERSCORE_EMPHASIS = Regex("""(?<![\p{L}\p{N}])_+(.+?)_+(?![\p{L}\p{N}])""")

    /**
     * Parses [message]. [referenceDate] is the day the sheet was published: dates
     * carry no year, so each one goes to the year that keeps it nearest to that day.
     */
    fun parse(message: String, referenceDate: LocalDate): ExamSchedule {
        var group = ""
        val exams = mutableListOf<Exam>()
        var unreadable = 0
        message.lineSequence().forEach { raw ->
            val line = plain(raw)
            if (line.isEmpty()) return@forEach
            heading(line)?.let {
                group = it
                return@forEach
            }
            val exam = parseLine(line, referenceDate)
            if (exam != null) {
                exams += exam.copy(group = group)
            } else if (looksLikeExam(line)) {
                // An exam the student would not see in the cards: count it, so the
                // screens can say the sheet is incomplete and keep the notice.
                unreadable++
            }
        }
        // A heading with nothing under it is a label such as "Instructions:", not a group.
        val groups = exams.map { it.group }.filter { it.isNotEmpty() }.distinct()
        return ExamSchedule(groups, exams.sortedWith(compareBy({ it.date }, { it.startMinutes })), unreadable)
    }

    /**
     * Whether a line that did not read as an exam has the shape of one, and so is
     * a slip in the sheet rather than prose: it opens with a date, or it ends
     * with a time range behind a dash.
     */
    private fun looksLikeExam(line: String): Boolean {
        if (DAY_FIRST.containsMatchIn(line) || MONTH_FIRST.containsMatchIn(line) || SUBJECT_FIRST.containsMatchIn(line)) {
            return true
        }
        return line.indices.filter { line[it] in DASH_CHARS }.any { dash ->
            line.substring(0, dash).isNotBlank() && TIME_RANGE.matches(line.substring(dash + 1).trimStart())
        }
    }

    /**
     * One line without the Markdown a notice is written in, so the rules read plain
     * text: heading hashes, asterisks and underscore emphasis go. The notice card
     * still renders the Markdown from the original text; this is only for reading it.
     */
    private fun plain(raw: String): String = UNDERSCORE_EMPHASIS
        .replace(HEADING_MARKS.replace(raw, ""), "$1")
        .replace("*", "")
        .replace('：', ':')
        .trim()

    /** "**Chemistry Group:**" → "Chemistry Group"; null for every line that is not a bare label. */
    private fun heading(line: String): String? {
        if (!line.endsWith(':')) return null
        return line.removeSuffix(":").trim().ifEmpty { null }
    }

    /** A single exam line, or null when the line is not one or its date or times make no sense. */
    private fun parseLine(line: String, reference: LocalDate): Exam? {
        val (date, body) = splitDate(line, reference) ?: return null
        // The time range is the last dash-separated piece that reads as one, so a
        // subject may itself contain dashes ("Mathematics-I").
        for (dash in body.indices.filter { body[it] in DASH_CHARS }.sortedDescending()) {
            val time = TIME_RANGE.find(body.substring(dash + 1).trimStart()) ?: continue
            val subject = clean(body.substring(0, dash).trim('*', '_'))
            if (subject.isEmpty()) continue
            val startMeridiem = time.groups["smer"]?.value?.first()?.lowercaseChar()
            val endMeridiem = time.groups["emer"]?.value?.first()?.lowercaseChar()
            // A side written without AM/PM takes the other side's: "11:00 AM–12:30" ends at 12:30 PM.
            val start = minute(time, "sh", "sm", startMeridiem ?: endMeridiem) ?: continue
            val end = minute(time, "eh", "em", endMeridiem ?: startMeridiem) ?: continue
            // A 12-hour reading that ends before it starts wraps over noon instead:
            // "11:00 AM–12:30" ends at 12:30, "11–12 PM" starts at 11, "9:15 PM–10:45 AM" ends at 22:45.
            // …and the moved side has to land before the other one, or the line is dropped:
            // "9:15 PM–8:00 AM" cannot be read any way.
            val range = when {
                start < end -> start to end
                startMeridiem == null && endMeridiem != null && start - 12 * 60 >= 0 && start - 12 * 60 < end -> (start - 12 * 60) to end
                startMeridiem != null && end + 12 * 60 <= 24 * 60 && start < end + 12 * 60 -> start to (end + 12 * 60)
                else -> null
            } ?: continue
            return Exam(
                date = date,
                startMinutes = range.first,
                endMinutes = range.second,
                subject = subject,
                sections = sections(time.groups["sections"]?.value)
            )
        }
        return null
    }

    /** Splits an exam line into its date and everything after it. */
    private fun splitDate(line: String, reference: LocalDate): Pair<LocalDate, String>? {
        DAY_FIRST.find(line)?.let { m ->
            val date = resolveDate(m, reference) ?: return null
            return date to m.part("rest")
        }
        MONTH_FIRST.find(line)?.let { m ->
            val date = resolveDate(m, reference) ?: return null
            return date to m.part("rest")
        }
        SUBJECT_FIRST.find(line)?.let { m ->
            val date = resolveDate(m, reference) ?: return null
            return date to "${m.part("subject")} — ${m.part("rest")}"
        }
        return null
    }

    private fun resolveDate(m: MatchResult, reference: LocalDate): LocalDate? {
        val day = m.part("day").toInt()
        val month = month(m.part("month")) ?: return null
        val year = m.part("year")
        if (year.isNotEmpty()) {
            return runCatching { LocalDate.of(year.toInt(), month, day) }.getOrNull()
        }
        return ((reference.year - 1)..(reference.year + 1))
            .mapNotNull { runCatching { LocalDate.of(it, month, day) }.getOrNull() }
            .minByOrNull { abs(ChronoUnit.DAYS.between(it, reference)) }
    }

    private fun MatchResult.part(name: String): String = groups[name]?.value ?: ""

    private fun month(text: String): Month? {
        val key = text.lowercase(Locale.ROOT).let { if (it == "sept") "sep" else it }
        return Month.entries.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(key) }
    }

    /** Minutes since midnight for one side of the range, or null when the clock reading is impossible. */
    private fun minute(time: MatchResult, hourGroup: String, minuteGroup: String, meridiem: Char?): Int? {
        val hour = time.groups[hourGroup]!!.value.toInt()
        val minute = time.groups[minuteGroup]?.value?.toInt() ?: 0
        if (minute !in 0..59) return null
        val h = when (meridiem) {
            'a' -> if (hour == 12) 0 else hour
            'p' -> if (hour == 12) 12 else hour + 12
            null -> hour
            else -> return null
        }
        return if (h in 0..23) h * 60 + minute else null
    }

    /** "(ME A, ME B, CE A, CE B)" → [ME A, ME B, CE A, CE B] */
    private fun sections(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SECTION_SPLIT)
            .map { clean(it.replace(ONLY_SUFFIX, "").trim('*', '(', ')', '.')) }
            .filter { it.isNotEmpty() }
    }

    private fun clean(text: String): String = SPACES.replace(text.trim(), " ")
}

/**
 * Decides which exam the home page puts in the big card and which ones are
 * listed under it, from the student's exams and the current time.
 */
object ExamTimeline {

    enum class Phase { ONGOING, TODAY, UPCOMING }

    data class Featured(
        val exam: Exam,
        val phase: Phase,
        /** 0 while the exam is today, otherwise the number of days until it. */
        val daysAhead: Int,
        /** Minutes until the exam ends while it is ONGOING, otherwise until it starts. */
        val minutes: Int
    )

    data class View(
        /** The exam in the big card: the one running now, else the next one today, else the next one. */
        val featured: Featured?,
        /** Every exam still to finish, the featured one excluded, earliest first. */
        val upcoming: List<Exam>,
        val finished: Int,
        val total: Int
    ) {
        /** The schedule had exams and all of them are over. */
        val allOver: Boolean get() = featured == null && total > 0
    }

    fun compute(exams: List<Exam>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): View {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val nowMinutes = now.hour * 60 + now.minute
        val ordered = exams.sortedWith(compareBy({ it.date }, { it.startMinutes }))
        val over = { exam: Exam ->
            exam.date.isBefore(today) || (exam.date == today && exam.endMinutes <= nowMinutes)
        }
        val ongoing = ordered.firstOrNull {
            it.date == today && it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes
        }
        val laterToday = ordered.firstOrNull { it.date == today && it.startMinutes > nowMinutes }
        val featured = when {
            ongoing != null -> Featured(ongoing, Phase.ONGOING, 0, ongoing.endMinutes - nowMinutes)
            laterToday != null -> Featured(laterToday, Phase.TODAY, 0, laterToday.startMinutes - nowMinutes)
            else -> ordered.firstOrNull { it.date.isAfter(today) }?.let { next ->
                val days = ChronoUnit.DAYS.between(today, next.date).toInt()
                Featured(next, Phase.UPCOMING, days, days * 24 * 60 + next.startMinutes - nowMinutes)
            }
        }
        return View(
            featured = featured,
            upcoming = ordered.filterNot { over(it) || it == featured?.exam },
            finished = ordered.count(over),
            total = ordered.size
        )
    }
}

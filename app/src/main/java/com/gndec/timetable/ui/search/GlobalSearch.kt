package com.gndec.timetable.ui.search

import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.domain.FacultyDirectoryManager
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.domain.VacantRoomsManager
import com.gndec.timetable.parse.AcRooms
import com.gndec.timetable.parse.GlobalRoomData
import com.gndec.timetable.parse.RoomNameNormalizer
import com.gndec.timetable.ui.attendance.SubjectAttendance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Which part of the app a result belongs to. Order here is the order shown. */
enum class SearchGroup(val title: String) {
    DAY("That day"),
    YOU("You"),
    FACULTY("Faculty"),
    ACTION("Go to"),
    ROOM("Rooms"),
    LECTURE("Lectures"),
    NOTICE("Notices"),
    HOLIDAY("Holidays"),
    ATTENDANCE("Attendance")
}

/** What tapping a result does. */
sealed interface SearchAction {
    data object None : SearchAction
    data class Navigate(val route: String) : SearchAction
    data class OpenUrl(val url: String) : SearchAction
    data class OpenLecture(val lecture: LectureEntity, val date: LocalDate) : SearchAction
    data class OpenFaculty(val profileId: String) : SearchAction
    data class OpenNotice(val noticeId: String) : SearchAction
}

data class SearchResult(
    val id: String,
    val group: SearchGroup,
    val title: String,
    val subtitle: String,
    val score: Int,
    val action: SearchAction = SearchAction.None,
    /** Faculty results only: the teacher's photo from the college website,
     * rendered as the row's circular avatar. */
    val photoUrl: String = ""
)

/**
 * Everything the engine is allowed to look at. Built once per query from data the
 * app already holds, which is what lets [search] stay a pure function with no IO.
 */
data class SearchSnapshot(
    val settings: AppSettings,
    val lectures: List<LectureEntity>,
    val notices: List<ErpNotice>,
    val holidays: List<Holiday>,
    val attendance: List<SubjectAttendance>,
    val rooms: GlobalRoomData?,
    val faculty: List<FacultyRecord> = emptyList(),
    val today: LocalDate,
    val nowMinutes: Int
)

/** Placeholder shown while the room timetables are still downloading. */
const val ROOMS_LOADING_HINT = "__rooms_loading__"

// ---------------------------------------------------------------------------
// Scoring
// ---------------------------------------------------------------------------

private const val SCORE_EXACT = 100
private const val SCORE_PREFIX = 80
private const val SCORE_WORD = 60
private const val SCORE_SUBSTRING = 40
private const val SCORE_SUBSEQUENCE = 20

/** Lowercase, strip everything that is not a letter or digit. */
private fun key(raw: String?): String =
    raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/**
 * How well [query] matches [text]. Exact > prefix > word-boundary > substring >
 * loose subsequence, so "chem" ranks a subject called "Chemistry" above one that
 * merely contains those letters somewhere.
 */
private fun score(query: String, text: String?): Int {
    val q = key(query)
    val t = key(text)
    if (q.isBlank() || t.isBlank()) return 0
    if (t == q) return SCORE_EXACT
    if (t.startsWith(q)) return SCORE_PREFIX
    if (t.contains(q)) return SCORE_SUBSTRING
    if (isSubsequence(q, t)) return SCORE_SUBSEQUENCE
    return 0
}

/** Same ladder, but a whole-word hit inside the text scores above a bare substring. */
private fun scoreWords(query: String, text: String?): Int {
    val base = score(query, text)
    if (base < SCORE_SUBSTRING) return base
    val q = key(query)
    val t = key(text)
    val words = (text ?: "").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    return if (words.any { it.startsWith(q) }) SCORE_WORD else base
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    var i = 0
    for (c in haystack) {
        if (i < needle.length && needle[i] == c) i++
    }
    return i == needle.length
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

private val DayAliases = mapOf(
    "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
    "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
    "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
    "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
    "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
    "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY
)

private val MonthAliases = mapOf(
    "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
    "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
    "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9,
    "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12
)

private val DayMonthPattern = Regex("""^(\d{1,2})[\s\-/.]+([a-z]+)[\s\-/.]*(\d{4})?$""")
private val MonthDayPattern = Regex("""^([a-z]+)[\s\-/.]+(\d{1,2})[\s\-/.]*(\d{4})?$""")
private val IsoPattern = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
private val SlashPattern = Regex("""^(\d{1,2})[/.](\d{1,2})[/.](\d{4})$""")
/** "25-12-2026" — a dash is not a month name, so it needs its own numeric rule. */
private val DashPattern = Regex("""^(\d{1,2})-(\d{1,2})-(\d{4})$""")

/**
 * Reads a date out of a query: relative words, weekdays, and the written and
 * numeric forms people actually type. Returns null when the query is not a date,
 * which lets the caller fall through to the other intents.
 */
fun parseQueryDate(query: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val raw = query.trim().lowercase()
    val cleaned = raw.removePrefix("on ").removePrefix("for ").trim()
    if (cleaned.isEmpty()) return null

    when (key(cleaned)) {
        "today", "tonight" -> return today
        "tomorrow", "tmrw" -> return today.plusDays(1)
        "yesterday" -> return today.minusDays(1)
        "dayaftertomorrow" -> return today.plusDays(2)
    }

    // "next monday" and a bare "monday" both mean the coming occurrence — which,
    // on a Monday, is today.
    val body = key(cleaned.removePrefix("next ").removePrefix("upcoming ").trim())
    DayAliases[body]?.let { weekday -> return nextWeekday(today, weekday) }

    IsoPattern.matchEntire(cleaned)?.let { m ->
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }
    SlashPattern.matchEntire(cleaned)?.let { m ->
        val a = m.groupValues[1].toIntOrNull() ?: return@let null
        val b = m.groupValues[2].toIntOrNull() ?: return@let null
        val year = m.groupValues[3].toIntOrNull() ?: today.year
        // d/m/yyyy is the convention in India; fall back to m/d when the day is impossible.
        return runCatching { LocalDate.of(year, b, a) }.getOrNull()
            ?: runCatching { LocalDate.of(year, a, b) }.getOrNull()
    }
    DashPattern.matchEntire(cleaned)?.let { m ->
        val a = m.groupValues[1].toIntOrNull() ?: return@let null
        val b = m.groupValues[2].toIntOrNull() ?: return@let null
        val year = m.groupValues[3].toIntOrNull() ?: today.year
        return runCatching { LocalDate.of(year, b, a) }.getOrNull()
            ?: runCatching { LocalDate.of(year, a, b) }.getOrNull()
    }
    DayMonthPattern.matchEntire(cleaned)?.let { m ->
        val day = m.groupValues[1].toIntOrNull() ?: return@let null
        val month = MonthAliases[m.groupValues[2]] ?: return@let null
        val year = m.groupValues[3].toIntOrNull() ?: inferYear(month, day, today)
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    MonthDayPattern.matchEntire(cleaned)?.let { m ->
        val month = MonthAliases[m.groupValues[1]] ?: return@let null
        val day = m.groupValues[2].toIntOrNull() ?: return@let null
        val year = m.groupValues[3].toIntOrNull() ?: inferYear(month, day, today)
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    // A bare number is deliberately not read as a date. "25" is far more likely
    // to be half a roll number or a room than the 25th, and guessing here would
    // push a bogus day to the top of every numeric query. Dates are only
    // recognised from unambiguous forms: a weekday, a month name, or separators.
    return null
}

/** The next [weekday] on or after today, so "monday" on a Monday means today. */
private fun nextWeekday(today: LocalDate, weekday: DayOfWeek): LocalDate {
    val delta = (weekday.value - today.dayOfWeek.value + 7) % 7
    return today.plusDays(delta.toLong())
}

/**
 * Pick the year for a month-and-day query with no year written. "Nearest" beats
 * "next" — from 4 October, "25 sep" must resolve to last month rather than
 * eleven months out, and "1 jan" to next January rather than nine months back.
 * A future date wins an exact tie.
 */
private fun inferYear(month: Int, day: Int, today: LocalDate): Int {
    val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        ?: return today.year
    val nextYear = if (today.year >= 2100) return today.year else today.year + 1
    val following = runCatching { LocalDate.of(nextYear, month, day) }.getOrNull()
        ?: return today.year
    val daysToNext = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, following))
    val daysSinceThis = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, thisYear))
    return if (daysToNext < daysSinceThis) nextYear else today.year
}

// ---------------------------------------------------------------------------
// Identity fields
// ---------------------------------------------------------------------------

private data class IdentityField(
    val label: String,
    val aliases: List<String>,
    val value: (AppSettings) -> String,
    val icon: SearchGroup
)

/**
 * College email is derived the same way as Profile: firstName + CRN + @gndec.ac.in.
 * Not stored separately — always computed from the saved profile.
 */
private fun collegeEmailOf(settings: AppSettings): String {
    val first = settings.studentName.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        ?: return ""
    val crn = settings.rollNumber.trim()
    if (crn.isBlank()) return ""
    return "${first.lowercase()}${crn.lowercase()}@gndec.ac.in"
}

/**
 * Profile fields exposed to search. CRN (class roll) and registration number are
 * separate values — never label one as the other.
 *
 * - [AppSettings.rollNumber] = CRN / class roll from the student directory
 * - [AppSettings.registrationNumber] = university registration number
 */
private val IdentityFields = listOf(
    IdentityField(
        "CRN (class roll number)",
        listOf(
            "crn", "classroll", "classrollno", "classrollnumber", "classrollnum",
            "roll", "rollno", "rollnumber", "myroll", "mycrn", "classno", "rno"
        ),
        { it.rollNumber }, SearchGroup.YOU
    ),
    IdentityField(
        "Registration number",
        listOf(
            "registration", "registrationno", "registrationnumber", "regno", "reg",
            "enrollment", "enrollmentno", "enrolment", "enrolmentno", "myregistration"
        ),
        { it.registrationNumber }, SearchGroup.YOU
    ),
    IdentityField(
        "Name",
        listOf("name", "myname", "fullname", "studentname", "nameofstudent", "stuname"),
        { it.studentName }, SearchGroup.YOU
    ),
    IdentityField(
        "College email",
        listOf(
            "email", "mail", "collegeemail", "collegemail", "gndecmail", "gndecemail",
            "studentemail", "studentmail", "myemail", "mymail", "uniemail", "universityemail"
        ),
        { collegeEmailOf(it) }, SearchGroup.YOU
    ),
    IdentityField(
        "Branch",
        listOf("branch", "mybranch", "department", "dept", "stream"),
        { it.branch }, SearchGroup.YOU
    ),
    IdentityField(
        "Academic year",
        listOf("year", "academicyear", "myyear", "studyyear", "currentyear"),
        { if (it.academicYear in 1..4) "${it.academicYear}" else "" }, SearchGroup.YOU
    ),
    IdentityField(
        "Section",
        listOf("section", "mysection", "classsection"),
        { it.studentSection }, SearchGroup.YOU
    ),
    IdentityField(
        "Subsection",
        listOf("subsection", "mysubsection", "batch"),
        { it.studentSubsection }, SearchGroup.YOU
    ),
    IdentityField(
        "Timetable group",
        listOf("group", "mygroup", "timetablegroup", "division"),
        { it.studentGroup.ifBlank { it.group.orEmpty() } }, SearchGroup.YOU
    ),
    IdentityField(
        "Father's name",
        listOf("father", "fathersname", "fathername", "dad", "papa"),
        { it.fatherName }, SearchGroup.YOU
    ),
    IdentityField(
        "Mother's name",
        listOf("mother", "mothersname", "mothername", "mom", "mummy"),
        { it.motherName }, SearchGroup.YOU
    ),
    IdentityField(
        "Mentor",
        listOf(
            "mentor", "mentorname", "mymentor", "facultyadvisor", "facultymentor",
            "guide", "tutor", "proctor"
        ),
        { it.mentorName }, SearchGroup.YOU
    ),
    IdentityField(
        "Mentor mobile",
        listOf("mentormobile", "mentorphone", "mentornumber", "mentorcontact", "guidephone"),
        { it.mentorMobile }, SearchGroup.YOU
    ),
    IdentityField(
        "Mentor venue",
        listOf("mentorvenue", "mentorroom", "mentoroffice", "mentorcabin", "guidevenue"),
        { it.mentorVenue }, SearchGroup.YOU
    ),
    IdentityField(
        "Class coordinator",
        listOf(
            "coordinator", "classcoordinator", "classcoordinatorname", "mycoordinator",
            "cc", "classincharge", "incharge"
        ),
        { it.classCoordinator }, SearchGroup.YOU
    )
)

// ---------------------------------------------------------------------------
// Navigation actions
// ---------------------------------------------------------------------------

private data class QuickAction(
    val id: String,
    val title: String,
    val route: String,
    val keywords: List<String>,
    val hint: String = ""
)

/** Route names are MainActivity's `composable(...)` strings, reused verbatim. */
private val QuickActions = listOf(
    QuickAction("vacant", "Find vacant rooms", "vacant_rooms", listOf("vacantroom", "vacantrooms", "freeclass", "freeclasses", "freeslot", "freeslots", "emptylab", "roomavailability", "isroomfree", "roomstatus", "whichroom")),
    QuickAction(
        "downloads",
        "Downloads",
        "downloads",
        listOf("download", "downloads", "forms", "form", "buspass", "gatepass", "vehiclepass", "bonafide", "documents", "document", "files", "pdf", "idcard", "nodues"),
        hint = "Bus pass, vehicle pass and every official form"
    ),
    QuickAction(
        "lostfound",
        "Lost & Found",
        "lost_found",
        listOf("lost", "found", "lostandfound", "lostitem", "founditem", "lostfound", "missing", "mislaid"),
        hint = "Campus lost & found noticeboard"
    ),
    QuickAction(
        "faculty",
        "Faculty members",
        "faculty_members",
        listOf(
            "faculty", "facultymembers", "teachers", "teacherlist", "staff", "professors",
            "lecturers", "directory", "facultydirectory", "findteacher", "searchteacher"
        ),
        hint = "Browse the college faculty directory"
    ),
    QuickAction("attendance", "My attendance", "attendance", listOf("attendance", "myattendance", "present", "absent", "percentage", "howmanypercent", "amirequired"), hint="Marks and overall percentage"),
    QuickAction("profile", "My profile", "profile", listOf("profile", "myprofile", "studentprofile", "mydetails", "personalinfo", "myinfo")),
    QuickAction("notice", "Notices", "notice", listOf("notice", "notices", "erpnotice", "officialnotice", "circular")),
    QuickAction("syllabus", "Syllabus assistant", "syllabus", listOf("syllabus", "syllabi", "syllabusassistant", "notes", "curriculum", "askassistant")),
    QuickAction("today", "Today's timetable", "today", listOf("today", "timetable", "schedule", "classes", "myclasses", "lectures", "timetableview")),
    QuickAction("home", "Home", "home", listOf("home", "dashboard", "gohome")),
    QuickAction("settings", "Settings", "settings", listOf("setting", "settings", "preferences", "theme", "darkmode", "lightmode", "appon"))
)

private val SyllabusAction = QuickAction(
    "syllabus-ask",
    "Ask the syllabus assistant",
    "syllabus",
    listOf("syllabusask", "asksyllabus", "syllabusquery")
)

// ---------------------------------------------------------------------------
// The search itself
// ---------------------------------------------------------------------------

/**
 * The whole feature in one pure call. Every branch is additive — a query that is
 * also a date still falls through to the text search — so nothing is lost when a
 * query matches more than one intent.
 *
 * [departmentFilter] narrows the FACULTY group to one department (the picker in
 * the search suggestions). With an empty query a picked department turns the
 * suggestions into a straight directory listing of that department's teachers,
 * so the picker works for browsing as well as for filtering.
 */
fun search(query: String, snapshot: SearchSnapshot, departmentFilter: String? = null): List<SearchResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        if (departmentFilter != null && snapshot.faculty.isNotEmpty()) {
            return departmentBrowse(departmentFilter, snapshot)
        }
        return suggestions(snapshot)
    }

    val out = mutableListOf<SearchResult>()
    out += dateResults(trimmed, snapshot)
    out += identityResults(trimmed, snapshot)
    out += facultyResults(trimmed, snapshot, departmentFilter)
    out += roomResults(trimmed, snapshot)
    out += actionResults(trimmed)
    out += syllabusResults(trimmed)
    out += textResults(trimmed, snapshot)
    return rank(out)
}

/**
 * Stable ordering: group first, then score, then title so ties never jump around.
 *
 * Loose subsequence hits are dropped whenever something matched properly. Three
 * letters scattered through a subject name will "contain" almost anything —
 * `crn` occurs in order inside "Computer Networks" — so those results are only
 * worth showing when nothing better was found at all.
 */
private fun rank(results: List<SearchResult>): List<SearchResult> {
    val kept = results
        .filter { it.score > 0 }
        .distinctBy { it.id }
    val strongest = kept.maxOfOrNull { it.score } ?: 0
    return kept
        .filter { strongest < SCORE_WORD || it.score >= SCORE_SUBSTRING }
        .sortedWith(
            compareBy<SearchResult> { it.group.ordinal }
                .thenByDescending { it.score }
                .thenBy { it.title }
        )
}

private fun suggestions(snapshot: SearchSnapshot): List<SearchResult> {
    val out = mutableListOf<SearchResult>()
    QuickActions.forEach { action ->
        out += SearchResult(
            id = "action-${action.id}",
            group = SearchGroup.ACTION,
            title = action.title,
            subtitle = action.hint.ifBlank { "Open ${action.title.lowercase()}" },
            score = SCORE_PREFIX - action.id.length,
            action = SearchAction.Navigate(action.route)
        )
    }
    snapshot.settings.rollNumber.takeIf { it.isNotBlank() }?.let {
        out += SearchResult(
            id = "you-crn",
            group = SearchGroup.YOU,
            title = "Your CRN",
            subtitle = it,
            score = SCORE_PREFIX,
            action = SearchAction.Navigate("profile")
        )
    }
    snapshot.settings.registrationNumber.takeIf { it.isNotBlank() }?.let {
        out += SearchResult(
            id = "you-registration",
            group = SearchGroup.YOU,
            title = "Your registration number",
            subtitle = it,
            score = SCORE_PREFIX - 1,
            action = SearchAction.Navigate("profile")
        )
    }
    // College mail instead of name — knowing your own name is not useful here;
    // name remains searchable via identity aliases ("name", "my name", …).
    collegeEmailOf(snapshot.settings).takeIf { it.isNotBlank() }?.let {
        out += SearchResult(
            id = "you-email",
            group = SearchGroup.YOU,
            title = "Your mail",
            subtitle = it,
            score = SCORE_PREFIX - 2,
            action = SearchAction.Navigate("profile")
        )
    }
    return rank(out)
}

// --- identity -------------------------------------------------------------

private fun identityResults(query: String, snapshot: SearchSnapshot): List<SearchResult> {
    val k = key(query)
    if (k.isBlank()) return emptyList()
    val out = mutableListOf<SearchResult>()

    IdentityFields.forEach { field ->
        val value = field.value(snapshot.settings).trim()
        if (value.isBlank()) return@forEach
        // Asked for by field name, e.g. "crn", "college mail", "class coordinator".
        if (identityAliasHit(k, field.aliases)) {
            out += SearchResult(
                id = "you-${field.label}",
                group = SearchGroup.YOU,
                title = field.label,
                subtitle = value,
                score = SCORE_EXACT,
                action = SearchAction.Navigate("profile")
            )
        }
    }
    if (out.isNotEmpty()) return out

    // Not a known alias — maybe the student typed a value they are looking for.
    IdentityFields.forEach { field ->
        val value = field.value(snapshot.settings).trim()
        if (value.isBlank()) return@forEach
        // CRNs can be short; allow value lookup from length 3 upward.
        if (value.length < 3 && key(value).length < 3) return@forEach
        val hit = score(k, value)
        if (hit >= SCORE_PREFIX) {
            out += SearchResult(
                id = "you-${field.label}",
                group = SearchGroup.YOU,
                title = field.label,
                subtitle = value,
                score = hit - 5,
                action = SearchAction.Navigate("profile")
            )
        }
    }
    return out
}

/** True when the normalised query names this profile field. */
private fun identityAliasHit(queryKey: String, aliases: List<String>): Boolean {
    if (queryKey.isBlank()) return false
    aliases.forEach { alias ->
        val a = key(alias)
        if (a.isBlank()) return@forEach
        if (queryKey == a) return true
        // "class coordinator", "college mail", "student name", "reg no"…
        if (queryKey.length >= 3 && (a.startsWith(queryKey) || queryKey.startsWith(a))) return true
        if (queryKey.length >= 4 && a.contains(queryKey)) return true
    }
    return false
}

// --- dates ----------------------------------------------------------------

private fun dateResults(query: String, snapshot: SearchSnapshot): List<SearchResult> {
    val date = parseQueryDate(query, snapshot.today) ?: return emptyList()
    val out = mutableListOf<SearchResult>()
    val pretty = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))

    val holidaysOn = snapshot.holidays.filter { it.date == date.toString() }
    val summary = when {
        holidaysOn.isNotEmpty() -> holidaysOn.joinToString(", ") { it.name }
        date.isBefore(snapshot.today) -> "Past day · no holiday"
        else -> "No holiday"
    }
    out += SearchResult(
        id = "day-${date}",
        group = SearchGroup.DAY,
        title = pretty,
        subtitle = summary,
        score = SCORE_EXACT,
        action = SearchAction.Navigate("today")
    )

    val dayOfWeek = date.dayOfWeek.value
    snapshot.lectures
        .filter { it.dayOfWeek == dayOfWeek }
        .sortedBy { it.startMinutes }
        .forEach { lecture ->
            out += SearchResult(
                id = "day-lecture-${lecture.id}",
                group = SearchGroup.LECTURE,
                title = lecture.subject ?: "Lecture",
                subtitle = "${formatSlot(lecture.startMinutes)}–${formatSlot(lecture.endMinutes)} · ${lecture.venue.orEmpty()}".trim(' ', '·'),
                score = SCORE_PREFIX - 10,
                action = SearchAction.OpenLecture(lecture, date)
            )
        }

    snapshot.notices
        .filter { it.publishedDate.take(10) == date.toString() }
        .forEach { notice ->
            out += SearchResult(
                id = "day-notice-${notice.id}",
                group = SearchGroup.NOTICE,
                title = notice.title,
                subtitle = notice.displayDate,
                score = SCORE_PREFIX - 10,
                action = notice.actionOrNavigate()
            )
        }
    return out
}

private fun formatSlot(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val suffix = if (hour >= 12) "PM" else "AM"
    val display = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(display, minute, suffix)
}

private fun ErpNotice.actionOrNavigate(): SearchAction =
    SearchAction.OpenNotice(id)

// --- faculty ---------------------------------------------------------------

/**
 * Faculty search over the bundled college directory. Every teacher is
 * searchable by name — including teachers whose weekly timetable is not
 * published; their detail page answers with contact details plus the honest
 * "timetable not available" note. The department in the subtitle
 * disambiguates same-name teachers (three Mandeep Kaurs across departments).
 *
 * Names go through BOTH the shared score ladder and a per-word Damerau fuzzy
 * pass, because the directory and the timetable files disagree on spellings
 * ("KULVINDER" vs "Kulwinder") — a user typing either finds the teacher.
 *
 * [departmentFilter] keeps only the picked department's teachers — that is
 * the department picker in the search suggestions.
 */
private fun facultyResults(query: String, snapshot: SearchSnapshot, departmentFilter: String? = null): List<SearchResult> {
    if (snapshot.faculty.isEmpty()) return emptyList()
    val hits = facultyMatched(query, snapshot)
        .filter { departmentFilter == null || it.first.department == departmentFilter }
        .map { (faculty, best) ->
            val subtitle = listOfNotNull(
                faculty.designation.takeIf { it.isNotBlank() },
                faculty.department.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { "Faculty" }
            SearchResult(
                id = "faculty-${faculty.profileId}",
                group = SearchGroup.FACULTY,
                title = faculty.name,
                subtitle = subtitle,
                score = if (best.second) best.first else best.first - 10,
                action = SearchAction.OpenFaculty(faculty.profileId),
                photoUrl = faculty.imageUrl
            )
        }
    return hits.sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.title }).take(8)
}

/**
 * The department picker's options for this query: every department the user can
 * still narrow down to. With a query it lists only departments that actually
 * have a matching teacher; with an empty query it lists the whole directory so
 * the picker doubles as a department browser straight from the suggestions.
 */
fun facultyDepartments(query: String, snapshot: SearchSnapshot): List<String> {
    if (snapshot.faculty.isEmpty()) return emptyList()
    val trimmed = query.trim()
    val pool = if (trimmed.isEmpty()) {
        snapshot.faculty
    } else {
        facultyMatched(trimmed, snapshot).map { it.first }
    }
    val departments = pool.map { it.department }.filter { it.isNotBlank() }.distinct()
    return FacultyDirectoryManager.orderedDepartments(departments)
}

/**
 * Empty-query department browsing: picking a department with nothing typed
 * lists that department's whole directory — every teacher, alphabetical —
 * instead of the generic quick actions. The selected chip itself says which
 * department is listed, so the subtitle carries designation and email.
 */
private fun departmentBrowse(department: String, snapshot: SearchSnapshot): List<SearchResult> =
    snapshot.faculty
        .filter { it.department == department }
        .sortedBy { it.name }
        .map { faculty ->
            val subtitle = listOfNotNull(
                faculty.designation.takeIf { it.isNotBlank() },
                faculty.emails.firstOrNull()
            ).joinToString(" · ").ifBlank { "Faculty" }
            SearchResult(
                id = "faculty-${faculty.profileId}",
                group = SearchGroup.FACULTY,
                title = faculty.name,
                subtitle = subtitle,
                score = SCORE_PREFIX,
                action = SearchAction.OpenFaculty(faculty.profileId),
                photoUrl = faculty.imageUrl
            )
        }

/**
 * Directory records that match [query] with their best score and whether that
 * best score came from the name itself (name hits outrank department/designation
 * hits, which rank below everything with the same ladder score).
 */
private fun facultyMatched(query: String, snapshot: SearchSnapshot): List<Pair<FacultyRecord, Pair<Int, Boolean>>> {
    return snapshot.faculty.mapNotNull { faculty ->
        val name = maxOf(scoreWords(query, faculty.name), fuzzyNameScore(query, faculty.name))
        val dept = score(query, faculty.department)
        val designation = score(query, faculty.designation)
        if (name >= dept && name >= designation) {
            if (name <= 0) return@mapNotNull null
            faculty to (name to true)
        } else {
            faculty to (maxOf(dept, designation) to false)
        }
    }
}

/**
 * Every query word must fuzzy-match (or be a prefix of) some name word; the
 * result ranks just under a clean word-boundary hit. Spelling drift between
 * the college directory and the department files is common, so typing
 * "kulwinder" still surfaces "DR. KULVINDER SINGH MANN".
 */
private fun fuzzyNameScore(query: String, name: String?): Int {
    val queryWords = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    if (queryWords.isEmpty()) return 0
    val nameWords = name.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    if (nameWords.isEmpty()) return 0
    val matched = queryWords.count { qw ->
        nameWords.any { nw ->
            qw == nw || nw.startsWith(qw) || (
                qw.length >= 4 && nw.length >= 4 &&
                    com.gndec.timetable.parse.FacultyNameMatcher.damerau(qw, nw).toDouble() / maxOf(qw.length, nw.length) <= 0.25
                )
        }
    }
    if (matched != queryWords.size) return 0
    return SCORE_WORD - 8
}

// --- rooms ----------------------------------------------------------------

/**
 * Answers "is this room free right now" inline. The day and slot are chosen the
 * same way the vacant-rooms screen chooses its opening chips, so the two can
 * never disagree, and a `null` cell is reported as "no class listed" rather than
 * as free — a room no department publishes must not read as vacant. AC rooms
 * carry the snowflake badge on their title, and a snowflake query lists them all.
 */
private fun roomResults(query: String, snapshot: SearchSnapshot): List<SearchResult> {
    val rooms = snapshot.rooms
    if (rooms == null) {
        val looksRoomy = AcRooms.hasSnowflake(query) ||
            RoomNameNormalizer.isRoomLike(key(query)) ||
            query.trim().split(Regex("\\s+")).any { RoomNameNormalizer.isRoomLike(key(it)) }
        if (!looksRoomy) return emptyList()
        return listOf(
            SearchResult(
                id = ROOMS_LOADING_HINT,
                group = SearchGroup.ROOM,
                title = "Loading room timetables…",
                subtitle = "Checking availability",
                score = SCORE_PREFIX,
                action = SearchAction.Navigate("vacant_rooms")
            )
        )
    }

    val day = VacantRoomsManager.defaultDayIndex(snapshot.today)
    val slot = VacantRoomsManager.defaultSlotIndex(rooms.slotStarts)
    val nowOnThisDay = VacantRoomsManager.isToday(day, snapshot.today)
    val slotIsNow = nowOnThisDay &&
        VacantRoomsManager.isCurrentSlot(rooms.slotStarts.getOrElse(slot) { 0 }, snapshot.nowMinutes)

    // A bare snowflake asks for the AC rooms as a list — all of them, not the
    // usual top-8 cut, so every air-conditioned room is shown.
    val acOnly = AcRooms.isSnowflakeOnly(query)

    return rooms.rooms
        .mapNotNull { room ->
            val hit = roomNameScore(query, room.name)
            if (hit <= 0) return@mapNotNull null
            val cell = room.occupancy.getOrNull(day)?.getOrNull(slot)
            val status = when {
                cell == null -> "No class listed · coverage unknown"
                !slotIsNow -> "Free at this slot"
                cell.isFree -> "Free right now"
                else -> "Occupied · ${cell.subject.orEmpty().ifBlank { "a class" }}"
            }
            SearchResult(
                id = "room-${room.key}",
                group = SearchGroup.ROOM,
                title = AcRooms.badge(room.name, room.key),
                subtitle = status,
                score = hit,
                action = SearchAction.Navigate("vacant_rooms")
            )
        }
        .sortedByDescending { it.score }
        .let { results -> if (acOnly) results else results.take(8) }
}

/**
 * Delegates to the matcher the vacant-rooms screen already uses, then folds that
 * into the shared score ladder so rooms rank alongside everything else.
 */
private fun roomNameScore(query: String, roomName: String): Int {
    if (query.isBlank() || roomName.isBlank()) return 0
    val matched = RoomNameNormalizer.matches(roomName, query)
    val direct = score(query, roomName)
    val words = scoreWords(query, roomName)
    val best = maxOf(direct, words)
    if (matched && best > 0) return maxOf(best, SCORE_SUBSTRING)
    if (matched) return SCORE_SUBSEQUENCE
    return 0
}

// --- actions --------------------------------------------------------------

private fun actionResults(query: String): List<SearchResult> {
    val k = key(query)
    if (k.isBlank()) return emptyList()
    return QuickActions.mapNotNull { action ->
        val exact = action.keywords.any { it == k }
        val hit = action.keywords.maxOfOrNull { kw -> score(k, kw) } ?: 0
        val chosen = when {
            exact -> SCORE_EXACT
            hit >= SCORE_PREFIX -> SCORE_PREFIX - 5
            else -> return@mapNotNull null
        }
        SearchResult(
            id = "action-${action.id}",
            group = SearchGroup.ACTION,
            title = action.title,
            subtitle = action.hint.ifBlank { "Open ${action.title.lowercase()}" },
            score = chosen,
            action = SearchAction.Navigate(action.route)
        )
    }
}

/**
 * Syllabus content is not indexed for search — `SyllabusManager` keeps its index
 * private and builds it during a chat request — so this offers the assistant as
 * a destination instead of pretending to search inside it.
 */
private fun syllabusResults(query: String): List<SearchResult> {
    val q = key(query)
    if (q.length < 8) return emptyList()
    val hit = SyllabusAction.keywords.maxOfOrNull { score(q, it) } ?: 0
    if (hit < SCORE_SUBSEQUENCE) return emptyList()
    return listOf(
        SearchResult(
            id = "action-${SyllabusAction.id}",
            group = SearchGroup.ACTION,
            title = "Ask the syllabus assistant",
            subtitle = "Search the official syllabus for \"$query\"",
            score = SCORE_SUBSEQUENCE,
            action = SearchAction.Navigate(SyllabusAction.route)
        )
    )
}

// --- free text ------------------------------------------------------------

private fun textResults(query: String, snapshot: SearchSnapshot): List<SearchResult> {
    val out = mutableListOf<SearchResult>()

    snapshot.lectures.forEach { lecture ->
        val subject = scoreWords(query, lecture.subject)
        val teacher = score(query, lecture.teacher)
        val venue = score(query, lecture.venue)
        val best = maxOf(subject, teacher, venue)
        if (best <= 0) return@forEach
        val via = when (best) {
            subject -> lecture.subject ?: "Lecture"
            teacher -> lecture.teacher.orEmpty()
            else -> lecture.venue.orEmpty()
        }
        out += SearchResult(
            id = "lecture-${lecture.id}",
            group = SearchGroup.LECTURE,
            title = lecture.subject ?: "Lecture",
            subtitle = "$via · ${formatSlot(lecture.startMinutes)}",
            score = best,
            action = SearchAction.OpenLecture(lecture, nextDateFor(lecture, snapshot.today))
        )
    }

    snapshot.notices.forEach { notice ->
        val title = scoreWords(query, notice.title)
        val body = score(query, notice.author)
        val source = score(query, notice.source)
        val best = maxOf(title, body, source)
        if (best <= 0) return@forEach
        out += SearchResult(
            id = "notice-${notice.id}",
            group = SearchGroup.NOTICE,
            title = notice.title,
            subtitle = notice.displayDate,
            score = best - 5,
            action = notice.actionOrNavigate()
        )
    }

    snapshot.holidays.forEach { holiday ->
        val name = scoreWords(query, holiday.name)
        if (name <= 0) return@forEach
        out += SearchResult(
            id = "holiday-${holiday.id}",
            group = SearchGroup.HOLIDAY,
            title = holiday.name,
            subtitle = "${holiday.displayDate} · ${holiday.category}",
            score = name - 5,
            action = SearchAction.Navigate("notice")
        )
    }

    snapshot.attendance.forEach { subject ->
        val name = scoreWords(query, subject.subject)
        if (name <= 0) return@forEach
        out += SearchResult(
            id = "attendance-${subject.subject}",
            group = SearchGroup.ATTENDANCE,
            title = subject.subject,
            subtitle = "${subject.percentage?.let { "${"%.1f".format(it)}%" } ?: "—"} · ${subject.present}/${subject.markedTotal} attended",
            score = name - 10,
            action = SearchAction.Navigate("attendance")
        )
    }

    return out
}

/** Best guess at the date a weekly lecture next occurs, for the detail screen. */
private fun nextDateFor(lecture: LectureEntity, today: LocalDate): LocalDate {
    val delta = (lecture.dayOfWeek - today.dayOfWeek.value + 7) % 7
    return today.plusDays(delta.toLong())
}

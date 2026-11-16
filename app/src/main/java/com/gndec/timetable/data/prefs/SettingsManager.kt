package com.gndec.timetable.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.gndec.timetable.domain.Friend
import com.gndec.timetable.net.AiProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val group: String? = null,
    val sourceUrl: String = DEFAULT_SOURCE_URL,
    val backendUrl: String = "",
    val pyqRagBackendUrl: String = DEFAULT_PYQ_RAG_BACKEND_URL,
    val aiEnabled: Boolean = true,
    val aiProvider: String = AiProvider.GEMINI.id,
    /** Model for [aiProvider]. Each provider keeps its own model so switching back restores it. */
    val model: String = AiProvider.GEMINI.defaultModel,
    val themeMode: String = DEFAULT_THEME_MODE, // light | dark | system
    val remind15: Boolean = true,
    val remind30: Boolean = false,
    val remind5: Boolean = false,
    val remindAtStart: Boolean = true,
    val onboardingDone: Boolean = false,
    val notificationPermissionPrompted: Boolean = false,
    /**
     * Outcome of the last POST_NOTIFICATIONS system prompt. Kept separate from
     * [notificationPermissionPrompted] so a denial is recorded as a denial instead of
     * being indistinguishable from a grant. The authoritative live check is still
     * NotificationHelper.notificationsEnabled(); this only records what we asked for
     * and what the user answered, so a permanent denial can be explained to the user.
     */
    val notificationPermissionGranted: Boolean = false,
    val studentName: String = "",
    val rollNumber: String = "",
    val branch: String = "",
    val registrationNumber: String = "",
    val fatherName: String = "",
    val motherName: String = "",
    val mentorName: String = "",
    // Stored under the legacy preference keys so existing profiles remain readable.
    val studentSection: String = "",
    val studentSubsection: String = "",
    val studentGroup: String = "",
    val mentorMobile: String = "",
    val mentorVenue: String = "",
    val classCoordinator: String = "",
    val profileSource: String = "manual",
    /** 0 = unset (legacy profiles), 1..4 = academic year of B.Tech. */
    val academicYear: Int = 0,
    val studentDirectoryBranch: String = "",
    val studentDirectoryJson: String = "",
    val studentDirectoryUpdatedAt: Long = 0L,
    val announcementNotifications: Boolean = true,
    val lastAnnouncementId: String = "",
    val lastAnnouncementTitle: String = "",
    val lastAnnouncementMessage: String = "",
    val lastAnnouncementPublishedAt: String = "",
    val lastAnnouncementType: String = "info",
    val lastAnnouncementLink: String = "",
    val lastAnnouncementBranch: String = "",
    val lastAnnouncementSection: String = "",
    val lastAnnouncementSubsection: String = "",
    val lastAnnouncementTarget: String = "both",
    /** Every announcement currently eligible for Home, newest first, as JSON. */
    val announcementsJson: String = "",
    /** Classmates added locally for the friends-aware timetable, as JSON. */
    val friendsJson: String = "",
    val announcementsUpdatedAt: Long = 0,
    val erpNoticeJson: String = "",
    val erpNoticeUpdatedAt: Long = 0L,
    val holidayJson: String = "",
    val holidayUpdatedAt: Long = 0L,
    /**
     * yyyy-MM-dd of the one day the student chose "Not a holiday?" on — showing the
     * timetable despite a full holiday. Day-scoped so it expires on its own at midnight;
     * blank (or any other date) means the holiday notice is in charge again.
     */
    val holidayTimetableOverrideDay: String = "",
    /**
     * yyyy-MM-dd of the free/weekend day the student chose to follow another weekday's
     * timetable on. Day-scoped like the holiday override — blank means no follow is active.
     */
    val weekendFollowDayIso: String = "",
    /**
     * ISO day-of-week (1=Monday … 7=Sunday) whose lectures to show and remind for on
     * [weekendFollowDayIso]. 0 means unset.
     */
    val weekendFollowDow: Int = 0,
    val lastReleaseMarker: String = "",
    val lastReleaseName: String = "",
    val lastReleaseNotes: String = "",
    val lastReleaseCheckedAt: Long = 0L,
    val lastReleaseNotifiedMarker: String = "",
    val attendanceTarget: Float = 75f,
    /** ZenQuotes quote text shown on the home screen; blank until the first fetch succeeds. */
    val quoteText: String = "",
    val quoteAuthor: String = "",
    /** IST day (yyyy-MM-dd) the cached quote was fetched for — the once-a-day guard. */
    val quoteDayKey: String = ""
) {
    /** The AI backend in use. Unknown values fall back to the default provider. */
    val provider: AiProvider get() = AiProvider.fromId(aiProvider)

    companion object {
        const val DEFAULT_PYQ_RAG_BACKEND_URL = "https://nextlecture-android.vercel.app"
        /** A fresh install follows the OS light/dark setting until the user picks one. */
        const val DEFAULT_THEME_MODE = "system"
        const val DEFAULT_HOLIDAY_BACKEND_URL = "https://nextlecture-android.vercel.app"
        const val DEFAULT_SOURCE_URL =
            "https://appsc.gndec.ac.in/sites/default/files/2026-09/06_09_2026%20ON%20WEBSITE_subgroups_days_horizontal.html"
    }
}

class SettingsManager(private val context: Context) {

    private object K {
        val GROUP = stringPreferencesKey("group")
        val SOURCE_URL = stringPreferencesKey("source_url")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val PYQ_RAG_BACKEND_URL = stringPreferencesKey("pyq_rag_backend_url")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        /** Legacy single-model key. It stays Gemini's slot so existing profiles keep working. */
        val MODEL = stringPreferencesKey("model")
        val MODEL_GEMINI = stringPreferencesKey("model_gemini")
        val MODEL_GROQ = stringPreferencesKey("model_groq")
        val MODEL_OPENROUTER = stringPreferencesKey("model_openrouter")
        val THEME = stringPreferencesKey("theme_mode")
        val REMIND15 = booleanPreferencesKey("remind_15")
        val REMIND30 = booleanPreferencesKey("remind_30")
        val REMIND5 = booleanPreferencesKey("remind_5")
        val REMIND_START = booleanPreferencesKey("remind_start")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val NOTIFICATION_PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")
        val NOTIFICATION_PERMISSION_GRANTED = booleanPreferencesKey("notification_permission_granted")
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val ROLL_NUMBER = stringPreferencesKey("roll_number")
        val BRANCH = stringPreferencesKey("branch")
        val REGISTRATION_NUMBER = stringPreferencesKey("registration_number")
        val FATHER_NAME = stringPreferencesKey("father_name")
        val MOTHER_NAME = stringPreferencesKey("mother_name")
        val MENTOR_NAME = stringPreferencesKey("mentor_name")
        // Keep these legacy key names to migrate profiles created by older builds.
        val STUDENT_SECTION = stringPreferencesKey("temporary_section")
        val STUDENT_SUBSECTION = stringPreferencesKey("temporary_subsection")
        val STUDENT_GROUP = stringPreferencesKey("student_group")
        val MENTOR_MOBILE = stringPreferencesKey("mentor_mobile")
        val MENTOR_VENUE = stringPreferencesKey("mentor_venue")
        val CLASS_COORDINATOR = stringPreferencesKey("class_coordinator")
        val PROFILE_SOURCE = stringPreferencesKey("profile_source")
        val ACADEMIC_YEAR = intPreferencesKey("academic_year")
        val STUDENT_DIRECTORY_BRANCH = stringPreferencesKey("student_directory_branch")
        val STUDENT_DIRECTORY_JSON = stringPreferencesKey("student_directory_json")
        val STUDENT_DIRECTORY_UPDATED_AT = longPreferencesKey("student_directory_updated_at")
        val ANNOUNCEMENT_NOTIFICATIONS = booleanPreferencesKey("announcement_notifications")
        val LAST_ANNOUNCEMENT_ID = stringPreferencesKey("last_announcement_id")
        val LAST_ANNOUNCEMENT_TITLE = stringPreferencesKey("last_announcement_title")
        val LAST_ANNOUNCEMENT_MESSAGE = stringPreferencesKey("last_announcement_message")
        val LAST_ANNOUNCEMENT_PUBLISHED_AT = stringPreferencesKey("last_announcement_published_at")
        val LAST_ANNOUNCEMENT_TYPE = stringPreferencesKey("last_announcement_type")
        val LAST_ANNOUNCEMENT_LINK = stringPreferencesKey("last_announcement_link")
        val LAST_ANNOUNCEMENT_BRANCH = stringPreferencesKey("last_announcement_branch")
        val LAST_ANNOUNCEMENT_SECTION = stringPreferencesKey("last_announcement_section")
        val LAST_ANNOUNCEMENT_SUBSECTION = stringPreferencesKey("last_announcement_subsection")
        val LAST_ANNOUNCEMENT_TARGET = stringPreferencesKey("last_announcement_target")
        val FRIENDS_JSON = stringPreferencesKey("friends_json")
        val ANNOUNCEMENTS_JSON = stringPreferencesKey("announcements_json")
        val ANNOUNCEMENTS_UPDATED_AT = longPreferencesKey("announcements_updated_at")
        val ERP_NOTICE_JSON = stringPreferencesKey("erp_notice_json")
        val ERP_NOTICE_UPDATED_AT = longPreferencesKey("erp_notice_updated_at")
        val HOLIDAY_JSON = stringPreferencesKey("holiday_json")
        val HOLIDAY_UPDATED_AT = longPreferencesKey("holiday_updated_at")
        val HOLIDAY_TIMETABLE_OVERRIDE_DAY = stringPreferencesKey("holiday_timetable_override_day")
        val WEEKEND_FOLLOW_DAY_ISO = stringPreferencesKey("weekend_follow_day_iso")
        val WEEKEND_FOLLOW_DOW = intPreferencesKey("weekend_follow_dow")
        val LAST_RELEASE_MARKER = stringPreferencesKey("last_release_marker")
        val LAST_RELEASE_NAME = stringPreferencesKey("last_release_name")
        val LAST_RELEASE_NOTES = stringPreferencesKey("last_release_notes")
        val LAST_RELEASE_CHECKED_AT = longPreferencesKey("last_release_checked_at")
        val LAST_RELEASE_NOTIFIED_MARKER = stringPreferencesKey("last_release_notified_marker")
        val ATTENDANCE_TARGET = floatPreferencesKey("attendance_target")
        val QUOTE_TEXT = stringPreferencesKey("quote_text")
        val QUOTE_AUTHOR = stringPreferencesKey("quote_author")
        val QUOTE_DAY_KEY = stringPreferencesKey("quote_day_key")
        val LAST_INSTALLED_VERSION_CODE = intPreferencesKey("last_installed_version_code")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val provider = AiProvider.fromId(p[K.AI_PROVIDER])
        AppSettings(
            group = p[K.GROUP],
            sourceUrl = p[K.SOURCE_URL] ?: AppSettings.DEFAULT_SOURCE_URL,
            backendUrl = p[K.BACKEND_URL] ?: "",
            pyqRagBackendUrl = p[K.PYQ_RAG_BACKEND_URL] ?: AppSettings.DEFAULT_PYQ_RAG_BACKEND_URL,
            aiEnabled = p[K.AI_ENABLED] ?: true,
            aiProvider = provider.id,
            model = storedModel(provider, p),
            themeMode = p[K.THEME] ?: AppSettings.DEFAULT_THEME_MODE,
            remind15 = p[K.REMIND15] ?: true,
            remind30 = p[K.REMIND30] ?: false,
            remind5 = p[K.REMIND5] ?: false,
            remindAtStart = p[K.REMIND_START] ?: true,
            onboardingDone = p[K.ONBOARDED] ?: false,
            notificationPermissionPrompted = p[K.NOTIFICATION_PERMISSION_PROMPTED] ?: false,
            notificationPermissionGranted = p[K.NOTIFICATION_PERMISSION_GRANTED] ?: false,
            studentName = p[K.STUDENT_NAME] ?: "",
            rollNumber = p[K.ROLL_NUMBER] ?: "",
            branch = p[K.BRANCH] ?: "",
            registrationNumber = p[K.REGISTRATION_NUMBER] ?: "",
            fatherName = p[K.FATHER_NAME] ?: "",
            motherName = p[K.MOTHER_NAME] ?: "",
            mentorName = p[K.MENTOR_NAME] ?: "",
            studentSection = p[K.STUDENT_SECTION] ?: "",
            studentSubsection = p[K.STUDENT_SUBSECTION] ?: "",
            studentGroup = p[K.STUDENT_GROUP] ?: p[K.STUDENT_SUBSECTION] ?: "",
            mentorMobile = p[K.MENTOR_MOBILE] ?: "",
            mentorVenue = p[K.MENTOR_VENUE] ?: "",
            classCoordinator = p[K.CLASS_COORDINATOR] ?: "",
            profileSource = p[K.PROFILE_SOURCE] ?: "manual",
            academicYear = (p[K.ACADEMIC_YEAR] ?: 0).coerceIn(0, 4),
            studentDirectoryBranch = p[K.STUDENT_DIRECTORY_BRANCH] ?: "",
            studentDirectoryJson = p[K.STUDENT_DIRECTORY_JSON] ?: "",
            studentDirectoryUpdatedAt = p[K.STUDENT_DIRECTORY_UPDATED_AT] ?: 0L,
            announcementNotifications = p[K.ANNOUNCEMENT_NOTIFICATIONS] ?: true,
            lastAnnouncementId = p[K.LAST_ANNOUNCEMENT_ID] ?: "",
            lastAnnouncementTitle = p[K.LAST_ANNOUNCEMENT_TITLE] ?: "",
            lastAnnouncementMessage = p[K.LAST_ANNOUNCEMENT_MESSAGE] ?: "",
            lastAnnouncementPublishedAt = p[K.LAST_ANNOUNCEMENT_PUBLISHED_AT] ?: "",
            lastAnnouncementType = p[K.LAST_ANNOUNCEMENT_TYPE] ?: "info",
            lastAnnouncementLink = p[K.LAST_ANNOUNCEMENT_LINK] ?: "",
            lastAnnouncementBranch = p[K.LAST_ANNOUNCEMENT_BRANCH] ?: "",
            lastAnnouncementSection = p[K.LAST_ANNOUNCEMENT_SECTION] ?: "",
            lastAnnouncementSubsection = p[K.LAST_ANNOUNCEMENT_SUBSECTION] ?: "",
            lastAnnouncementTarget = p[K.LAST_ANNOUNCEMENT_TARGET] ?: "both",
            friendsJson = p[K.FRIENDS_JSON] ?: "",
            announcementsJson = p[K.ANNOUNCEMENTS_JSON] ?: "",
            announcementsUpdatedAt = p[K.ANNOUNCEMENTS_UPDATED_AT] ?: 0L,
            erpNoticeJson = p[K.ERP_NOTICE_JSON] ?: "",
            erpNoticeUpdatedAt = p[K.ERP_NOTICE_UPDATED_AT] ?: 0L,
            holidayJson = p[K.HOLIDAY_JSON] ?: "",
            holidayUpdatedAt = p[K.HOLIDAY_UPDATED_AT] ?: 0L,
            holidayTimetableOverrideDay = p[K.HOLIDAY_TIMETABLE_OVERRIDE_DAY] ?: "",
            weekendFollowDayIso = p[K.WEEKEND_FOLLOW_DAY_ISO] ?: "",
            weekendFollowDow = (p[K.WEEKEND_FOLLOW_DOW] ?: 0).coerceIn(0, 7),
            lastReleaseMarker = p[K.LAST_RELEASE_MARKER] ?: "",
            lastReleaseName = p[K.LAST_RELEASE_NAME] ?: "",
            lastReleaseNotes = p[K.LAST_RELEASE_NOTES] ?: "",
            lastReleaseCheckedAt = p[K.LAST_RELEASE_CHECKED_AT] ?: 0L,
            lastReleaseNotifiedMarker = p[K.LAST_RELEASE_NOTIFIED_MARKER] ?: "",
            attendanceTarget = (p[K.ATTENDANCE_TARGET] ?: 75f).coerceIn(50f, 100f),
            quoteText = p[K.QUOTE_TEXT] ?: "",
            quoteAuthor = p[K.QUOTE_AUTHOR] ?: "",
            quoteDayKey = p[K.QUOTE_DAY_KEY] ?: ""
        )
    }

    suspend fun setGroup(g: String) = context.dataStore.edit { it[K.GROUP] = g }
    suspend fun setSourceUrl(u: String) = context.dataStore.edit { it[K.SOURCE_URL] = u }
    suspend fun setBackendUrl(u: String) = context.dataStore.edit { it[K.BACKEND_URL] = u.trim() }
    suspend fun setPyqRagBackendUrl(u: String) = context.dataStore.edit { it[K.PYQ_RAG_BACKEND_URL] = u.trim().ifBlank { AppSettings.DEFAULT_PYQ_RAG_BACKEND_URL } }
    suspend fun setAiEnabled(b: Boolean) = context.dataStore.edit { it[K.AI_ENABLED] = b }

    /** Switching provider keeps that provider's own model, so returning restores the choice. */
    suspend fun setAiProvider(value: String) {
        val provider = AiProvider.fromId(value)
        context.dataStore.edit { prefs ->
            prefs[K.AI_PROVIDER] = provider.id
            val key = modelKeyFor(provider)
            if (prefs[key].isNullOrBlank()) prefs[key] = provider.defaultModel
        }
    }

    suspend fun setModel(m: String) {
        val provider = AiProvider.fromId(context.dataStore.data.first()[K.AI_PROVIDER])
        context.dataStore.edit { it[modelKeyFor(provider)] = m.trim() }
    }
    suspend fun setThemeMode(m: String) = context.dataStore.edit { it[K.THEME] = m }
    suspend fun setRemind15(b: Boolean) = context.dataStore.edit { it[K.REMIND15] = b }
    suspend fun setRemind30(b: Boolean) = context.dataStore.edit { it[K.REMIND30] = b }
    suspend fun setRemind5(b: Boolean) = context.dataStore.edit { it[K.REMIND5] = b }
    suspend fun setRemindAtStart(b: Boolean) = context.dataStore.edit { it[K.REMIND_START] = b }
    suspend fun setOnboardingDone(b: Boolean) = context.dataStore.edit { it[K.ONBOARDED] = b }
    suspend fun setNotificationPermissionPrompted(b: Boolean) = context.dataStore.edit { it[K.NOTIFICATION_PERMISSION_PROMPTED] = b }
    /** Records what the system actually answered. `prompted` stays true either way so deniers are never nagged. */
    suspend fun setNotificationPermissionGranted(b: Boolean) = context.dataStore.edit { it[K.NOTIFICATION_PERMISSION_GRANTED] = b }
    suspend fun setStudentName(value: String) = context.dataStore.edit { it[K.STUDENT_NAME] = value.trim() }
    suspend fun setRollNumber(value: String) = context.dataStore.edit { it[K.ROLL_NUMBER] = value.trim() }
    suspend fun setBranch(value: String) = context.dataStore.edit { it[K.BRANCH] = value.trim() }
    suspend fun setRegistrationNumber(value: String) = context.dataStore.edit { it[K.REGISTRATION_NUMBER] = value.trim() }
    suspend fun setMentorName(value: String) = context.dataStore.edit { it[K.MENTOR_NAME] = value.trim() }
    suspend fun setStudentSection(value: String) = context.dataStore.edit { it[K.STUDENT_SECTION] = value.trim() }
    suspend fun setStudentSubsection(value: String) = context.dataStore.edit { it[K.STUDENT_SUBSECTION] = value.trim() }
    suspend fun setStudentGroup(value: String) = context.dataStore.edit { it[K.STUDENT_GROUP] = value.trim() }
    suspend fun setMentorMobile(value: String) = context.dataStore.edit { it[K.MENTOR_MOBILE] = value.trim() }
    suspend fun setMentorVenue(value: String) = context.dataStore.edit { it[K.MENTOR_VENUE] = value.trim() }
    suspend fun setProfileSource(value: String) = context.dataStore.edit { it[K.PROFILE_SOURCE] = value.trim() }
    suspend fun setAcademicYear(year: Int) = context.dataStore.edit { it[K.ACADEMIC_YEAR] = year.coerceIn(0, 4) }
    suspend fun setStudentDirectoryCache(branch: String, json: String, updatedAt: Long = System.currentTimeMillis()) = context.dataStore.edit {
        it[K.STUDENT_DIRECTORY_BRANCH] = branch
        it[K.STUDENT_DIRECTORY_JSON] = json
        it[K.STUDENT_DIRECTORY_UPDATED_AT] = updatedAt
    }
    suspend fun saveStudentProfile(
        name: String,
        rollNumber: String,
        branch: String,
        registrationNumber: String,
        fatherName: String,
        motherName: String,
        mentorName: String,
        section: String,
        subsection: String,
        studentGroup: String,
        mentorMobile: String,
        mentorVenue: String,
        source: String,
        classCoordinator: String = ""
    ) = context.dataStore.edit {
        it[K.STUDENT_NAME] = name.trim()
        it[K.ROLL_NUMBER] = rollNumber.trim()
        it[K.BRANCH] = branch.trim()
        it[K.REGISTRATION_NUMBER] = registrationNumber.trim()
        it[K.FATHER_NAME] = fatherName.trim()
        it[K.MOTHER_NAME] = motherName.trim()
        it[K.MENTOR_NAME] = mentorName.trim()
        it[K.STUDENT_SECTION] = section.trim()
        it[K.STUDENT_SUBSECTION] = subsection.trim()
        it[K.STUDENT_GROUP] = studentGroup.trim()
        it[K.MENTOR_MOBILE] = mentorMobile.trim()
        it[K.MENTOR_VENUE] = mentorVenue.trim()
        it[K.PROFILE_SOURCE] = source.trim()
        it[K.CLASS_COORDINATOR] = classCoordinator.trim()
    }
    suspend fun setAnnouncementNotifications(enabled: Boolean) = context.dataStore.edit { it[K.ANNOUNCEMENT_NOTIFICATIONS] = enabled }
    suspend fun setLastAnnouncementId(id: String) = context.dataStore.edit { it[K.LAST_ANNOUNCEMENT_ID] = id }
    /**
     * Replace the cached Home announcements with [json] (the full eligible list,
     * newest first) and remember [newestId] so the next refresh does not notify
     * for an announcement the student has already been told about.
     */
    suspend fun setAnnouncementsCache(json: String, updatedAt: Long, newestId: String) =
        context.dataStore.edit {
            it[K.ANNOUNCEMENTS_JSON] = json
            it[K.ANNOUNCEMENTS_UPDATED_AT] = updatedAt
            it[K.LAST_ANNOUNCEMENT_ID] = newestId
        }

    /**
     * Forget the cached announcements entirely. Called when a successful feed
     * fetch yields nothing to show, so the Home cards stay hidden on the next cold
     * start instead of being restored from this cache. The single-announcement
     * keys written by older builds are cleared too, so they cannot resurrect an
     * announcement the feed has since dropped.
     */
    suspend fun clearAnnouncementsCache() = context.dataStore.edit {
        it.remove(K.ANNOUNCEMENTS_JSON)
        it.remove(K.ANNOUNCEMENTS_UPDATED_AT)
        it.remove(K.LAST_ANNOUNCEMENT_ID)
        it.remove(K.LAST_ANNOUNCEMENT_TITLE)
        it.remove(K.LAST_ANNOUNCEMENT_MESSAGE)
        it.remove(K.LAST_ANNOUNCEMENT_PUBLISHED_AT)
        it.remove(K.LAST_ANNOUNCEMENT_TYPE)
        it.remove(K.LAST_ANNOUNCEMENT_LINK)
        it.remove(K.LAST_ANNOUNCEMENT_BRANCH)
        it.remove(K.LAST_ANNOUNCEMENT_SECTION)
        it.remove(K.LAST_ANNOUNCEMENT_SUBSECTION)
        it.remove(K.LAST_ANNOUNCEMENT_TARGET)
    }
    // --- Friends (local only; never leaves the device) ------------------------

    private val friendJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Adds a friend, replacing any existing entry with the same name. */
    suspend fun addFriend(friend: Friend) = context.dataStore.edit { prefs ->
        val current = decodeFriends(prefs[K.FRIENDS_JSON] ?: "").toMutableList()
        val entry = friend.copy(name = friend.name.trim(), group = friend.group.trim())
        val index = current.indexOfFirst { it.name.equals(entry.name, ignoreCase = true) }
        if (index >= 0) current[index] = entry else current += entry
        prefs[K.FRIENDS_JSON] = friendJson.encodeToString(current)
    }

    suspend fun removeFriend(name: String) = context.dataStore.edit { prefs ->
        val remaining = decodeFriends(prefs[K.FRIENDS_JSON] ?: "")
            .filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        prefs[K.FRIENDS_JSON] = friendJson.encodeToString(remaining)
    }

    /** Reads the stored friends for [settings]'s [AppSettings.friendsJson]. */
    fun decodeFriends(value: String): List<Friend> = runCatching {
        if (value.isBlank()) emptyList() else friendJson.decodeFromString<List<Friend>>(value)
    }.getOrDefault(emptyList())

    suspend fun setErpNoticeCache(json: String, updatedAt: Long) = context.dataStore.edit {
        it[K.ERP_NOTICE_JSON] = json
        it[K.ERP_NOTICE_UPDATED_AT] = updatedAt
    }
    suspend fun setHolidayCache(json: String, updatedAt: Long) = context.dataStore.edit {
        it[K.HOLIDAY_JSON] = json
        it[K.HOLIDAY_UPDATED_AT] = updatedAt
    }

    /**
     * Records (with a non-blank [dayIso]) or clears (blank) the day-scoped
     * "show the timetable anyway" choice for a full holiday.
     */
    suspend fun setHolidayTimetableOverrideDay(dayIso: String) = context.dataStore.edit {
        if (dayIso.isBlank()) it.remove(K.HOLIDAY_TIMETABLE_OVERRIDE_DAY)
        else it[K.HOLIDAY_TIMETABLE_OVERRIDE_DAY] = dayIso
    }

    /**
     * Day-scoped "this free/weekend day follows [followDow]'s timetable".
     * Pass blank [dayIso] (or [followDow] outside 1..7) to clear.
     */
    suspend fun setWeekendTimetableFollow(dayIso: String, followDow: Int) = context.dataStore.edit {
        if (dayIso.isBlank() || followDow !in 1..7) {
            it.remove(K.WEEKEND_FOLLOW_DAY_ISO)
            it.remove(K.WEEKEND_FOLLOW_DOW)
        } else {
            it[K.WEEKEND_FOLLOW_DAY_ISO] = dayIso
            it[K.WEEKEND_FOLLOW_DOW] = followDow
        }
    }

    suspend fun setReleaseCache(marker: String, name: String, notes: String, checkedAt: Long) = context.dataStore.edit {
        it[K.LAST_RELEASE_MARKER] = marker.trim()
        it[K.LAST_RELEASE_NAME] = name.trim()
        it[K.LAST_RELEASE_NOTES] = notes.trim()
        it[K.LAST_RELEASE_CHECKED_AT] = checkedAt
    }
    suspend fun setReleaseNotifiedMarker(marker: String) = context.dataStore.edit { it[K.LAST_RELEASE_NOTIFIED_MARKER] = marker.trim() }
    suspend fun setAttendanceTarget(value: Float) = context.dataStore.edit { it[K.ATTENDANCE_TARGET] = value.coerceIn(50f, 100f) }

    /**
     * Stores the fetched quote under the IST day it belongs to. [dayKey] is written in the
     * same transaction as the text so a cached quote can never be read back without the day
     * that proves whether today's fetch is still owed.
     */
    suspend fun setQuoteCache(text: String, author: String, dayKey: String) = context.dataStore.edit {
        it[K.QUOTE_TEXT] = text.trim()
        it[K.QUOTE_AUTHOR] = author.trim()
        it[K.QUOTE_DAY_KEY] = dayKey
    }

    /** Android versionCode of the last build that finished startup migration. 0 = never recorded. */
    suspend fun getLastInstalledVersionCode(): Int {
        val prefs = context.dataStore.data
        return prefs.first()[K.LAST_INSTALLED_VERSION_CODE] ?: 0
    }

    suspend fun setLastInstalledVersionCode(code: Int) =
        context.dataStore.edit { it[K.LAST_INSTALLED_VERSION_CODE] = code }


    private fun modelKeyFor(provider: AiProvider) = when (provider) {
        AiProvider.GEMINI -> K.MODEL_GEMINI
        AiProvider.GROQ -> K.MODEL_GROQ
        AiProvider.OPENROUTER -> K.MODEL_OPENROUTER
    }

    /**
     * The model to use for [provider], falling back through its own slot, then the legacy
     * single-model key for Gemini, then the provider's own default.
     */
    private fun storedModel(provider: AiProvider, prefs: Preferences): String {
        val stored = prefs[modelKeyFor(provider)]
            ?: if (provider == AiProvider.GEMINI) prefs[K.MODEL] else null
        return normalizeModel(provider, stored)
    }

    /**
     * Cleans up a stored model ID.
     *
     * Gemini IDs are versioned, so a legacy or foreign ID is reset to the current default.
     * An OpenAI-compatible ID is passed through untouched — `meta-llama/llama-3.3-70b-instruct`
     * is a valid Groq model and must not be rewritten to a Gemini one.
     */
    private fun normalizeModel(provider: AiProvider, value: String?): String {
        val model = value?.trim().orEmpty()
        if (!provider.usesGeminiModelIds) return model.ifBlank { provider.defaultModel }
        val stale = model.isBlank() ||
            model.contains("llama", ignoreCase = true) ||
            model.contains("mixtral", ignoreCase = true) ||
            model.contains("gemini-2.5-flash", ignoreCase = true) ||
            (model.contains("gemma", ignoreCase = true) && !model.startsWith("gemini", ignoreCase = true))
        return if (stale) provider.defaultModel else model.removePrefix("models/")
    }
}

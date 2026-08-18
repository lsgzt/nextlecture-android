package com.gndec.timetable.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val group: String? = null,
    val sourceUrl: String = DEFAULT_SOURCE_URL,
    val backendUrl: String = "",
    val aiEnabled: Boolean = true,
    val model: String = "llama-3.1-8b-instant",
    val themeMode: String = "light", // light | dark | system
    val remind15: Boolean = true,
    val remind30: Boolean = false,
    val remind5: Boolean = false,
    val remindAtStart: Boolean = true,
    val onboardingDone: Boolean = false,
    val notificationPermissionPrompted: Boolean = false,
    val studentName: String = "",
    val rollNumber: String = "",
    val branch: String = "",
    val registrationNumber: String = "",
    val mentorName: String = "",
    val temporarySection: String = "",
    val temporarySubsection: String = "",
    val profileSource: String = "manual",
    val studentDirectoryBranch: String = "",
    val studentDirectoryJson: String = "",
    val studentDirectoryUpdatedAt: Long = 0L,
    val announcementNotifications: Boolean = true,
    val lastAnnouncementId: String = "",
    val lastAnnouncementTitle: String = "",
    val lastAnnouncementMessage: String = "",
    val lastAnnouncementPublishedAt: String = ""
) {
    companion object {
        const val DEFAULT_SOURCE_URL =
            "https://appsc.gndec.ac.in/sites/default/files/2026-08/09_08_2026%20FINAL_FILE_subgroups_days_horizontal.html"
    }
}

class SettingsManager(private val context: Context) {

    private object K {
        val GROUP = stringPreferencesKey("group")
        val SOURCE_URL = stringPreferencesKey("source_url")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val MODEL = stringPreferencesKey("model")
        val THEME = stringPreferencesKey("theme_mode")
        val REMIND15 = booleanPreferencesKey("remind_15")
        val REMIND30 = booleanPreferencesKey("remind_30")
        val REMIND5 = booleanPreferencesKey("remind_5")
        val REMIND_START = booleanPreferencesKey("remind_start")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val NOTIFICATION_PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val ROLL_NUMBER = stringPreferencesKey("roll_number")
        val BRANCH = stringPreferencesKey("branch")
        val REGISTRATION_NUMBER = stringPreferencesKey("registration_number")
        val MENTOR_NAME = stringPreferencesKey("mentor_name")
        val TEMPORARY_SECTION = stringPreferencesKey("temporary_section")
        val TEMPORARY_SUBSECTION = stringPreferencesKey("temporary_subsection")
        val PROFILE_SOURCE = stringPreferencesKey("profile_source")
        val STUDENT_DIRECTORY_BRANCH = stringPreferencesKey("student_directory_branch")
        val STUDENT_DIRECTORY_JSON = stringPreferencesKey("student_directory_json")
        val STUDENT_DIRECTORY_UPDATED_AT = longPreferencesKey("student_directory_updated_at")
        val ANNOUNCEMENT_NOTIFICATIONS = booleanPreferencesKey("announcement_notifications")
        val LAST_ANNOUNCEMENT_ID = stringPreferencesKey("last_announcement_id")
        val LAST_ANNOUNCEMENT_TITLE = stringPreferencesKey("last_announcement_title")
        val LAST_ANNOUNCEMENT_MESSAGE = stringPreferencesKey("last_announcement_message")
        val LAST_ANNOUNCEMENT_PUBLISHED_AT = stringPreferencesKey("last_announcement_published_at")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            group = p[K.GROUP],
            sourceUrl = p[K.SOURCE_URL] ?: AppSettings.DEFAULT_SOURCE_URL,
            backendUrl = p[K.BACKEND_URL] ?: "",
            aiEnabled = p[K.AI_ENABLED] ?: true,
            model = p[K.MODEL] ?: "llama-3.1-8b-instant",
            themeMode = p[K.THEME] ?: "light",
            remind15 = p[K.REMIND15] ?: true,
            remind30 = p[K.REMIND30] ?: false,
            remind5 = p[K.REMIND5] ?: false,
            remindAtStart = p[K.REMIND_START] ?: true,
            onboardingDone = p[K.ONBOARDED] ?: false,
            notificationPermissionPrompted = p[K.NOTIFICATION_PERMISSION_PROMPTED] ?: false,
            studentName = p[K.STUDENT_NAME] ?: "",
            rollNumber = p[K.ROLL_NUMBER] ?: "",
            branch = p[K.BRANCH] ?: "",
            registrationNumber = p[K.REGISTRATION_NUMBER] ?: "",
            mentorName = p[K.MENTOR_NAME] ?: "",
            temporarySection = p[K.TEMPORARY_SECTION] ?: "",
            temporarySubsection = p[K.TEMPORARY_SUBSECTION] ?: "",
            profileSource = p[K.PROFILE_SOURCE] ?: "manual",
            studentDirectoryBranch = p[K.STUDENT_DIRECTORY_BRANCH] ?: "",
            studentDirectoryJson = p[K.STUDENT_DIRECTORY_JSON] ?: "",
            studentDirectoryUpdatedAt = p[K.STUDENT_DIRECTORY_UPDATED_AT] ?: 0L,
            announcementNotifications = p[K.ANNOUNCEMENT_NOTIFICATIONS] ?: true,
            lastAnnouncementId = p[K.LAST_ANNOUNCEMENT_ID] ?: "",
            lastAnnouncementTitle = p[K.LAST_ANNOUNCEMENT_TITLE] ?: "",
            lastAnnouncementMessage = p[K.LAST_ANNOUNCEMENT_MESSAGE] ?: "",
            lastAnnouncementPublishedAt = p[K.LAST_ANNOUNCEMENT_PUBLISHED_AT] ?: ""
        )
    }

    suspend fun setGroup(g: String) = context.dataStore.edit { it[K.GROUP] = g }
    suspend fun setSourceUrl(u: String) = context.dataStore.edit { it[K.SOURCE_URL] = u }
    suspend fun setBackendUrl(u: String) = context.dataStore.edit { it[K.BACKEND_URL] = u.trim() }
    suspend fun setAiEnabled(b: Boolean) = context.dataStore.edit { it[K.AI_ENABLED] = b }
    suspend fun setModel(m: String) = context.dataStore.edit { it[K.MODEL] = m.trim() }
    suspend fun setThemeMode(m: String) = context.dataStore.edit { it[K.THEME] = m }
    suspend fun setRemind15(b: Boolean) = context.dataStore.edit { it[K.REMIND15] = b }
    suspend fun setRemind30(b: Boolean) = context.dataStore.edit { it[K.REMIND30] = b }
    suspend fun setRemind5(b: Boolean) = context.dataStore.edit { it[K.REMIND5] = b }
    suspend fun setRemindAtStart(b: Boolean) = context.dataStore.edit { it[K.REMIND_START] = b }
    suspend fun setOnboardingDone(b: Boolean) = context.dataStore.edit { it[K.ONBOARDED] = b }
    suspend fun setNotificationPermissionPrompted(b: Boolean) = context.dataStore.edit { it[K.NOTIFICATION_PERMISSION_PROMPTED] = b }
    suspend fun setStudentName(value: String) = context.dataStore.edit { it[K.STUDENT_NAME] = value.trim() }
    suspend fun setRollNumber(value: String) = context.dataStore.edit { it[K.ROLL_NUMBER] = value.trim() }
    suspend fun setBranch(value: String) = context.dataStore.edit { it[K.BRANCH] = value.trim() }
    suspend fun setRegistrationNumber(value: String) = context.dataStore.edit { it[K.REGISTRATION_NUMBER] = value.trim() }
    suspend fun setMentorName(value: String) = context.dataStore.edit { it[K.MENTOR_NAME] = value.trim() }
    suspend fun setTemporarySection(value: String) = context.dataStore.edit { it[K.TEMPORARY_SECTION] = value.trim() }
    suspend fun setTemporarySubsection(value: String) = context.dataStore.edit { it[K.TEMPORARY_SUBSECTION] = value.trim() }
    suspend fun setProfileSource(value: String) = context.dataStore.edit { it[K.PROFILE_SOURCE] = value.trim() }
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
        mentorName: String,
        temporarySection: String,
        temporarySubsection: String,
        source: String
    ) = context.dataStore.edit {
        it[K.STUDENT_NAME] = name.trim()
        it[K.ROLL_NUMBER] = rollNumber.trim()
        it[K.BRANCH] = branch.trim()
        it[K.REGISTRATION_NUMBER] = registrationNumber.trim()
        it[K.MENTOR_NAME] = mentorName.trim()
        it[K.TEMPORARY_SECTION] = temporarySection.trim()
        it[K.TEMPORARY_SUBSECTION] = temporarySubsection.trim()
        it[K.PROFILE_SOURCE] = source.trim()
    }
    suspend fun setAnnouncementNotifications(enabled: Boolean) = context.dataStore.edit { it[K.ANNOUNCEMENT_NOTIFICATIONS] = enabled }
    suspend fun setLastAnnouncementId(id: String) = context.dataStore.edit { it[K.LAST_ANNOUNCEMENT_ID] = id }
    suspend fun setAnnouncementCache(id: String, title: String, message: String, publishedAt: String) = context.dataStore.edit {
        it[K.LAST_ANNOUNCEMENT_ID] = id
        it[K.LAST_ANNOUNCEMENT_TITLE] = title
        it[K.LAST_ANNOUNCEMENT_MESSAGE] = message
        it[K.LAST_ANNOUNCEMENT_PUBLISHED_AT] = publishedAt
    }
}

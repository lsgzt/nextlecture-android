package com.gndec.timetable.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    val studentName: String = "",
    val rollNumber: String = "",
    val branch: String = "",
    val registrationNumber: String = "",
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
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val ROLL_NUMBER = stringPreferencesKey("roll_number")
        val BRANCH = stringPreferencesKey("branch")
        val REGISTRATION_NUMBER = stringPreferencesKey("registration_number")
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
            studentName = p[K.STUDENT_NAME] ?: "",
            rollNumber = p[K.ROLL_NUMBER] ?: "",
            branch = p[K.BRANCH] ?: "",
            registrationNumber = p[K.REGISTRATION_NUMBER] ?: "",
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
    suspend fun setStudentName(value: String) = context.dataStore.edit { it[K.STUDENT_NAME] = value.trim() }
    suspend fun setRollNumber(value: String) = context.dataStore.edit { it[K.ROLL_NUMBER] = value.trim() }
    suspend fun setBranch(value: String) = context.dataStore.edit { it[K.BRANCH] = value.trim() }
    suspend fun setRegistrationNumber(value: String) = context.dataStore.edit { it[K.REGISTRATION_NUMBER] = value.trim() }
    suspend fun setAnnouncementNotifications(enabled: Boolean) = context.dataStore.edit { it[K.ANNOUNCEMENT_NOTIFICATIONS] = enabled }
    suspend fun setLastAnnouncementId(id: String) = context.dataStore.edit { it[K.LAST_ANNOUNCEMENT_ID] = id }
    suspend fun setAnnouncementCache(id: String, title: String, message: String, publishedAt: String) = context.dataStore.edit {
        it[K.LAST_ANNOUNCEMENT_ID] = id
        it[K.LAST_ANNOUNCEMENT_TITLE] = title
        it[K.LAST_ANNOUNCEMENT_MESSAGE] = message
        it[K.LAST_ANNOUNCEMENT_PUBLISHED_AT] = publishedAt
    }
}

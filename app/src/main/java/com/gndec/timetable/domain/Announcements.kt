package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val message: String,
    val publishedAt: String = "",
    val type: String = "info",
    val active: Boolean = true
)

@Serializable
data class AnnouncementFeed(
    val version: Int = 1,
    val announcements: List<Announcement> = emptyList()
)

class AnnouncementManager(
    private val context: Context,
    private val settings: SettingsManager
) {
    companion object {
        const val FEED_URL = "https://raw.githubusercontent.com/lsgzt/nextlecture-android/main/announcements.json"
    }

    private val _latest = MutableStateFlow<Announcement?>(null)
    val latest: StateFlow<Announcement?> = _latest.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        val cached = settings.flow.first()
        if (cached.lastAnnouncementId.isNotBlank() && cached.lastAnnouncementTitle.isNotBlank() && cached.lastAnnouncementMessage.isNotBlank()) {
            _latest.value = Announcement(
                id = cached.lastAnnouncementId,
                title = cached.lastAnnouncementTitle,
                message = cached.lastAnnouncementMessage,
                publishedAt = cached.lastAnnouncementPublishedAt
            )
        }
    }

    suspend fun refreshAndNotify(): Announcement? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(FEED_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val announcement = Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val feed = json.decodeFromString<AnnouncementFeed>(body)
                feed.announcements
                    .asSequence()
                    .filter { it.active && it.id.isNotBlank() && it.title.isNotBlank() && it.message.isNotBlank() }
                    .maxByOrNull { it.publishedAt }
            }
            _latest.value = announcement
            if (announcement != null) {
                val current = settings.flow.first()
                if (current.announcementNotifications && current.lastAnnouncementId != announcement.id) {
                    NotificationHelper.showAnnouncement(context, announcement.id, announcement.title, announcement.message)
                }
                settings.setAnnouncementCache(announcement.id, announcement.title, announcement.message, announcement.publishedAt)
            }
            announcement
        } catch (_: Exception) {
            null
        }
    }
}

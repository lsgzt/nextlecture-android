package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.AppSettings
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
    /** info | notice | warn | happy | urgent | update — drives card color + emoji. */
    val type: String = "info",
    /** Optional deep link / URL. When set, the whole home card is tappable. */
    val link: String = "",
    /**
     * Audience targeting (all optional, empty / "all" = everyone at that level).
     * Each field accepts a single value or a comma/semicolon list (OR within the field):
     * - branch "IT" → all IT; "CS, IT" → CS or IT
     * - branch + section → section(s) within matching branch(es)
     * - + subsection → subgroup(s) (e.g. "ITB1, ITB2")
     */
    val branch: String = "",
    val section: String = "",
    val subsection: String = "",
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
        if (cached.lastAnnouncementId.isBlank() ||
            cached.lastAnnouncementTitle.isBlank() ||
            cached.lastAnnouncementMessage.isBlank()
        ) {
            _latest.value = null
            return@withContext
        }
        val announcement = Announcement(
            id = cached.lastAnnouncementId,
            title = cached.lastAnnouncementTitle,
            message = cached.lastAnnouncementMessage,
            publishedAt = cached.lastAnnouncementPublishedAt,
            type = cached.lastAnnouncementType.ifBlank { "info" },
            link = cached.lastAnnouncementLink,
            branch = cached.lastAnnouncementBranch,
            section = cached.lastAnnouncementSection,
            subsection = cached.lastAnnouncementSubsection
        )
        _latest.value = if (announcement.matchesAudience(cached)) announcement else null
    }

    suspend fun refreshAndNotify(): Announcement? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(FEED_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val profile = settings.flow.first()
            val announcement = Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val feed = json.decodeFromString<AnnouncementFeed>(body)
                feed.announcements
                    .asSequence()
                    .filter { it.active && it.id.isNotBlank() && it.title.isNotBlank() && it.message.isNotBlank() }
                    .filter { it.matchesAudience(profile) }
                    .maxByOrNull { it.publishedAt }
            }
            _latest.value = announcement
            if (announcement != null) {
                if (profile.announcementNotifications && profile.lastAnnouncementId != announcement.id) {
                    NotificationHelper.showAnnouncement(
                        context,
                        announcement.id,
                        announcement.title,
                        announcement.message
                    )
                }
                settings.setAnnouncementCache(
                    id = announcement.id,
                    title = announcement.title,
                    message = announcement.message,
                    publishedAt = announcement.publishedAt,
                    type = announcement.type,
                    link = announcement.link,
                    branch = announcement.branch,
                    section = announcement.section,
                    subsection = announcement.subsection
                )
            }
            announcement
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Audience filter:
 * - blank / "all" at a level = any value at that level
 * - comma/semicolon-separated lists match if the student hits **any** value
 *   (e.g. branch "CS, IT" → CS or IT students)
 * - more specific fields only apply when coarser ones match
 * - subsection is matched against group / studentGroup / studentSubsection
 */
internal fun Announcement.matchesAudience(profile: AppSettings): Boolean {
    val targetBranches = parseAudienceTokens(branch)
    val targetSections = parseAudienceTokens(section)
    val targetSubsections = parseAudienceTokens(subsection)

    if (targetBranches != null) {
        val userBranch = normalizeAudienceToken(profile.branch) ?: return false
        if (userBranch !in targetBranches) return false
    }

    if (targetSections != null) {
        val userSection = normalizeAudienceToken(profile.studentSection) ?: return false
        if (userSection !in targetSections) return false
    }

    if (targetSubsections != null) {
        val candidates = listOfNotNull(
            normalizeAudienceToken(profile.studentSubsection),
            normalizeAudienceToken(profile.studentGroup),
            normalizeAudienceToken(profile.group)
        )
        if (candidates.none { it in targetSubsections }) return false
    }

    return true
}

/**
 * Parse an audience field into a set of normalized tokens.
 * Returns null when the field means “everyone” (blank, only "all"/"*", or empty list).
 * Supports multiple values: `"CS, IT"`, `"ITB1; ITB2"`, `"CS,IT,ECE"`.
 */
internal fun parseAudienceTokens(raw: String?): Set<String>? {
    if (raw.isNullOrBlank()) return null
    val tokens = raw.split(',', ';', '|')
        .mapNotNull { normalizeAudienceToken(it) }
        .toSet()
    return tokens.takeIf { it.isNotEmpty() }
}

/** null means "match everyone" for a single token (empty or the word all). */
private fun normalizeAudienceToken(raw: String?): String? {
    val value = raw?.trim()?.lowercase()?.replace(" ", "")?.replace("-", "") ?: return null
    if (value.isEmpty() || value == "all" || value == "*") return null
    return value
}

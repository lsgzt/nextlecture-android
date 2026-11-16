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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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
    /**
     * Which surface this announcement is meant for: "android-app", "webapp" or
     * "both". `for` is a Kotlin keyword, so it is mapped from the JSON key.
     */
    @SerialName("for")
    val target: String = "both",
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
        const val FEED_URL = "https://raw.githubusercontent.com/lsgzt/nextlecture/main/announcements.json"
    }

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        val cached = settings.flow.first()
        val restored = decode(cached.announcementsJson).ifEmpty { legacyAnnouncements(cached) }
        _announcements.value = restored.filter { it.isShowable() && it.matchesAudience(cached) }
    }

    /**
     * Fetch the feed and replace the Home announcements.
     *
     * A null result means the fetch itself failed (offline, HTTP error, malformed
     * body); whatever is already on screen and cached is left alone. An empty
     * list means the fetch worked and there is genuinely nothing to show — the
     * cache is cleared so the cards do not come back on the next cold start.
     */
    suspend fun refreshAndNotify(): List<Announcement> = withContext(Dispatchers.IO) {
        val profile = settings.flow.first()
        val fetched: List<Announcement>? = try {
            val request = Request.Builder()
                .url(FEED_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<AnnouncementFeed>(body).announcements
                    .filter { it.isShowable() && it.matchesAudience(profile) }
                    // Newest first. ISO-8601 sorts lexicographically, so this is
                    // chronological without a date parse.
                    .sortedByDescending { it.publishedAt }
            }
        } catch (_: Exception) {
            null
        }

        if (fetched == null) return@withContext _announcements.value

        _announcements.value = fetched
        if (fetched.isEmpty()) {
            settings.clearAnnouncementsCache()
        } else {
            val newest = fetched.first()
            if (profile.announcementNotifications && profile.lastAnnouncementId != newest.id) {
                NotificationHelper.showAnnouncement(
                    context,
                    newest.id,
                    newest.title,
                    newest.message
                )
            }
            settings.setAnnouncementsCache(
                json = json.encodeToString(fetched),
                updatedAt = System.currentTimeMillis(),
                newestId = newest.id
            )
        }
        fetched
    }

    private fun decode(value: String): List<Announcement> =
        runCatching { if (value.isBlank()) emptyList() else json.decodeFromString<List<Announcement>>(value) }
            .getOrDefault(emptyList())

    /**
     * The single announcement cached by builds that only ever showed one. Read
     * once, only while no list has been cached, so an app upgrading from an
     * earlier version still renders its card offline on the very first launch.
     */
    private fun legacyAnnouncements(cached: AppSettings): List<Announcement> {
        if (cached.lastAnnouncementId.isBlank() ||
            cached.lastAnnouncementTitle.isBlank() ||
            cached.lastAnnouncementMessage.isBlank()
        ) {
            return emptyList()
        }
        return listOf(
            Announcement(
                id = cached.lastAnnouncementId,
                title = cached.lastAnnouncementTitle,
                message = cached.lastAnnouncementMessage,
                publishedAt = cached.lastAnnouncementPublishedAt,
                type = cached.lastAnnouncementType.ifBlank { "info" },
                link = cached.lastAnnouncementLink,
                branch = cached.lastAnnouncementBranch,
                section = cached.lastAnnouncementSection,
                subsection = cached.lastAnnouncementSubsection,
                target = cached.lastAnnouncementTarget
            )
        )
    }
}

/** Everything that has to hold before an announcement is worth showing at all. */
private fun Announcement.isShowable(): Boolean =
    active && id.isNotBlank() && title.isNotBlank() && message.isNotBlank() && appliesToAndroid()

/**
 * Whether this announcement is meant for the Android app.
 *
 * Anything that is not explicitly web-only shows here, including "both" and a
 * missing key: feeds written before `for` existed carry no value at all, and an
 * announcement with no stated surface should keep reaching the app rather than
 * silently vanishing.
 */
internal fun Announcement.appliesToAndroid(): Boolean {
    val key = target.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
    return key != "webapp" && key != "web"
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

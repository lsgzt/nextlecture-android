package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.BuildConfig
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
private data class GithubRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String? = null
)

data class ReleaseUpdateState(
    val latestMarker: String = "",
    val releaseName: String = "",
    val notes: String = "",
    val checkedAt: Long = 0L,
    val updateAvailable: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null
)

class ReleaseUpdateManager(
    private val context: Context,
    private val settings: SettingsManager
) {
    companion object {
        const val RELEASES_API_URL = "https://api.github.com/repos/lsgzt/nextlecture-android/releases/latest"
        const val DOWNLOAD_URL = "https://github.com/lsgzt/nextlecture-android/releases/latest/download/gndec-timetable.apk"
        private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

        fun installedMarker(): String = if (compareMarkers(BuildConfig.RELEASE_MARKER, BuildConfig.VERSION_NAME) >= 0) {
            BuildConfig.RELEASE_MARKER
        } else {
            BuildConfig.VERSION_NAME
        }

        fun isNewer(remote: String, local: String): Boolean = compareMarkers(remote, local) > 0

        private fun compareMarkers(left: String, right: String): Int {
            val a = numericParts(left)
            val b = numericParts(right)
            val size = maxOf(a.size, b.size)
            for (index in 0 until size) {
                val av = a.getOrElse(index) { 0 }
                val bv = b.getOrElse(index) { 0 }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }

        private fun numericParts(value: String): List<Int> = value
            .trim()
            .removePrefix("v")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    private val _state = MutableStateFlow(ReleaseUpdateState())
    val state: StateFlow<ReleaseUpdateState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        val cached = settings.flow.first()
        if (cached.lastReleaseMarker.isNotBlank()) {
            _state.value = ReleaseUpdateState(
                latestMarker = cached.lastReleaseMarker,
                releaseName = cached.lastReleaseName,
                notes = cached.lastReleaseNotes,
                checkedAt = cached.lastReleaseCheckedAt,
                updateAvailable = isNewer(cached.lastReleaseMarker, installedMarker())
            )
        }
    }

    suspend fun refreshIfStale() {
        val cached = settings.flow.first()
        if (System.currentTimeMillis() - cached.lastReleaseCheckedAt >= CHECK_INTERVAL_MS) {
            checkForUpdates(force = false)
        }
    }

    suspend fun checkForUpdates(force: Boolean = true): ReleaseUpdateState = withContext(Dispatchers.IO) {
        if (!force) {
            val cached = settings.flow.first()
            if (System.currentTimeMillis() - cached.lastReleaseCheckedAt < CHECK_INTERVAL_MS) {
                loadCached()
                return@withContext _state.value
            }
        }

        _state.value = _state.value.copy(checking = true, error = null)
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GNDEC-Timetable/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            val release = Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("GitHub returned ${response.code}")
                val body = response.body?.string() ?: throw IllegalStateException("Empty GitHub response")
                json.decodeFromString<GithubRelease>(body)
            }
            if (release.draft || release.prerelease || release.tag_name.isBlank()) {
                throw IllegalStateException("No stable release found")
            }

            val now = System.currentTimeMillis()
            val available = isNewer(release.tag_name, installedMarker())
            val current = settings.flow.first()
            val next = ReleaseUpdateState(
                latestMarker = release.tag_name.trim().removePrefix("v"),
                releaseName = release.name.ifBlank { "GNDEC Timetable ${release.tag_name}" },
                notes = release.body.trim(),
                checkedAt = now,
                updateAvailable = available,
                checking = false
            )
            _state.value = next
            settings.setReleaseCache(next.latestMarker, next.releaseName, next.notes, now)

            if (available && current.announcementNotifications && current.lastReleaseNotifiedMarker != next.latestMarker) {
                NotificationHelper.showAppUpdate(context, next.latestMarker, next.releaseName)
                settings.setReleaseNotifiedMarker(next.latestMarker)
            }
            next
        } catch (error: Exception) {
            val failed = _state.value.copy(checking = false, error = error.message ?: "Could not check GitHub releases")
            _state.value = failed
            failed
        }
    }

}

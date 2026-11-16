package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.Net
import com.gndec.timetable.parse.DownloadLink
import com.gndec.timetable.parse.DownloadsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** UI-facing state of the Downloads feature. */
sealed interface DownloadsState {
    /** No cached data yet — first load in progress. */
    data object Loading : DownloadsState

    /**
     * Links available. [fromSnapshot] is true when they came from the bundled
     * page snapshot rather than the network (offline-first start).
     */
    data class Ready(
        val links: List<DownloadLink>,
        val fetchedAtMillis: Long,
        val refreshing: Boolean,
        val fromSnapshot: Boolean = false,
        val error: String? = null
    ) : DownloadsState

    /** No cache, no snapshot parse, network failed. */
    data class Error(val message: String) : DownloadsState
}

/** What persists between app runs. */
@Serializable
private data class CachedDownloads(
    val links: List<DownloadLink>,
    val fetchedAtMillis: Long
)

/**
 * Keeps the college Downloads page (gndec.ac.in/?q=node/85) available:
 * parsed, student-priority sorted, cached on disk, refreshed in the
 * background, with a bundled snapshot as the offline fallback so the
 * section is never empty on first open without network.
 */
class DownloadsManager(
    context: Context,
    private val client: OkHttpClient = Net.client
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val assets = context.assets
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<DownloadsState>(DownloadsState.Loading)
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private var loaded = false
    private var cache: CachedDownloads? = null

    /** Emits cache first (once per process), refreshes when stale. */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        if (!loaded) {
            loaded = true
            withContext(Dispatchers.IO) { readCache() }
        }
        val cached = cache
        val freshEnough = cached != null &&
            System.currentTimeMillis() - cached.fetchedAtMillis < FRESH_WINDOW_MS
        if (!forceRefresh && freshEnough) {
            publishReady(refreshing = false)
            return
        }
        refresh(force = forceRefresh)
    }

    suspend fun refresh(force: Boolean = true) {
        refreshMutex.withLock {
            if (cache != null) publishReady(refreshing = true)
            try {
                val html = withContext(Dispatchers.IO) { downloadPage() }
                if (html != null) {
                    val links = withContext(Dispatchers.Default) { DownloadsParser.parse(html) }
                    if (links.isNotEmpty()) {
                        val newCache = CachedDownloads(links, System.currentTimeMillis())
                        cache = newCache
                        withContext(Dispatchers.IO) { writeCache(newCache) }
                        publishReady(refreshing = false)
                        return@withLock
                    }
                }
                // Network failed or produced nothing usable: fall back to what
                // we already have — the disk cache, else the bundled snapshot.
                if (cache != null) {
                    publishReady(refreshing = false, error = "Couldn’t reach gndec.ac.in — showing the last saved list")
                } else {
                    loadSnapshot(error = "Couldn’t reach gndec.ac.in — showing a bundled snapshot")
                }
            } catch (e: Exception) {
                if (cache != null) {
                    publishReady(refreshing = false, error = e.message ?: "Refresh failed")
                } else {
                    loadSnapshot(error = e.message ?: "Refresh failed")
                }
            }
        }
    }

    private fun publishReady(refreshing: Boolean, error: String? = null) {
        val cached = cache ?: return
        _state.value = DownloadsState.Ready(
            links = cached.links,
            fetchedAtMillis = cached.fetchedAtMillis,
            refreshing = refreshing,
            error = error
        )
    }

    /** Offline start: parse the page snapshot shipped inside the APK. */
    private fun loadSnapshot(error: String?) {
        val links = runCatching {
            assets.open(SNAPSHOT_ASSET).bufferedReader().use { it.readText() }
        }.map { DownloadsParser.parse(it) }.getOrDefault(emptyList())
        if (links.isEmpty()) {
            _state.value = DownloadsState.Error(error ?: "No downloads available")
            return
        }
        _state.value = DownloadsState.Ready(
            links = links,
            fetchedAtMillis = 0L,
            refreshing = false,
            fromSnapshot = true,
            error = error
        )
    }

    private fun downloadPage(): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(DownloadsParser.SOURCE_URL)
                .header("Accept", "text/html")
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun readCache() {
        cache = runCatching {
            if (!cacheFile.exists()) return
            json.decodeFromString<CachedDownloads>(cacheFile.readText())
        }.getOrNull()?.takeIf { it.links.isNotEmpty() }
        if (cache != null) publishReady(refreshing = false)
    }

    private fun writeCache(data: CachedDownloads) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(json.encodeToString(data))
                tmp.delete()
            }
        }
    }

    companion object {
        const val CACHE_FILE = "downloads_cache.json"
        const val SNAPSHOT_ASSET = "downloads_snapshot.html"

        /** The college rarely edits this page; re-fetch every two days. */
        const val FRESH_WINDOW_MS = 48L * 60 * 60 * 1000
    }
}

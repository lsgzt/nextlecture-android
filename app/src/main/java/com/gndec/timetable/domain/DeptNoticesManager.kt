package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.Net
import com.gndec.timetable.parse.DeptNotice
import com.gndec.timetable.parse.DeptNoticesParser
import com.gndec.timetable.parse.DeptSite
import com.gndec.timetable.parse.DeptSites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** UI-facing state of the department notices feature. */
sealed interface DeptNoticesState {
    /** No department resolved / first load in progress. */
    data object Loading : DeptNoticesState

    /**
     * Notices for the department named by [deptKey]. [refreshing] is true while
     * a network refresh runs on top of the visible list.
     */
    data class Ready(
        val deptKey: String,
        val notices: List<DeptNotice>,
        val fetchedAtMillis: Long,
        val refreshing: Boolean = false,
        val error: String? = null
    ) : DeptNoticesState

    /** No cache and the network failed. */
    data class Error(val deptKey: String, val message: String) : DeptNoticesState
}

/** What persists between app runs, one file per department. */
@Serializable
private data class CachedDeptNotices(
    val notices: List<DeptNotice>,
    val fetchedAtMillis: Long
)

/**
 * The student's own department notice board: resolves which of the seven
 * department sites belongs on their Notice tab (saved branch; first-year
 * students are always in Applied Sciences), then keeps that site's notice
 * list parsed, cached on disk and refreshed in the background — the same
 * offline-first shape DownloadsManager uses.
 */
class DeptNoticesManager(
    context: Context,
    private val settings: SettingsManager,
    private val client: OkHttpClient = Net.client
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val filesDir = context.filesDir
    private val refreshMutex = Mutex()

    private val _dept = MutableStateFlow<DeptSite?>(null)
    val dept: StateFlow<DeptSite?> = _dept.asStateFlow()

    private val _state = MutableStateFlow<DeptNoticesState>(DeptNoticesState.Loading)
    val state: StateFlow<DeptNoticesState> = _state.asStateFlow()

    private var cacheDept: DeptSite? = null
    private var cache: CachedDeptNotices? = null
    private var loaded = false

    /**
     * Resolves the department from the saved profile and serves its notices:
     * cache first, refresh when stale. Re-resolves on every call so a branch
     * or year change is picked up the next time the Notice tab opens.
     */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        val site = resolveSite()
        _dept.value = site
        if (site == null) {
            // No branch saved: the Notice tab simply keeps its default sources.
            _state.value = DeptNoticesState.Loading
            return
        }
        if (cacheDept != site) {
            cacheDept = site
            cache = null
            loaded = false
            _state.value = DeptNoticesState.Loading
        }
        if (!loaded) {
            loaded = true
            withContext(Dispatchers.IO) { readCache(site) }
        }
        val cached = cache
        val freshEnough = cached != null && !forceRefresh &&
            System.currentTimeMillis() - cached.fetchedAtMillis < FRESH_WINDOW_MS
        if (!forceRefresh && freshEnough) {
            publishReady(site, refreshing = false)
            return
        }
        refresh(force = forceRefresh)
    }

    /** Network refresh of the current department's page. */
    suspend fun refresh(force: Boolean = true) {
        val site = cacheDept ?: resolveSite() ?: return
        cacheDept = site
        refreshMutex.withLock {
            if (cache != null) publishReady(site, refreshing = true)
            try {
                val html = withContext(Dispatchers.IO) { downloadPage(site) }
                if (html.isNullOrBlank()) error("Unreachable")
                val notices = withContext(Dispatchers.Default) { DeptNoticesParser.parse(html, site) }
                if (notices.isEmpty()) error("${site.label}'s page had no notices")
                val newCache = CachedDeptNotices(notices, System.currentTimeMillis())
                cache = newCache
                withContext(Dispatchers.IO) { writeCache(site, newCache) }
                publishReady(site, refreshing = false)
            } catch (e: Exception) {
                if (cache != null) {
                    publishReady(site, refreshing = false, error = "Couldn’t reach ${site.host} — showing the last saved notices")
                } else {
                    _state.value = DeptNoticesState.Error(site.key, e.message ?: "Could not load department notices")
                }
            }
        }
    }

    private fun publishReady(site: DeptSite, refreshing: Boolean, error: String? = null) {
        val cached = cache ?: return
        _state.value = DeptNoticesState.Ready(
            deptKey = site.key,
            notices = cached.notices,
            fetchedAtMillis = cached.fetchedAtMillis,
            refreshing = refreshing,
            error = error
        )
    }

    private suspend fun resolveSite(): DeptSite? {
        val s = settings.flow.first()
        return DeptSites.resolve(
            branch = s.branch,
            academicYear = s.academicYear,
            group = s.group ?: s.studentGroup.ifBlank { null }
        )
    }

    private fun downloadPage(site: DeptSite): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(site.url)
                .header("Accept", "text/html")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun cacheFile(site: DeptSite): File = File(filesDir, "dept_notices_${site.key}.json")

    private fun readCache(site: DeptSite) {
        cache = runCatching {
            val file = cacheFile(site)
            if (!file.exists()) return
            json.decodeFromString<CachedDeptNotices>(file.readText())
        }.getOrNull()?.takeIf { it.notices.isNotEmpty() }
        if (cache != null) publishReady(site, refreshing = false)
    }

    private fun writeCache(site: DeptSite, data: CachedDeptNotices) {
        runCatching {
            val file = cacheFile(site)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(data))
                tmp.delete()
            }
        }
    }

    companion object {
        /** Departments touch their notice lists every few days. */
        const val FRESH_WINDOW_MS = 12L * 60 * 60 * 1000
    }
}

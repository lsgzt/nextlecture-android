package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.net.RoomTimetableClient
import com.gndec.timetable.parse.GlobalRoomData
import com.gndec.timetable.parse.SourceRoomDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** UI-facing state of the vacant rooms feature. */
sealed interface VacantRoomsState {
    /** No cached data yet — first load in progress. */
    data object Loading : VacantRoomsState

    /** Data available (from cache or network). [error] carries a non-fatal refresh failure. */
    data class Ready(
        val data: GlobalRoomData,
        val refreshing: Boolean,
        val error: String? = null
    ) : VacantRoomsState

    /** No cache and refresh failed. */
    data class Error(val message: String) : VacantRoomsState
}

/** What persists between app runs: one parsed document per contributing root. */
@Serializable
data class CachedRoomData(
    val docs: List<SourceRoomDoc>,
    val incompleteRoots: List<String>
)

/**
 * Keeps the GLOBAL GNDEC room availability available: discovers the newest
 * published room timetable of every department (Applied Sciences college-wide
 * file plus CSE/ECE/EE/ME/CE/IT/MCA/MBA), caches the parsed snapshots on disk,
 * and serves the merged dataset instantly on subsequent opens while a
 * background refresh runs.
 *
 * Refresh policy: each root is re-downloaded only when its index page reports
 * a document URL that differs from the cached one (the college publishes
 * revisions under new URLs and never mutates a published file). A root whose
 * refresh fails keeps its previous document when its URL is still listed,
 * otherwise it is reported in [GlobalRoomData.incompleteRoots] — the UI must
 * make incomplete coverage visible instead of silently showing wrong vacancies.
 */
class VacantRoomsManager(
    private val context: Context,
    private val client: RoomTimetableClient = RoomTimetableClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val refreshMutex = Mutex()

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<VacantRoomsState>(
        VacantRoomsState.Loading
    )
    val state = _state as kotlinx.coroutines.flow.StateFlow<VacantRoomsState>

    private var loaded = false
    private var cache: CachedRoomData? = null

    /**
     * Emits cached data immediately (once per process), then refreshes in the
     * background when the cache is missing or older than [FRESH_WINDOW_MS].
     */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        if (!loaded) {
            loaded = true
            withContext(Dispatchers.IO) { readCache() }
        }
        val cached = cache
        val freshEnough = cached != null &&
            System.currentTimeMillis() - latestFetchMillis(cached) < FRESH_WINDOW_MS
        if (!forceRefresh && freshEnough) {
            publishReady(refreshing = false)
            return
        }
        refresh(force = forceRefresh)
    }

    /** Forces a discovery + download + parse cycle. Failures keep cached data usable. */
    suspend fun refresh(force: Boolean = true) {
        val acquired = refreshMutex.tryLock()
        if (!acquired) return
        try {
            val cached = cache ?: CachedRoomData(docs = emptyList(), incompleteRoots = emptyList())
            publishReady(refreshing = true)

            val now = System.currentTimeMillis()
            val discovered = withContext(Dispatchers.IO) {
                runCatching { client.discoverAll(now) }.getOrNull()
            }

            // Per-root decision: reuse the cached document while its URL is
            // unchanged, or when the index page could not be reached (the last
            // known document stays the best available information). Otherwise
            // download the newest valid document for that root.
            val reused = LinkedHashMap<String, SourceRoomDoc>()
            val rootsToFetch = mutableListOf<Pair<RoomSourceRoot, com.gndec.timetable.net.RootCandidates?>>()
            for (root in RoomSourceRoot.entries) {
                val cachedDoc = cached.docs.firstOrNull { it.rootId == root.id }
                val candidates = discovered?.get(root)
                val desiredUrl = candidates?.roomUrls?.firstOrNull()
                    ?: candidates?.groupUrls?.firstOrNull()
                val shouldReuse = cachedDoc != null && (
                    candidates == null ||
                        (!force && desiredUrl == cachedDoc.url)
                    )
                if (shouldReuse && cachedDoc != null) {
                    reused[cachedDoc.rootId] = cachedDoc
                } else {
                    rootsToFetch.add(root to candidates)
                }
            }

            val fetched = coroutineScope {
                rootsToFetch.map { (root, candidates) ->
                    async(Dispatchers.IO) {
                        root.id to runCatching {
                            client.fetchRootDoc(root, candidates ?: emptyCandidates(root), now)
                        }.getOrNull()
                    }
                }.awaitAll()
            }.toMap()

            // Assemble the final document set with a safe fallback per root.
            val docs = mutableListOf<SourceRoomDoc>()
            val incomplete = mutableListOf<String>()
            for (root in RoomSourceRoot.entries) {
                val fresh = fetched[root.id]
                val cachedDoc = cached.docs.firstOrNull { it.rootId == root.id }
                val candidates = discovered?.get(root)
                when {
                    fresh != null -> docs.add(fresh)
                    root.id in reused -> docs.add(reused[root.id]!!)
                    cachedDoc != null && candidates != null &&
                        (cachedDoc.url in candidates.roomUrls || cachedDoc.url in candidates.groupUrls) ->
                        docs.add(cachedDoc) // URL still published — cache remains valid

                    else -> incomplete.add(root.id)
                }
            }

            if (docs.isEmpty()) {
                _state.value = VacantRoomsState.Error(
                    "No department room timetable could be loaded right now"
                )
                return
            }

            val newCache = CachedRoomData(docs = docs, incompleteRoots = incomplete)
            cache = newCache
            withContext(Dispatchers.IO) { writeCache(newCache) }
            publishReady(
                refreshing = false,
                error = if (discovered == null) "Couldn’t reach the college timetables" else null
            )
        } catch (e: Exception) {
            val cached = cache
            if (cached == null || cached.docs.isEmpty()) {
                _state.value = VacantRoomsState.Error(
                    e.message ?: "Couldn’t load the room timetables"
                )
            } else {
                publishReady(refreshing = false, error = e.message)
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun emptyCandidates(root: RoomSourceRoot) =
        com.gndec.timetable.net.RootCandidates(root, roomUrls = emptyList(), groupUrls = emptyList())

    private fun latestFetchMillis(cached: CachedRoomData): Long =
        cached.docs.maxOfOrNull { it.fetchedAtMillis } ?: 0L

    private fun publishReady(refreshing: Boolean, error: String? = null) {
        val cached = cache ?: return
        if (cached.docs.isEmpty()) return
        val merged = RoomMerger.merge(cached.docs, cached.incompleteRoots)
        _state.value = VacantRoomsState.Ready(merged, refreshing, error)
    }

    private fun readCache() {
        cache = runCatching {
            if (!cacheFile.exists()) return
            json.decodeFromString<CachedRoomData>(cacheFile.readText())
        }.getOrNull()?.takeIf { it.docs.isNotEmpty() }
        if (cache != null) publishReady(refreshing = false)
    }

    private fun writeCache(data: CachedRoomData) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedRoomData.serializer(), data))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(json.encodeToString(CachedRoomData.serializer(), data))
                tmp.delete()
            }
        }
    }

    companion object {
        const val CACHE_FILE = "vacant_rooms_cache.json"

        /** Re-check every index after half a working day; discovery is cheap. */
        const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

        /** FET renders one row per teaching hour across every department file. */
        const val SLOT_MINUTES = 60

        fun slotEndMinutes(slotStartMinutes: Int): Int = slotStartMinutes + SLOT_MINUTES

        /**
         * Index of the day chip the screen should open on: today when it is a
         * teaching day (Mon–Fri), otherwise the first published weekday.
         */
        fun defaultDayIndex(today: LocalDate = LocalDate.now()): Int {
            val dow = today.dayOfWeek
            return if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) 0
            else dow.value - DayOfWeek.MONDAY.value
        }

        /**
         * Index of the slot chip to open on: the slot that contains "now"
         * during college hours, the first slot before classes start, the last
         * one after they end.
         */
        fun defaultSlotIndex(slotStarts: List<Int>, nowMillis: Long = System.currentTimeMillis()): Int {
            if (slotStarts.isEmpty()) return 0
            val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
            val nowMinutes = now.hour * 60 + now.minute
            var best = 0
            for (i in slotStarts.indices) {
                if (nowMinutes >= slotStarts[i]) best = i
            }
            if (nowMinutes < slotStarts.first()) return 0
            return best
        }

        fun isToday(dayIndex: Int, today: LocalDate = LocalDate.now()): Boolean {
            val dow = today.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
            return dayIndex == dow.value - DayOfWeek.MONDAY.value
        }

        /** True when [nowMinutes] falls inside the slot starting at [slotStart]. */
        fun isCurrentSlot(slotStart: Int, nowMinutes: Int): Boolean =
            nowMinutes in slotStart until slotStart + SLOT_MINUTES
    }
}

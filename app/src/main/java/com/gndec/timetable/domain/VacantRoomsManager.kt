package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.RoomTimetableClient
import com.gndec.timetable.parse.RoomTimetableData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** UI-facing state of the vacant rooms feature. */
sealed interface VacantRoomsState {
    /** No cached data yet — first load in progress. */
    data object Loading : VacantRoomsState

    /** Data available (from cache or network). [error] carries a non-fatal refresh failure. */
    data class Ready(
        val data: RoomTimetableData,
        val refreshing: Boolean,
        val error: String? = null
    ) : VacantRoomsState

    /** No cache and refresh failed. */
    data class Error(val message: String) : VacantRoomsState
}

/**
 * Keeps the weekly room timetable available: discovers the newest document published
 * on the college index, caches the parsed snapshot on disk, and serves it instantly
 * on subsequent opens while a background refresh runs.
 *
 * Refresh policy: a network download only happens when discovery reports a document URL
 * that differs from the cached one (the college publishes a NEW url every week and never
 * mutates a published file), or when the user forces a manual refresh.
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
    private var cachedData: RoomTimetableData? = null

    /**
     * Emits cached data immediately (once per process), then refreshes in the background
     * when the cache is missing or older than [FRESH_WINDOW_MS]. Safe to call on every
     * screen open; concurrent invocations are collapsed.
     */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        if (!loaded) {
            loaded = true
            withContext(Dispatchers.IO) { readCache() }
        }
        val cache = cachedData
        val freshEnough = cache != null &&
            System.currentTimeMillis() - cache.fetchedAtMillis < FRESH_WINDOW_MS
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
            val cache = cachedData
            if (!force && cache != null) {
                // Cheap discovery-only check: skip download when the weekly URL is unchanged.
                val discovered = withContext(Dispatchers.IO) {
                    runCatching { client.discoverRoomTimetableUrls().firstOrNull() }.getOrNull()
                }
                if (discovered == null || discovered == cache.sourceUrl) {
                    publishReady(refreshing = false)
                    return
                }
            }
            publishReady(refreshing = true)
            try {
                val data = client.fetchLatest(cachedUrl = cache?.sourceUrl)
                cachedData = data
                withContext(Dispatchers.IO) { writeCache(data) }
                publishReady(refreshing = false)
            } catch (e: Exception) {
                if (cachedData == null) {
                    _state.value = VacantRoomsState.Error(
                        e.message ?: "Couldn’t load the room timetable"
                    )
                } else {
                    publishReady(refreshing = false, error = e.message)
                }
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun publishReady(refreshing: Boolean, error: String? = null) {
        val cache = cachedData ?: return
        _state.value = VacantRoomsState.Ready(cache, refreshing, error)
    }

    private fun readCache() {
        cachedData = runCatching {
            if (!cacheFile.exists()) return
            json.decodeFromString<RoomTimetableData>(cacheFile.readText())
        }.getOrNull()?.takeIf { it.days.isNotEmpty() && it.slots.isNotEmpty() && it.rooms.isNotEmpty() }
        if (cachedData != null) publishReady(refreshing = false)
    }

    private fun writeCache(data: RoomTimetableData) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(RoomTimetableData.serializer(), data))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(json.encodeToString(RoomTimetableData.serializer(), data))
                tmp.delete()
            }
        }
    }

    companion object {
        const val CACHE_FILE = "vacant_rooms_cache.json"

        /** Re-check the index after half a working day; discovery is cheap either way. */
        const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

        /** Slot length in minutes — FET renders one row per teaching hour. */
        const val SLOT_MINUTES = 60

        fun slotStartMinutes(slot: String): Int? {
            val m = Regex("""(\d{1,2}):(\d{2})""").find(slot.trim()) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) return null
            return h * 60 + min
        }

        fun slotEndMinutes(slot: String): Int =
            (slotStartMinutes(slot) ?: 0) + SLOT_MINUTES

        /**
         * Index of the day chip the screen should open on: today when it is a teaching
         * day, otherwise the first published weekday (e.g. Monday on a weekend).
         */
        fun defaultDayIndex(days: List<String>, today: LocalDate = LocalDate.now()): Int {
            val label = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            val idx = days.indexOfFirst { it.equals(label, ignoreCase = true) }
            if (idx >= 0) return idx
            val teachingDay = days.indexOfFirst { label ->
                runCatching {
                    val dow = DayOfWeek.valueOf(label.trim().uppercase())
                    dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                }.getOrDefault(false)
            }
            return if (teachingDay >= 0) teachingDay else 0
        }

        /**
         * Index of the slot chip to open on: the slot that contains "now" during college
         * hours, the first slot before classes start, the last one after they end.
         */
        fun defaultSlotIndex(slots: List<String>, now: LocalTime = LocalTime.now()): Int {
            if (slots.isEmpty()) return 0
            val nowMinutes = now.hour * 60 + now.minute
            var best = 0
            for (i in slots.indices) {
                val start = slotStartMinutes(slots[i]) ?: continue
                if (nowMinutes >= start) best = i
            }
            val firstStart = slotStartMinutes(slots.first()) ?: 0
            if (nowMinutes < firstStart) return 0
            return best
        }

        fun isToday(days: List<String>, dayIndex: Int, today: LocalDate = LocalDate.now()): Boolean {
            val label = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            return days.getOrNull(dayIndex)?.equals(label, ignoreCase = true) == true
        }

        /** True when [nowMinutes] falls inside the slot that starts at [slotStartMinutes]. */
        fun isCurrentSlot(slot: String, now: LocalTime = LocalTime.now()): Boolean {
            val start = slotStartMinutes(slot) ?: return false
            val nowMinutes = now.hour * 60 + now.minute
            return nowMinutes in start until start + SLOT_MINUTES
        }
    }
}

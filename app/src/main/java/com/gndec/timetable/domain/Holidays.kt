package com.gndec.timetable.domain

import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.PyqRagClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Holiday(
    val id: String,
    val name: String,
    val date: String,
    val displayDate: String,
    val weekday: String,
    val category: String,
    val year: Int,
    val source: String = ""
)

class HolidayManager(
    private val settings: SettingsManager,
    private val backend: PyqRagClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val _holidays = MutableStateFlow<List<Holiday>>(emptyList())
    val holidays: StateFlow<List<Holiday>> = _holidays.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        _holidays.value = decode(settings.flow.first().holidayJson)
    }

    suspend fun refresh(forceRefresh: Boolean = false): List<Holiday> = mutex.withLock {
        _refreshing.value = true
        _lastError.value = null
        try {
            val configuredBackend = settings.flow.first().pyqRagBackendUrl.trim()
                .ifBlank { AppSettings.DEFAULT_PYQ_RAG_BACKEND_URL }
            val feed = backend.holidays(configuredBackend, forceRefresh = forceRefresh)
            val holidays = feed.holidays.map { item ->
                Holiday(
                    id = item.id,
                    name = item.name,
                    date = item.date,
                    displayDate = item.displayDate,
                    weekday = item.weekday,
                    category = item.category,
                    year = item.year,
                    source = item.source
                )
            }
            if (holidays.isEmpty()) error("No holidays found in the official list")
            _holidays.value = holidays
            settings.setHolidayCache(json.encodeToString(holidays), System.currentTimeMillis())
            holidays
        } catch (error: Exception) {
            _lastError.value = error.message ?: "Could not refresh holidays"
            _holidays.value
        } finally {
            _refreshing.value = false
        }
    }

    private fun decode(value: String): List<Holiday> = runCatching {
        if (value.isBlank()) emptyList() else json.decodeFromString<List<Holiday>>(value)
    }.getOrDefault(emptyList())
}

package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.PyqRagClient
import java.time.LocalDate
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
data class ErpNotice(
    val id: String,
    val title: String,
    val publishedDate: String,
    val displayDate: String,
    val url: String,
    val author: String = "",
    val source: String = "",
    val firstSeenAt: String = "",
    val bannerStartDate: String = "",
    val bannerUntilDate: String = ""
)

class ErpNoticeManager(
    private val context: Context,
    private val settings: SettingsManager,
    private val backend: PyqRagClient
) {
    companion object {
        const val NOTICE_URL = "https://erp.gndec.ac.in/notice"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val _notices = MutableStateFlow<List<ErpNotice>>(emptyList())
    val notices: StateFlow<List<ErpNotice>> = _notices.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        val cached = settings.flow.first()
        _notices.value = decode(cached.erpNoticeJson)
    }

    suspend fun refresh(forceRefresh: Boolean = false): List<ErpNotice> = mutex.withLock {
        _refreshing.value = true
        _lastError.value = null
        try {
            val configuredBackend = settings.flow.first().pyqRagBackendUrl.trim()
                .ifBlank { AppSettings.DEFAULT_PYQ_RAG_BACKEND_URL }
            val feed = backend.notices(configuredBackend, forceRefresh = forceRefresh)
            val notices = feed.notices.map { notice ->
                ErpNotice(
                    id = notice.id,
                    title = notice.title,
                    publishedDate = notice.publishedDate,
                    displayDate = notice.displayDate,
                    url = notice.url,
                    author = notice.author,
                    source = notice.source,
                    firstSeenAt = notice.firstSeenAt,
                    bannerStartDate = notice.bannerStartDate,
                    bannerUntilDate = notice.bannerUntilDate
                )
            }
            if (notices.isEmpty()) error("No notices found in the ERP response")
            _notices.value = notices
            settings.setErpNoticeCache(json.encodeToString(notices), System.currentTimeMillis())
            notices
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not refresh ERP notices"
            _notices.value
        } finally {
            _refreshing.value = false
        }
    }

    fun latestForToday(today: LocalDate = LocalDate.now()): ErpNotice? =
        _notices.value.firstOrNull { it.publishedDate == today.toString() }

    private fun decode(value: String): List<ErpNotice> = runCatching {
        if (value.isBlank()) emptyList() else json.decodeFromString<List<ErpNotice>>(value)
    }.getOrDefault(emptyList())
}

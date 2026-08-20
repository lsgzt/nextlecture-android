package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.Net
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import okhttp3.Request
import org.jsoup.Jsoup

@Serializable
data class ErpNotice(
    val id: String,
    val title: String,
    val publishedDate: String,
    val displayDate: String,
    val url: String,
    val author: String = ""
)

class ErpNoticeManager(
    private val context: Context,
    private val settings: SettingsManager
) {
    companion object {
        const val NOTICE_URL = "https://erp.gndec.ac.in/notice"
        private const val MAX_NOTICES = 10
        private val datePattern = Regex(
            "(?i)(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}"
        )
        private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH)
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

    suspend fun refresh(): List<ErpNotice> = mutex.withLock {
        _refreshing.value = true
        _lastError.value = null
        try {
            val request = Request.Builder()
                .url(NOTICE_URL)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "GNDEC-Timetable/1.8")
                .get()
                .build()
            val notices = withContext(Dispatchers.IO) {
                Net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("ERP returned HTTP ${response.code}")
                    val body = response.body?.string() ?: error("ERP returned an empty response")
                    parse(body)
                }
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

    private fun parse(html: String): List<ErpNotice> {
        val document = Jsoup.parse(html, NOTICE_URL)
        val result = document.selectFirst("div.website-list div.result") ?: return emptyList()
        return result.children()
            .mapNotNull { card ->
                val anchor = card.selectFirst("a[href*=noticeboard/]") ?: return@mapNotNull null
                val title = anchor.text().trim().replace(Regex("\\s+"), " ")
                val href = anchor.absUrl("href").ifBlank { NOTICE_URL.trimEnd('/') + "/" + anchor.attr("href").trimStart('/') }
                val dateMatch = datePattern.find(card.text()) ?: return@mapNotNull null
                val displayDate = dateMatch.value.replace(Regex("\\s+"), " ")
                val parsedDate = runCatching { LocalDate.parse(displayDate, dateFormatter) }.getOrNull() ?: return@mapNotNull null
                val author = card.selectFirst("p")?.text()?.trim()?.removeSuffix(displayDate)?.trim().orEmpty()
                ErpNotice(
                    id = href.substringAfter("/noticeboard/").ifBlank { href },
                    title = title,
                    publishedDate = parsedDate.toString(),
                    displayDate = displayDate,
                    url = href,
                    author = author
                )
            }
            .distinctBy { it.id }
            .take(MAX_NOTICES)
    }

    private fun decode(value: String): List<ErpNotice> = runCatching {
        if (value.isBlank()) emptyList() else json.decodeFromString<List<ErpNotice>>(value)
    }.getOrDefault(emptyList())
}

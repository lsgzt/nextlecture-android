package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Resolves the live sub-section timetable without requiring an APK rebuild for each dated URL. */
class TimetableSourceResolver(
    private val pyqRagClient: PyqRagClient,
    private val client: OkHttpClient = Net.client
) {
    data class Resolution(val url: String, val source: String)

    suspend fun resolve(serverFallbackBaseUrl: String, localFallbackUrl: String): Resolution = withContext(Dispatchers.IO) {
        val discoveredUrl = runCatching { discoverFromOfficialIndex() }.getOrNull()
        if (discoveredUrl != null) return@withContext Resolution(discoveredUrl, "official-index")

        val serverUrl = runCatching {
            pyqRagClient.timetableSource(serverFallbackBaseUrl).url
        }.getOrNull()
        if (serverUrl != null && isAllowedTimetableUrl(serverUrl)) {
            return@withContext Resolution(serverUrl, "server-config")
        }

        if (isAllowedTimetableUrl(localFallbackUrl)) {
            return@withContext Resolution(localFallbackUrl, "local-fallback")
        }
        return@withContext Resolution(CURRENT_FALLBACK_URL, "built-in-fallback")
    }

    private fun discoverFromOfficialIndex(): String? {
        val request = Request.Builder().url(OFFICIAL_TIMETABLE_INDEX_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return null
            val document = Jsoup.parse(html, OFFICIAL_TIMETABLE_INDEX_URL)
            return document.select("a[href]")
                .firstOrNull { anchor ->
                    val label = anchor.text().replace('\u00A0', ' ').trim().lowercase()
                    (label.contains("sub-section wise") || label.contains("subsection wise")) &&
                        isAllowedTimetableUrl(anchor.absUrl("href"))
                }
                ?.absUrl("href")
                ?.takeIf(::isAllowedTimetableUrl)
        }
    }

    companion object {
        const val OFFICIAL_TIMETABLE_INDEX_URL = "https://appsc.gndec.ac.in/time_tables"
        const val CURRENT_FALLBACK_URL = "https://appsc.gndec.ac.in/sites/default/files/2026-08/23_08_2026%20FINAL_FILE%20R4_subgroups_days_horizontal.html"

        fun isAllowedTimetableUrl(raw: String): Boolean {
            val parsed = raw.trim().toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" &&
                parsed.host == "appsc.gndec.ac.in" &&
                parsed.encodedPath.endsWith(".html", ignoreCase = true)
        }
    }
}

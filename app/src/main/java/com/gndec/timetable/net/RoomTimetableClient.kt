package com.gndec.timetable.net

import com.gndec.timetable.parse.ParseException
import com.gndec.timetable.parse.RoomTimetableParser
import com.gndec.timetable.parse.RoomTimetableData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Discovers and downloads the college's weekly "Room Time Table" document.
 *
 * The published URL changes every week, so the live index page
 * (https://appsc.gndec.ac.in/time_tables) is fetched first and the FIRST anchor whose
 * label contains "room time table" is used — the college lists the newest file on top.
 * Older anchors are kept as ordered fallbacks in case the newest file is malformed.
 */
class RoomTimetableClient(private val client: OkHttpClient = Net.client) {

    /** All room-timetable document URLs in the order published on the index page. */
    fun discoverRoomTimetableUrls(): List<String> {
        val request = Request.Builder().url(INDEX_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            val document = Jsoup.parse(html, INDEX_URL)
            return document.select("a[href]")
                .filter { anchor ->
                    val label = anchor.text().replace('\u00A0', ' ').trim().lowercase()
                    label.contains("room time table")
                }
                .mapNotNull { anchor ->
                    val abs = anchor.absUrl("href")
                    abs.takeIf(::isAllowedRoomUrl)
                }
        }
    }

    /**
     * Full pipeline: discover the newest document, download and parse it, falling back
     * to older published documents if the newest cannot be parsed.
     *
     * @param cachedUrl the sourceUrl of the currently cached data, preferred for
     *   re-download when discovery fails but the previously used document is still known.
     * @throws ParseException / [java.io.IOException] when nothing could be fetched.
     */
    suspend fun fetchLatest(cachedUrl: String? = null): RoomTimetableData = withContext(Dispatchers.IO) {
        val discovered = runCatching { discoverRoomTimetableUrls() }.getOrDefault(emptyList())
        val candidates = buildList {
            discovered.firstOrNull()?.let { add(it) }
            if (cachedUrl != null && cachedUrl !in this) add(cachedUrl)
            discovered.drop(1).forEach { if (it !in this) add(it) }
        }
        if (candidates.isEmpty()) throw ParseException("no room time table link found on the index page")

        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val html = download(url)
                val parsed = RoomTimetableParser.parse(html, url, System.currentTimeMillis())
                return@withContext parsed
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ParseException("room timetable unavailable")
    }

    private fun download(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "room timetable download failed")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw HttpException(response.code, "empty room timetable body")
            return body
        }
    }

    companion object {
        const val INDEX_URL = "https://appsc.gndec.ac.in/time_tables"

        fun isAllowedRoomUrl(raw: String): Boolean {
            val parsed = raw.trim().toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" &&
                parsed.host == "appsc.gndec.ac.in" &&
                parsed.encodedPath.endsWith(".html", ignoreCase = true)
        }
    }
}

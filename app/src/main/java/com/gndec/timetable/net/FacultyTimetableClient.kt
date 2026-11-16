package com.gndec.timetable.net

import com.gndec.timetable.parse.FacultyTimetableParser
import com.gndec.timetable.parse.ParsedFacultyDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * Discovers and downloads each department's CURRENT teacher timetable.
 *
 * No timetable file URL is ever hardcoded — the college republishes every
 * revision (roughly weekly) under a brand-new URL. The only stable URLs are the
 * department INDEX pages (reused from [RoomSourceRoot], the same pages the
 * vacant-rooms feature crawls). Discovery walks an index page's anchors and
 * classifies each one:
 *
 *  1. Filename evidence is AUTHORITATIVE. A path containing "teacher(s)" is a
 *     teacher document ("..._teachers_days_horizontal.html", "Teachers
 *     TT_1.html", "teachers individual tt_2.html"); a path naming groups/
 *     years/rooms/classes/subjects is NOT, no matter what its label says — ME
 *     publishes one combined block labelled "Revised Time table (Class) …
 *     (Teacher) … (Room)" whose href actually points at the GROUPS file.
 *  2. "free_period" (CE's "Faculty's Free Period") and "combined" (ECE's
 *     "Teachers combined Time Table") files are excluded.
 *  3. Only same-host .html documents qualify.
 *  4. When the filename is uninformative, label evidence ("Teacher Time
 *     Table", "Teaches Timetable", "Faculty Time Table") decides.
 *
 * GNDEC lists the newest revision first, so candidate order is page order. Each
 * downloaded document must parse AND carry a FET generation timestamp inside
 * the current session window (Jun–Dec vs Jan–May) — stale revisions are skipped
 * in favour of the next candidate.
 */
class FacultyTimetableClient(private val client: OkHttpClient = Net.client) {

    /** Teacher-timetable candidates for one root, newest-published-first. */
    data class TeacherCandidates(val root: RoomSourceRoot, val urls: List<String>)

    /** Crawl one root's index page and classify its anchors. */
    fun discoverTeacherCandidates(root: RoomSourceRoot): TeacherCandidates {
        val urls = mutableListOf<String>()
        val document = runCatching {
            val request = Request.Builder().url(root.indexUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return TeacherCandidates(root, urls)
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return TeacherCandidates(root, urls)
                Jsoup.parse(body, root.indexUrl)
            }
        }.getOrNull() ?: return TeacherCandidates(root, urls)

        val seen = mutableSetOf<String>()
        for (anchor in document.select("a[href]")) {
            val href = anchor.absUrl("href").trim()
            if (href.isEmpty() || !seen.add(href)) continue
            val label = anchor.text().replace('\u00A0', ' ').trim()
            if (isTeacherAnchor(label, href, root)) urls.add(href)
        }
        return TeacherCandidates(root, urls)
    }

    /**
     * Fetches one root's current teacher document: tries candidates in page
     * order and returns the first that downloads, parses and is
     * session-current.
     *
     * @return null when this root has no usable current document.
     */
    suspend fun fetchTeacherDoc(
        root: RoomSourceRoot,
        candidates: TeacherCandidates,
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedFacultyDoc? = withContext(Dispatchers.IO) {
        val fetchedAt = System.currentTimeMillis()
        for (url in candidates.urls.take(MAX_CANDIDATES)) {
            try {
                val html = download(url)
                val parsed = FacultyTimetableParser.parse(html, root.id, url, fetchedAt)
                if (!RoomTimetableClient.isCurrentSession(parsed.generatedAtMillis, nowMillis)) continue
                return@withContext parsed
            } catch (_: Exception) {
                // try the next published revision
            }
        }
        null
    }

    internal fun isTeacherAnchor(label: String, href: String, root: RoomSourceRoot): Boolean {
        if (!RoomTimetableClient.isCandidateUrl(href, root)) return false
        val path = decodePath(href)
        if (path.contains("free_period") || path.contains("combined")) return false
        // 1) filename evidence (authoritative)
        if (path.contains("teachers") || path.contains("teacher")) return true
        if (listOf("groups", "years", "rooms", "subgroups", "subjects", "classes", "class").any { path.contains(it) }) {
            return false
        }
        // 2) label evidence — only when the filename says nothing
        val l = label.lowercase()
        if (l.contains("teacher") || l.contains("teaches")) return true
        if (l.contains("faculty") && (l.contains("time table") || l.contains("timetable"))) return true
        return false
    }

    private fun decodePath(href: String): String =
        runCatching {
            val parsed = href.toHttpUrlOrNull() ?: return href.lowercase()
            URLDecoder.decode(parsed.encodedPath, "UTF-8").lowercase()
        }.getOrDefault(href.lowercase())

    private fun download(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "faculty timetable download failed")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw HttpException(response.code, "empty faculty timetable body")
            return body
        }
    }

    companion object {
        private const val MAX_CANDIDATES = 3
    }
}

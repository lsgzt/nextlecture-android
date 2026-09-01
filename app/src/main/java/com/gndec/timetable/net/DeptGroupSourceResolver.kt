package com.gndec.timetable.net

import com.gndec.timetable.parse.GroupMatcher
import com.gndec.timetable.parse.ParseException
import com.gndec.timetable.parse.RoomTimetableParser
import com.gndec.timetable.parse.TimetableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the OFFICIAL departmental student timetable document for a
 * 2nd/3rd/4th-year B.Tech student. Discovery is always live:
 *
 *   department index page (?q=node/5) → student/class/group anchors in page
 *   order (newest revision first) → download → FET-session check → full parse
 *   via [TimetableParser] → verify the student's year+branch groups exist.
 *
 * No dated file URL is ever hardcoded; when the college republishes under a
 * new name the next refresh simply picks the new document. Reuses the
 * [RoomTimetableClient] discovery primitives (same index pages, same anchor
 * classification, same session-window logic) instead of a second crawler.
 *
 * Correctness rules:
 *  - a candidate that fails to download, parse or validate is SKIPPED and the
 *    next older revision is tried;
 *  - if no candidate validates, the previously known URL (if any) is returned
 *    so the cached timetable is never replaced by a worse document;
 *  - with no previous URL the resolution FAILS loudly (the caller must show an
 *    honest error instead of silently falling back to the 1st-year document).
 */
class DeptGroupSourceResolver(private val client: OkHttpClient = Net.client) {

    data class Resolution(
        val url: String,
        /** "official-index" | "validated-new" | "cached-url" — provenance for tests/logs. */
        val source: String,
        val generatedAtMillis: Long? = null
    )

    /** A validated, fully downloaded document with its parsed group names. */
    data class DeptDoc(
        val url: String,
        val groups: List<String>,
        val generatedAtMillis: Long?
    )

    private val roomClient = RoomTimetableClient(client)

    /** B.Tech branch → official departmental discovery root. */
    fun rootFor(branch: String): RoomSourceRoot? = when (branch.trim().uppercase()) {
        "CS" -> RoomSourceRoot.CSE
        "EC" -> RoomSourceRoot.ECE
        "EE" -> RoomSourceRoot.EE
        "CE" -> RoomSourceRoot.CE
        "IT" -> RoomSourceRoot.IT
        "ME", "RAI" -> RoomSourceRoot.ME
        else -> null
    }

    /**
     * Cheap refresh path: when the newest discovered candidate equals
     * [currentUrl] nothing is downloaded (the fetcher's conditional GET takes
     * over); otherwise the new document is downloaded and validated BEFORE it
     * may replace the cached one.
     */
    suspend fun resolve(
        branch: String,
        year: Int,
        currentUrl: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Resolution? = withContext(Dispatchers.IO) {
        val root = rootFor(branch) ?: return@withContext null
        val candidates = runCatching { roomClient.discoverRoot(root) }.getOrNull()
            ?: return@withContext trustedCachedUrl(currentUrl, root)?.let { Resolution(it, "cached-url") }
        val newest = candidates.groupUrls.firstOrNull()

        if (newest != null && newest == currentUrl) {
            return@withContext Resolution(newest, "official-index")
        }

        for (url in candidates.groupUrls.take(MAX_CANDIDATES)) {
            val doc = downloadAndValidate(url, branch, year, nowMillis)
            if (doc != null) {
                return@withContext Resolution(doc.url, "validated-new", doc.generatedAtMillis)
            }
        }
        // Nothing newer validated — keep serving the cached document, but ONLY
        // when it belongs to the same department. A profile migrated from the
        // 1st-year source carries an appsc.gndec.ac.in URL here; re-serving it
        // would refresh the WRONG timetable and claim success.
        trustedCachedUrl(currentUrl, root)?.let { Resolution(it, "cached-url") }
    }

    /**
     * Full catalog load for the onboarding/profile group picker: always
     * downloads and parses the newest valid document, returning its group
     * names (no cached-URL shortcut — the user is actively choosing).
     */
    suspend fun loadDoc(
        branch: String,
        year: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DeptDoc? = withContext(Dispatchers.IO) {
        val root = rootFor(branch) ?: return@withContext null
        val candidates = runCatching { roomClient.discoverRoot(root) }.getOrNull() ?: return@withContext null
        for (url in candidates.groupUrls.take(MAX_CANDIDATES)) {
            val doc = downloadAndValidate(url, branch, year, nowMillis)
            if (doc != null) return@withContext doc
        }
        null
    }

    /** Cached URLs are only reusable for the SAME department's host (never appsc). */
    private fun trustedCachedUrl(currentUrl: String?, root: RoomSourceRoot): String? =
        currentUrl?.takeIf {
            it.toHttpUrlOrNull()?.host?.equals(root.host, ignoreCase = true) == true
        }

    /** Download + parse + session-window + year/branch group verification. */
    private fun downloadAndValidate(url: String, branch: String, year: Int, nowMillis: Long): DeptDoc? {
        return try {
            val html = download(url)
            val generatedAt = RoomTimetableParser.parseGeneratedAtMillis(html)
            if (!RoomTimetableClient.isCurrentSession(generatedAt, nowMillis)) return null
            val groups = TimetableParser.parse(html).keys.toList()
            if (groups.isEmpty()) return null
            if (!GroupMatcher.hasGroupsFor(groups, branch.trim().uppercase(), year)) return null
            DeptDoc(url, groups, generatedAt)
        } catch (_: ParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun download(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "timetable download failed")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw HttpException(response.code, "empty timetable body")
            return body
        }
    }

    companion object {
        private const val MAX_CANDIDATES = 4
    }
}

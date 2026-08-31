package com.gndec.timetable.net

import com.gndec.timetable.parse.ParseException
import com.gndec.timetable.parse.RoomTimetableParser
import com.gndec.timetable.parse.SourceKind
import com.gndec.timetable.parse.SourceRoomDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Official page whose "Time Tables" listing is the discovery root for one
 * department's (or the college-wide) published timetables. These are INDEX
 * pages, never hardcoded timetable files — the college republishes every
 * revision under new URLs.
 */
enum class RoomSourceRoot(
    val id: String,
    val indexUrl: String,
    val host: String
) {
    /** College-wide file maintained by the Applied Sciences department. */
    APPSC("appsc", "https://appsc.gndec.ac.in/time_tables", "appsc.gndec.ac.in"),
    CSE("cse", "https://cse.gndec.ac.in/?q=node/5", "cse.gndec.ac.in"),
    ECE("ece", "https://ece.gndec.ac.in/?q=node/5", "ece.gndec.ac.in"),
    EE("ee", "https://ee.gndec.ac.in/?q=node/5", "ee.gndec.ac.in"),

    /** Mechanical & Production publishes no rooms export — groups fallback. */
    ME("me", "https://me.gndec.ac.in/?q=node/5", "me.gndec.ac.in"),
    CE("ce", "https://ce.gndec.ac.in/?q=node/5", "ce.gndec.ac.in"),
    IT("it", "https://it.gndec.ac.in/?q=node/5", "it.gndec.ac.in"),
    MCA("mca", "https://mca.gndec.ac.in/?q=node/5", "mca.gndec.ac.in"),
    MBA("mba", "https://mba.gndec.ac.in/?q=node/5", "mba.gndec.ac.in")
}

/** Candidate document URLs discovered on one root's index page. */
data class RootCandidates(
    val root: RoomSourceRoot,
    /** Room-timetable documents, in the page's published order (newest first). */
    val roomUrls: List<String>,
    /** Group timetables used ONLY as fallback (rooms extracted from cells). */
    val groupUrls: List<String>
)

/**
 * Discovers and downloads every department's current room timetable.
 *
 * Discovery: each root's index page is fetched and its anchors inspected; an
 * anchor becomes a ROOMS candidate when its label or target references rooms
 * ("Room Time Table", "Class Rooms", "Rooms [w.e.f …]", "Room Wise TimeTable",
 * …rooms_days_horizontal.html) and points at an .html file on the same host.
 * GNDEC lists the newest revision on top, so candidates keep page order; older
 * revisions remain as fallbacks. If a root has no rooms export, its group
 * timetable ("(Class)" / groups_days_horizontal) becomes a GROUPS_CELLS
 * fallback — room occupancy is read from the cells of group tables.
 *
 * Staleness: each document embeds its FET generation timestamp ("Timetable
 * generated with FET 7.6.4 on 8/30/26 10:39 PM"). A document is accepted only
 * when it was generated inside the CURRENT semester window (Jun–Dec vs Jan–May
 * of the same calendar year). A department that has not published a current
 * revision contributes nothing — stale data must never pollute the vacancy
 * list (correctness over coverage).
 */
class RoomTimetableClient(private val client: OkHttpClient = Net.client) {

    /** Discovers candidate URLs for every root (index pages in parallel). */
    suspend fun discoverAll(nowMillis: Long = System.currentTimeMillis()): Map<RoomSourceRoot, RootCandidates> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                RoomSourceRoot.entries.map { root ->
                    async { root to discoverRoot(root) }
                }.awaitAll().toMap()
            }
        }

    /** Index-page crawl for one root. */
    fun discoverRoot(root: RoomSourceRoot): RootCandidates {
        val roomUrls = mutableListOf<String>()
        val groupUrls = mutableListOf<String>()
        val document = runCatching {
            val request = Request.Builder().url(root.indexUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return RootCandidates(root, roomUrls, groupUrls)
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return RootCandidates(root, roomUrls, groupUrls)
                org.jsoup.Jsoup.parse(body, root.indexUrl)
            }
        }.getOrNull() ?: return RootCandidates(root, roomUrls, groupUrls)

        val seen = mutableSetOf<String>()
        for (anchor in document.select("a[href]")) {
            val href = anchor.absUrl("href").trim()
            if (href.isEmpty() || !seen.add(href)) continue
            val label = anchor.text().replace('\u00A0', ' ').trim()
            val classification = classifyAnchor(label, href, root)
            when (classification) {
                AnchorKind.ROOMS -> roomUrls.add(href)
                AnchorKind.GROUPS -> groupUrls.add(href)
                AnchorKind.NONE -> Unit
            }
        }
        return RootCandidates(root, roomUrls, groupUrls)
    }

    internal enum class AnchorKind { ROOMS, GROUPS, NONE }

    /**
     * Pure anchor classification used by [discoverRoot] — a ROOMS candidate
     * references rooms in its label or target ("Room Time Table", "Class
     * Rooms", "Rooms [w.e.f …]", "…/rooms_days_horizontal.html"); a GROUPS
     * candidate is the department's class/group timetable used as fallback.
     */
    internal fun classifyAnchor(label: String, href: String, root: RoomSourceRoot): AnchorKind {
        if (!isCandidateUrl(href, root)) return AnchorKind.NONE
        val path = href.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        val isRooms = label.contains("room", ignoreCase = true) ||
            path.contains("rooms") || path.contains("room_")
        if (isRooms) return AnchorKind.ROOMS
        val isGroups = path.contains("groups_days_horizontal") ||
            listOf("class", "student", "group").any { label.lowercase().contains(it) }
        return if (isGroups) AnchorKind.GROUPS else AnchorKind.NONE
    }

    /**
     * Fetches one root's current document: newest rooms candidate first, then
     * older ones, then the groups fallback. Rejects documents that fail to
     * parse or were generated outside the current semester window.
     *
     * @return null when this root has no usable current document.
     */
    suspend fun fetchRootDoc(
        root: RoomSourceRoot,
        candidates: RootCandidates,
        nowMillis: Long = System.currentTimeMillis()
    ): SourceRoomDoc? = withContext(Dispatchers.IO) {
        val fetchedAt = System.currentTimeMillis()
        val attempts = candidates.roomUrls.take(MAX_CANDIDATES) + candidates.groupUrls.take(MAX_CANDIDATES)
        for (url in attempts) {
            val isGroups = url !in candidates.roomUrls
            try {
                val html = download(url)
                val parsed = if (isGroups) {
                    RoomTimetableParser.parseGroupsDoc(html, root.id, url, fetchedAt)
                } else {
                    RoomTimetableParser.parseRoomsDoc(html, root.id, url, fetchedAt)
                }
                if (!isCurrentSession(parsed.generatedAtMillis, nowMillis)) continue
                return@withContext SourceRoomDoc(
                    rootId = root.id,
                    kind = if (isGroups) SourceKind.GROUPS_CELLS else SourceKind.ROOMS,
                    url = url,
                    slotStarts = parsed.slotStarts,
                    rooms = parsed.rooms,
                    generatedAtMillis = parsed.generatedAtMillis,
                    fetchedAtMillis = fetchedAt
                )
            } catch (_: Exception) {
                // Try the next published revision / the groups fallback.
            }
        }
        null
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
        private const val MAX_CANDIDATES = 3

        /** Only same-host .html documents are timetable candidates (excludes PDFs). */
        fun isCandidateUrl(raw: String, root: RoomSourceRoot): Boolean {
            val parsed = raw.trim().toHttpUrlOrNull() ?: return false
            return (parsed.scheme == "https" || parsed.scheme == "http") &&
                parsed.host.equals(root.host, ignoreCase = true) &&
                parsed.encodedPath.endsWith(".html", ignoreCase = true)
        }

        /**
         * FET session window: files generated for the current half of the
         * calendar year (Jan–May or Jun–Dec). A March 2026 file is stale in
         * August 2026 but current in April 2026.
         */
        fun isCurrentSession(generatedAtMillis: Long?, nowMillis: Long): Boolean {
            if (generatedAtMillis == null) return true // nothing to disprove currency
            val zone = ZoneId.systemDefault()
            val generated = Instant.ofEpochMilli(generatedAtMillis).atZone(zone).toLocalDate()
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            if (generated.isAfter(now)) return true // future-dated file — accept
            return sessionWindow(generated) == sessionWindow(now)
        }

        private fun sessionWindow(date: LocalDate): Pair<Int, Int> =
            if (date.monthValue in 6..12) date.year to 2 else date.year to 1
    }
}

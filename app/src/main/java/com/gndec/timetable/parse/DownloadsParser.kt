package com.gndec.timetable.parse

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One downloadable form / document row taken from the college Downloads page. */
@Serializable
data class DownloadLink(
    val title: String,
    val url: String,
    val sizeLabel: String = "",
    val isFile: Boolean = false
)

/**
 * Parses the GNDEC "Downloads" page (https://gndec.ac.in/?q=node/85), a Drupal
 * node whose content region holds the actual downloads: a run of `<p>` links
 * followed by an attachments table with file sizes. Site-chrome links (menus,
 * sidebars) live outside that region and are ignored.
 *
 * The output is sorted by student usefulness: bus pass and vehicle/gate pass
 * forms first, then ID cards, bonafide, degree, no-dues, railway concession,
 * migration, scholarships, and so on, with staff/faculty-only forms demoted.
 * Sorting is stable, so links with equal priority keep the page's own order.
 */
object DownloadsParser {

    const val SOURCE_URL = "https://gndec.ac.in/?q=node/85"
    private const val BASE_URI = "https://gndec.ac.in/"

    private val FILE_EXTENSIONS = listOf(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".rar", ".jpg", ".jpeg", ".png", ".txt", ".csv", ".html"
    )

    /**
     * Priority rules, most important first. The first rule whose keyword is
     * contained in the link title (case-insensitive) decides the score; the
     * [STAFF_DEMOTION] then pushes staff-only variants below student ones.
     */
    private val PRIORITY_RULES: List<Pair<String, Int>> = listOf(
        "bus pass" to 100,
        "gate pass" to 95,
        "vehicle" to 95,
        "identity card" to 90,
        "id card" to 90,
        "bonafide" to 85,
        "degree" to 80,
        "no-dues" to 80,
        "no dues" to 80,
        "railway" to 75,
        "migration" to 70,
        "scholarship" to 70,
        "re-admission" to 65,
        "readmission" to 65,
        "detainee" to 65,
        "hostel" to 60,
        "library" to 55,
        "marks card" to 50,
        "dmc" to 50,
        "gazette" to 45,
        "medical" to 40,
        "result" to 40,
        "undertaking" to 35,
        "transport" to 30,
        "auditorium" to 25,
        "mentoring" to 20,
        "proforma" to 20,
        "ta/da" to 20,
        "honorarium" to 20,
        "covering letter" to 20,
        "fire safety" to 15
    )

    /** Staff-only variants of otherwise important forms rank below student ones. */
    private val STAFF_DEMOTION = 15
    private val STAFF_KEYWORDS = listOf("staff", "faculty", "external examiner")

    /** Score at or above this marks the "most asked for" links in the UI. */
    const val TOP_SCORE = 85

    /** Importance score of a link title; higher = closer to the top of the list. */
    fun priority(title: String): Int {
        val t = title.lowercase()
        var score = PRIORITY_RULES.firstOrNull { t.contains(it.first) }?.second ?: 0
        if (STAFF_KEYWORDS.any { t.contains(it) }) score -= STAFF_DEMOTION
        return score
    }

    /**
     * Extracts every download link from the page HTML. Duplicate URLs (a form
     * listed both in the paragraphs and the attachments table) collapse into
     * one entry, keeping the table's file-size label when available.
     */
    fun parse(html: String, baseUri: String = BASE_URI): List<DownloadLink> {
        val doc: Document = Jsoup.parse(html, baseUri)
        val region = doc.selectFirst("#content-content")
            ?: doc.selectFirst("div[id^=node-]")
            ?: doc.body()
            ?: return emptyList()

        // Attachment table: second cell of each row carries the file size.
        val sizes = mutableMapOf<String, String>()
        for (row in region.select("table#attachments tr")) {
            val cells = row.select("td")
            val anchor = cells.firstOrNull()?.selectFirst("a[href]") ?: continue
            val href = absoluteHref(anchor) ?: continue
            if (cells.size >= 2) {
                val size = cells[1].text().replace('\u00A0', ' ').trim()
                if (size.isNotBlank()) sizes[href] = size
            }
        }

        val byUrl = LinkedHashMap<String, DownloadLink>()
        for (anchor in region.select("a[href]")) {
            val href = absoluteHref(anchor) ?: continue
            if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:")) continue
            if (href.contains("/print/") || href.contains("/printmail/")) continue
            var title = anchor.text().replace('\u00A0', ' ').trim()
            if (title.length < 3) continue

            val size = sizes[href].orEmpty()
            val isFile = FILE_EXTENSIONS.any { href.substringBefore('?').lowercase().endsWith(it) }
            val link = DownloadLink(title = title, url = href, sizeLabel = size, isFile = isFile)
            val existing = byUrl[href]
            if (existing == null) {
                byUrl[href] = link
            } else if (existing.sizeLabel.isBlank() && size.isNotBlank()) {
                byUrl[href] = existing.copy(sizeLabel = size)
            }
        }

        return byUrl.values.sortedByDescending { priority(it.title) }
    }

    /** Absolute URL for an anchor, resolving the college's relative hrefs. */
    private fun absoluteHref(anchor: Element): String? {
        val raw = anchor.attr("href").trim()
        if (raw.isBlank()) return null
        return anchor.absUrl("href").ifBlank { raw }
    }
}

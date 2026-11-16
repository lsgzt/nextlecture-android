package com.gndec.timetable.parse

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * One of the seven department notice sources. Six department sites (CSE, IT,
 * ECE, EE, ME, CE) are classic Drupal pages whose left sidebar carries a
 * "News and Events" (ME: "Notice Board") block with a hand-maintained list of
 * PDF/image links. Applied Sciences publishes a proper notice-board view at
 * /notice-board with dated rows.
 *
 * [chipLabel] is the short name shown on the Notice tab's source selector;
 * [url] is both the fetch target and the base URI for resolving relative hrefs.
 */
enum class DeptSite(
    val key: String,
    val label: String,
    val chipLabel: String,
    val url: String
) {
    APPLIED("appsc", "Applied Sciences", "Applied Sciences", "https://appsc.gndec.ac.in/notice-board"),
    CSE("cse", "Computer Science & Engineering", "CSE", "https://cse.gndec.ac.in/"),
    IT("it", "Information Technology", "IT", "https://it.gndec.ac.in/"),
    ECE("ece", "Electronics & Communication", "ECE", "https://ece.gndec.ac.in/"),
    EE("ee", "Electrical Engineering", "EE", "https://ee.gndec.ac.in/"),
    ME("me", "Mechanical Engineering", "ME", "https://me.gndec.ac.in/"),
    CE("ce", "Civil Engineering", "CE", "https://ce.gndec.ac.in/");

    /** Host of [url], e.g. "cse.gndec.ac.in" — used in refresh/failure messages. */
    val host: String
        get() = url.substringAfter("://").substringBefore('/').trim()

    companion object {
        fun forKey(key: String?): DeptSite? = values().firstOrNull { it.key == key }
    }
}

/** One notice row taken from a department site's news/notice list. */
@Serializable
data class DeptNotice(
    val id: String,
    val title: String,
    val url: String,
    val dept: String,
    /** ISO yyyy-MM-dd when the source publishes dates (Applied Sciences' board). */
    val date: String = "",
    /** True when the site still marks the row with its "new" (newi.gif) bullet. */
    val isNew: Boolean = false
)

/**
 * Picks the department whose notices belong on a student's Notice tab.
 *
 * The saved profile branch (StudentDirectoryManager.BRANCHES codes) is the
 * primary signal; the selected timetable group backs it up when no profile
 * branch was saved. Every first-year student sits in the Applied Sciences
 * department regardless of admission branch, so year 1 always resolves to
 * [DeptSite.APPLIED] — from the saved academic year, or from the group's
 * leading "D1" token when the year was never recorded.
 */
object DeptSites {

    fun resolve(branch: String?, academicYear: Int, group: String?): DeptSite? {
        if (academicYear == 1) return DeptSite.APPLIED
        val parsedGroup = group?.trim()?.takeIf { it.isNotBlank() }?.let { GroupMatcher.parseGroup(it) }
        if (parsedGroup?.year == 1) return DeptSite.APPLIED
        val code = branch?.trim()?.uppercase().orEmpty().ifBlank { parsedGroup?.branch.orEmpty() }
        return siteForBranch(code)
    }

    private fun siteForBranch(code: String): DeptSite? = when (code) {
        "CS", "CSE" -> DeptSite.CSE
        "IT" -> DeptSite.IT
        "EC", "ECE" -> DeptSite.ECE
        "EE" -> DeptSite.EE
        "ME" -> DeptSite.ME
        "CE" -> DeptSite.CE
        // R.A.I. groups are published inside Mechanical's timetable documents
        // and the college runs no separate robotics notice site.
        "RAI" -> DeptSite.ME
        else -> null
    }
}

/**
 * Extracts the notice rows from a department page. Two shapes exist in the
 * wild, both handled here:
 *
 *  - Drupal 6 department homepages: the sidebar's `#block-block-2` ("News and
 *    Events" / "Notice Board") whose content holds a run of anchors — `<p>`,
 *    `<li>` or bare — most pointing at files under /sites/default/files.
 *    No dates; page order is the department's own newest-first curation.
 *    Some anchors are preceded by a `<sup><img newi.gif>` "new" bullet.
 *
 *  - Applied Sciences' Drupal 8 notice-board: `.views-row` items, each with a
 *    `time[datetime]` stamp and one title anchor. The sidebar renders the same
 *    view again, so URLs are deduped; rows are then ordered newest-first.
 *
 * Anything outside those regions (menus, HOD block, newsletter tables, the
 * "more" link) is ignored. Only absolute, non-chrome http(s) links survive.
 */
object DeptNoticesParser {

    fun parse(html: String, site: DeptSite): List<DeptNotice> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, site.url)
        return if (site == DeptSite.APPLIED) parseAppliedBoard(doc, site) else parseNewsBlock(doc, site)
    }

    // ---- Applied Sciences /notice-board ------------------------------------

    private fun parseAppliedBoard(doc: Document, site: DeptSite): List<DeptNotice> {
        val noticeRows = doc.select("div.notice-block div.views-row")
        val rows = if (noticeRows.isNotEmpty()) noticeRows else doc.select("div.views-row")
        val byUrl = LinkedHashMap<String, DeptNotice>()
        for (row in rows) {
            val anchor = row.selectFirst("a[href]") ?: continue
            val href = absoluteHref(anchor) ?: continue
            if (row.selectFirst(".more-link") != null) continue
            val title = anchor.text().trim()
            if (title.length < 3) continue
            val date = row.selectFirst("time[datetime]")
                ?.attr("datetime")
                ?.takeIf { it.length >= 10 }
                ?.substring(0, 10)
                .orEmpty()
            if (byUrl.containsKey(href)) continue
            byUrl[href] = DeptNotice(
                id = href,
                title = title,
                url = href,
                dept = site.key,
                date = date
            )
        }
        // Newest first; rows without a parseable date keep the page's own order after them.
        return byUrl.values.sortedByDescending { it.date }
    }

    // ---- Drupal department homepages ---------------------------------------

    private fun parseNewsBlock(doc: Document, site: DeptSite): List<DeptNotice> {
        val block = findNewsBlock(doc) ?: return emptyList()
        val byUrl = LinkedHashMap<String, DeptNotice>()
        for (anchor in block.select("a[href]")) {
            // The "more" link and any inline widget chrome stay out.
            if (anchor.parents().any { it.hasClass("more-link") }) continue
            val href = absoluteHref(anchor) ?: continue
            val title = anchor.text().replace('\u00A0', ' ').trim()
            if (title.length < 3) continue
            if (byUrl.containsKey(href)) continue
            byUrl[href] = DeptNotice(
                id = href,
                title = title,
                url = href,
                dept = site.key,
                isNew = isMarkedNew(anchor)
            )
        }
        return byUrl.values.toList()
    }

    /**
     * The news list lives in sidebar block `block-block-2` on every department
     * homepage. The heading scan is only a fallback for future theme changes;
     * it accepts "News and Event(s)" and "Notice Board" spellings.
     */
    private fun findNewsBlock(doc: Document): Element? {
        doc.selectFirst("div#block-block-2")?.let { block ->
            val heading = block.selectFirst("h2")?.text()?.lowercase().orEmpty()
            if (heading.contains("news") || heading.contains("notice")) {
                return block.selectFirst("div.content") ?: block
            }
        }
        for (block in doc.select("div.block")) {
            val heading = block.selectFirst("h2")?.text()?.lowercase().orEmpty()
            if (heading.contains("news and event") || heading.contains("notice board")) {
                return block.selectFirst("div.content") ?: block
            }
        }
        return null
    }

    /**
     * True when a "new" bullet (an img whose src contains "newi") sits directly
     * before the anchor — as `<img>`, or wrapped in `<sup>`/`<li>`. Two hops
     * back are enough for every department's markup.
     */
    private fun isMarkedNew(anchor: Element): Boolean {
        var sibling = anchor.previousElementSibling()
        repeat(2) {
            val current = sibling ?: return false
            if (current.tagName() == "img" && current.attr("src").contains("newi", ignoreCase = true)) return true
            if (current.selectFirst("img[src*=newi]") != null) return true
            sibling = current.previousElementSibling()
        }
        return false
    }

    /**
     * Absolute http(s) URL for an anchor, or null when the href is blank or is
     * pure chrome (#, mailto:, javascript:, escaped junk). Relative hrefs are
     * resolved against the site's own host, trimmed first because several
     * departments paste hrefs with a leading space which jsoup cannot resolve.
     */
    private fun absoluteHref(anchor: Element): String? {
        val raw = anchor.attr("href").trim()
        if (raw.length < 3) return null
        if (raw.startsWith("#")) return null
        if (raw.startsWith("mailto:", ignoreCase = true)) return null
        if (raw.startsWith("javascript:", ignoreCase = true)) return null
        if (raw.startsWith("&lt;")) return null
        val base = anchor.baseUri().trim()
        val root = base.substringAfter("//").substringBefore('/')
        return when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://$root$raw"
            // Plain "node/5" style: resolve against the path part of the base URI.
            else -> base.substringBefore('?').let { if (it.endsWith("/")) it + raw else "$it/$raw" }
        }
    }
}

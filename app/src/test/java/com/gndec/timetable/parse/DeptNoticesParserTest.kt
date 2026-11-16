package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeptNoticesParser and DeptSites against saved copies of the seven real
 * department pages: every site must yield its notice list, URLs must be
 * absolute and deduped, the "new" bullets must survive, Applied Sciences'
 * dated board must keep its dates newest-first, and the saved branch/year →
 * department resolution (first year = Applied Sciences) must hold.
 */
class DeptNoticesParserTest {

    private fun html(name: String): String =
        javaClass.getResource("/live/$name")!!.readBytes().decodeToString()

    private fun parse(site: DeptSite): List<DeptNotice> =
        DeptNoticesParser.parse(html("${site.key}_home.html"), site)

    private val homeSites = listOf(DeptSite.EE, DeptSite.CE, DeptSite.ME, DeptSite.ECE, DeptSite.CSE, DeptSite.IT)

    @Test
    fun everyDepartmentYieldsItsNoticeList() {
        for (site in homeSites) {
            val notices = parse(site)
            assertTrue("${site.label} yielded only ${notices.size} notices", notices.size >= 5)
            assertEquals("duplicate URLs survived for ${site.key}", notices.map { it.url }.distinct().size, notices.size)
            notices.forEach { assertEquals(site.key, it.dept) }
        }
    }

    @Test
    fun everyLinkIsAbsoluteAndChromeFree() {
        for (site in homeSites) {
            for (notice in parse(site)) {
                assertTrue("relative href leaked: ${notice.url}", notice.url.startsWith("http"))
                assertFalse("anchor href leaked: ${notice.url}", notice.url.contains("#"))
                assertFalse("mailto leaked: ${notice.url}", notice.url.startsWith("mailto:"))
                assertTrue("suspicious href: ${notice.url}", !notice.url.startsWith("&"))
                assertTrue("empty title for ${notice.url}", notice.title.isNotBlank() && notice.title.length >= 3)
            }
        }
    }

    @Test
    fun siteChromeStaysOutOfTheList() {
        // Navigation/menu chrome never links through /sites/default/files notices.
        for (site in homeSites) {
            for (notice in parse(site)) {
                val url = notice.url.lowercase()
                assertFalse("junk anchor leaked for ${site.key}: $url", url.contains("javascript"))
                assertFalse("escaped junk leaked for ${site.key}: $url", url.startsWith("http&"))
            }
        }
        // Applied Sciences' board ends with a "more" link that is NOT a notice.
        val appsc = DeptNoticesParser.parse(html("appsc_notice_board.html"), DeptSite.APPLIED)
        assertTrue(
            "the more-link leaked: ${appsc.map { it.title }}",
            appsc.none { it.title.trim().equals("more", ignoreCase = true) }
        )
    }

    @Test
    fun knownNoticesArePresent() {
        assertTrue(parse(DeptSite.CSE).any { it.title.contains("MSE-II Practical") })
        assertTrue(parse(DeptSite.IT).any { it.title.contains("TR-102") && it.title.contains("Viva") })
        assertTrue(parse(DeptSite.EE).any { it.title.contains("MST-II") })
        assertTrue(parse(DeptSite.ME).any { it.title.contains("Date Sheet for MSE-I") })
        assertTrue(parse(DeptSite.CE).any { it.title.contains("MSE-II") })
        assertTrue(parse(DeptSite.ECE).any { it.title.contains("Internal Viva Voce") })
    }

    @Test
    fun newBulletsAreKept() {
        // CSE marks nearly every current notice with the newi.gif bullet.
        val cse = parse(DeptSite.CSE)
        assertTrue("expected new-marked CSE notices, got ${cse.count { it.isNew }}", cse.count { it.isNew } >= 5)
        // EE marks several of its datasheet notices the same way.
        val ee = parse(DeptSite.EE)
        assertTrue("expected new-marked EE notices, got ${ee.count { it.isNew }}", ee.count { it.isNew } >= 1)
    }

    @Test
    fun appliedSciencesBoardIsDatedAndNewestFirst() {
        val appsc = DeptNoticesParser.parse(html("appsc_notice_board.html"), DeptSite.APPLIED)
        assertTrue("board yielded ${appsc.size}", appsc.size >= 2)
        appsc.forEach { assertEquals("date not ISO for ${it.title}: '${it.date}'", 10, it.date.length) }
        assertEquals("2026-08-21", appsc.first().date)
        val dates = appsc.map { it.date }
        assertEquals("not newest-first", dates.sortedDescending(), dates)
    }

    @Test
    fun resolveSavedBranchToItsDepartmentSite() {
        assertEquals(DeptSite.CSE, DeptSites.resolve("CS", 3, "D3 CS A"))
        assertEquals(DeptSite.IT, DeptSites.resolve("IT", 2, "D2IT_A"))
        assertEquals(DeptSite.ECE, DeptSites.resolve("EC", 4, null))
        assertEquals(DeptSite.EE, DeptSites.resolve("EE", 0, "D2A"))
        assertEquals(DeptSite.CE, DeptSites.resolve("CE", 0, null))
        assertEquals(DeptSite.ME, DeptSites.resolve("ME", 0, null))
        // RAI groups are published inside ME's documents.
        assertEquals(DeptSite.ME, DeptSites.resolve("RAI", 0, "D2 RAI A1"))
    }

    @Test
    fun firstYearAlwaysResolvesToAppliedSciences() {
        // Saved academic year 1 wins regardless of admission branch.
        assertEquals(DeptSite.APPLIED, DeptSites.resolve("CS", 1, "D1 CS A1"))
        assertEquals(DeptSite.APPLIED, DeptSites.resolve("IT", 1, null))
        // Legacy profile without a year: the group's D1 token decides.
        assertEquals(DeptSite.APPLIED, DeptSites.resolve("CS", 0, "D1 CS A1"))
        assertEquals(DeptSite.APPLIED, DeptSites.resolve(null, 0, "D1IT_A"))
        assertEquals(DeptSite.APPLIED, DeptSites.resolve(null, 1, null))
    }

    @Test
    fun unresolvedProfilesYieldNoDepartment() {
        assertNull(DeptSites.resolve(null, 0, null))
        assertNull(DeptSites.resolve("", 0, ""))
        assertNull(DeptSites.resolve(null, 0, "Automatic Group"))
    }

    @Test
    fun groupSuppliesBranchWhenProfileHasNone() {
        assertEquals(DeptSite.CSE, DeptSites.resolve("", 0, "D3 CS B"))
        assertEquals(DeptSite.IT, DeptSites.resolve(null, 0, "D2IT_B"))
    }

    @Test
    fun syntheticDepartmentPage() {
        val html = """
            <html><body><div id="sidebar-first"><div class="section">
              <div id="block-block-2" class="block block-block clearfix">
                <h2>News and Events</h2>
                <div class="content">
                  <li><sup><img src="https://me.gndec.ac.in/sites/default/files/newi.gif" /></sup><a href="/sites/default/files/datasheet.pdf">Date Sheet for MSE-I</a></li>
                  <li><a href="https://me.gndec.ac.in/?q=node/5">Time table (July - December 2026)</a></li>
                  <li><a href="mailto:dept@gndec.ac.in">Mail the department</a></li>
                </div>
              </div>
            </div></div></body></html>
        """.trimIndent()
        val notices = DeptNoticesParser.parse(html, DeptSite.ME)
        assertEquals(2, notices.size)
        assertEquals("Date Sheet for MSE-I", notices[0].title)
        assertTrue(notices[0].url.endsWith("/sites/default/files/datasheet.pdf"))
        assertTrue("expected the new-marked notice to be flagged", notices[0].isNew)
        assertFalse(notices[1].isNew)
        assertNotNull(notices.firstOrNull { it.url == "https://me.gndec.ac.in/?q=node/5" })
    }

    @Test
    fun emptyInputYieldsEmptyList() {
        assertEquals(0, DeptNoticesParser.parse("", DeptSite.CSE).size)
        assertEquals(0, DeptNoticesParser.parse("<html><body><p>nothing</p></body></html>", DeptSite.IT).size)
    }
}

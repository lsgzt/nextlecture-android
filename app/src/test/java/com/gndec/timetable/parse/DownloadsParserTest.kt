package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * DownloadsParser against a saved copy of the real gndec.ac.in/?q=node/85
 * page: the student-important forms must float to the top, the site chrome
 * must stay out, every URL must be absolute and deduped, and the attachment
 * table's file sizes must survive.
 */
class DownloadsParserTest {

    private fun liveHtml(): String =
        javaClass.getResource("/live/downloads_node85.html")!!
            .readBytes().decodeToString()

    private fun parseLive(): List<DownloadLink> = DownloadsParser.parse(liveHtml())

    @Test
    fun studentImportantFormsRankAtTheTop() {
        val links = parseLive()
        assertTrue("page must yield links, got ${links.size}", links.size >= 40)
        // Bus pass is the single highest-priority rule, so it leads the list.
        assertTrue(
            "bus pass should lead the list, got: ${links.first().title}",
            links.first().title.lowercase().contains("bus pass")
        )
        // Everything at/above TOP_SCORE precedes the rest; that block must
        // include the gate/vehicle pass forms students ask for most.
        val topBlock = links.takeWhile { DownloadsParser.priority(it.title) >= DownloadsParser.TOP_SCORE }
        assertTrue("expected a meaningful top block, got ${topBlock.size}", topBlock.size >= 3)
        val topText = topBlock.joinToString(" | ") { it.title.lowercase() }
        assertTrue("gate/vehicle pass expected in the top block: $topText", topText.contains("gate pass") || topText.contains("vehicle"))
        // And the ordering itself never increases in priority.
        val scores = links.map { DownloadsParser.priority(it.title) }
        assertEquals("priority order is not monotonically non-increasing", scores.sortedDescending(), scores)
    }

    @Test
    fun everyLinkIsAbsoluteAndChromeFree() {
        for (link in parseLive()) {
            assertTrue("relative href leaked: ${link.url}", link.url.startsWith("http"))
            assertFalse("anchor href leaked: ${link.url}", link.url.contains("#"))
            assertFalse("printer chrome leaked: ${link.url}", link.url.contains("/print"))
            assertTrue("empty title for ${link.url}", link.title.isNotBlank())
        }
    }

    @Test
    fun duplicateUrlsCollapseIntoOneEntry() {
        val links = parseLive()
        assertEquals("duplicate URLs survived", links.map { it.url }.distinct().size, links.size)
    }

    @Test
    fun attachmentTableSizesAreKept() {
        val withSize = parseLive().filter { it.sizeLabel.isNotBlank() }
        assertTrue("expected attachment table sizes on several rows, got ${withSize.size}", withSize.size >= 10)
        withSize.forEach {
            assertTrue("size label is not a size: '${it.sizeLabel}'", it.sizeLabel.contains(Regex("\\d")))
        }
    }

    @Test
    fun fileExtensionsAreFlagged() {
        val files = parseLive().filter { it.isFile }
        assertTrue("expected document files among links, got ${files.size}", files.size >= 40)
        val known = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".jpg", ".jpeg", ".png", ".txt", ".csv", ".html"
        )
        files.forEach { link ->
            val url = link.url.substringBefore('?').lowercase()
            assertTrue("file flagged without a document extension: $url", known.any { url.endsWith(it) })
        }
    }

    @Test
    fun priorityScoringRules() {
        assertEquals(100, DownloadsParser.priority("Bus Pass Form"))
        assertEquals(95, DownloadsParser.priority("gate pass"))
        assertEquals(95, DownloadsParser.priority("Vehicle entry permission"))
        assertEquals(90, DownloadsParser.priority("Identity Card form"))
        assertEquals(85, DownloadsParser.priority("Bonafide Student Certificate"))
        assertEquals(80, DownloadsParser.priority("no dues form"))
        // Staff-only variants are demoted below their student counterparts:
        // railway 75, staff demotion -15 -> 60.
        assertEquals(60, DownloadsParser.priority("Railway concession — staff"))
        assertEquals(0, DownloadsParser.priority("Some unlisted circular"))
    }

    @Test
    fun parseOfSyntheticPage() {
        val html = """
            <html><body><div id="content-content">
              <p><a href="/sites/default/files/bus.pdf">Bus Pass form</a></p>
              <table id="attachments"><tr>
                <td><a href="/sites/default/files/bus.pdf">Bus Pass form</a></td>
                <td>564 Kb</td>
              </tr></table>
            </div></body></html>
        """.trimIndent()
        val links = DownloadsParser.parse(html)
        assertEquals(1, links.size)
        assertEquals("Bus Pass form", links.first().title)
        assertEquals("564 Kb", links.first().sizeLabel)
        assertTrue(links.first().url.endsWith("/sites/default/files/bus.pdf"))
        assertTrue(links.first().isFile)
    }

    @Test
    fun emptyInputYieldsEmptyList() {
        assertEquals(0, DownloadsParser.parse("").size)
        assertEquals(0, DownloadsParser.parse("<html><body><p>nothing here</p></body></html>").size)
    }
}

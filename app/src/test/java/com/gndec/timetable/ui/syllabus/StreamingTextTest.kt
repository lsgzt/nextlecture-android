package com.gndec.timetable.ui.syllabus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextTest {

    /** Scenario 3 — a very long thought stays a bounded preview instead of a wall of text. */
    @Test
    fun longReasoningIsPreviewedAsItsTail() {
        val reasoning = (1..400).joinToString(" ") { "step$it" }
        val preview = tailPreview(reasoning, 100)
        assertTrue(preview.length <= 101)
        assertEquals("…step400", preview)
    }

    @Test
    fun shortReasoningIsShownWhole() {
        assertEquals("Checking the document.", tailPreview("Checking the document.", 100))
        assertEquals("", tailPreview("", 100))
    }

    /**
     * Scenario 4 — a one-character answer must survive the preview untouched; trimming here
     * would silently drop a valid short response.
     */
    @Test
    fun singleCharacterResponseIsNotTrimmed() {
        assertEquals("4", tailPreview("4", 520))
    }
}

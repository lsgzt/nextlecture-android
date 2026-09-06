package com.gndec.timetable.domain

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException

/**
 * PDF text extractor that knows about TABLE COLUMNS.
 *
 * The permanent-section documents lay every student out in fixed columns
 * (Student Name | Father Name | Mother Name | Branch | …). Plain
 * [PDFTextStripper] output collapses the columns into one space-separated
 * line — "Aaditya Koundal Kapil Dev Monika IT ITA …" — which makes it
 * impossible to tell where the student's name ends and the father's begins.
 * That ambiguity is why names came out combined with the parents'.
 *
 * This stripper watches the horizontal position of every glyph: whenever the
 * gap between consecutive glyphs exceeds [COLUMN_GAP_UNITS] (a word space is
 * ~3-5 units, a column jump is 15-60), a [COLUMN_SEPARATOR] is emitted
 * instead. One row therefore becomes pipe-delimited text:
 *
 * `1| 2621001| 26013653| Aaditya Koundal| Kapil Dev| Monika| IT| ITA| ITA1| …`
 *
 * [StudentDirectoryParser] consumes this format as its STRICT primary path;
 * anything that does not decompose cleanly falls back to the legacy
 * space-separated handling, so extraction can only improve, never regress.
 */
class ColumnAwarePdfTextStripper : PDFTextStripper() {

    /** X end of the previous glyph on the current line; NaN at line start. */
    private var lastGlyphEndX = Float.NaN

    /** pdfbox decided a word separator belongs here — emit it unless a column gap follows. */
    private var pendingWordSpace = false

    init {
        setSortByPosition(true)
    }

    override fun writeWordSeparator() {
        pendingWordSpace = true
    }

    @Suppress("UNUSED")
    @Throws(IOException::class)
    override fun writeLineSeparator() {
        lastGlyphEndX = Float.NaN
        pendingWordSpace = false
        super.writeLineSeparator()
    }

    @Throws(IOException::class)
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val builder = StringBuilder(text.length + 8)
        if (pendingWordSpace && textPositions.isNotEmpty()) {
            val firstX = textPositions.first().xDirAdj
            builder.append(
                when {
                    !lastGlyphEndX.isNaN() && firstX - lastGlyphEndX > COLUMN_GAP_UNITS -> COLUMN_SEPARATOR
                    else -> " "
                }
            )
            pendingWordSpace = false
        }
        for (position in textPositions) {
            val x = position.xDirAdj
            if (!lastGlyphEndX.isNaN() && x - lastGlyphEndX > COLUMN_GAP_UNITS) {
                builder.append(COLUMN_SEPARATOR)
            }
            builder.append(position.unicode)
            lastGlyphEndX = x + position.widthDirAdj
        }
        super.writeString(builder.toString())
    }

    companion object {
        const val COLUMN_SEPARATOR = "|"

        /** Word spacing is ~3-5 units at the documents' font size; columns jump 15+. */
        const val COLUMN_GAP_UNITS = 10f
    }
}

package com.gndec.timetable.domain

/**
 * Parses GNDEC permanent-section PDF text (already extracted to lines) into student
 * directory records. Pure JVM logic so it can be unit tested without Android.
 *
 * Two official layouts are supported:
 *  - Current: `S.No. | College Roll No. | Registration No. | Student Name | Father Name |
 *    Mother Name | Branch | Section | Subsection | Mentoring Group | Mentor Name |
 *    Mentor's Mobile No. | Venue` — the registration number is an 8-digit token
 *    directly after the 7-digit roll number.
 *  - Legacy: the same table without the Registration No. column; registration numbers
 *    are then filled from the bundled in-app fallback map keyed by CRN.
 *
 * Data-safety rules (real student data):
 *  - Every field of a record is derived from that record's own PDF row, keyed by CRN.
 *    No information is ever copied across students.
 *  - Human-readable name parts (student/father/mother) cannot be split reliably from
 *    concatenated PDF text, so the split is taken from the bundled directory only when
 *    the bundled tokens for that CRN exactly match the PDF tokens. Rows without a
 *    verified split keep the full PDF text as [StudentDirectoryRecord.candidateName]
 *    with blank father/mother fields — search still works, identity is never guessed.
 */
object StudentDirectoryParser {

    /** Row start: serial number, 7-digit college roll number (CRN), remainder. */
    private val ROW_START = Regex("^(\\d+)\\s+(\\d{7})\\s+(.+)$")

    /** Current layout: an 8-digit registration number right after the CRN. */
    private val REGISTRATION_PREFIX = Regex("^(\\d{8})\\s+(\\S.*)$")

    /** Verified name split for one CRN, taken from the bundled directory. */
    data class NameSplit(val candidateName: String, val fatherName: String, val motherName: String) {
        val tokens: List<String> by lazy {
            "$candidateName $fatherName $motherName".trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }

    fun parse(
        lines: List<String>,
        branch: String,
        nameSplits: Map<String, NameSplit> = emptyMap(),
        registrationFallback: Map<String, String> = emptyMap()
    ): List<StudentDirectoryRecord> {
        val normalizedBranch = branch.trim().uppercase()
        val branchToken = Regex.escape(normalizedBranch)
        // Branch, Section, Subsection, Mentoring Group, Mentor Name, 10-digit mobile, Venue.
        val tail = Regex(
            "\\s$branchToken\\s+([A-Z]{2,4})\\s+([A-Z]{2,4}\\d?)\\s+([A-Z]{2,4}\\d?M?\\d?)\\s+(.+?)\\s+(\\d{10})\\s+(.+)$"
        )
        val records = mutableListOf<StudentDirectoryRecord>()
        for (raw in lines) {
            val line = raw.trim()
            val start = ROW_START.matchEntire(line) ?: continue
            val crn = start.groupValues[2]
            var rest = start.groupValues[3]
            var registration = ""
            val regMatch = REGISTRATION_PREFIX.matchEntire(rest)
            if (regMatch != null) {
                registration = regMatch.groupValues[1]
                rest = regMatch.groupValues[2]
            }
            val tailMatch = tail.find(rest) ?: continue
            val namesPart = normalizeWhitespace(rest.substring(0, tailMatch.range.first))
            val record = StudentDirectoryRecord(
                crn = crn,
                registrationNumber = registration.ifBlank { registrationFallback[crn].orEmpty() },
                candidateName = namesPart,
                fatherName = "",
                motherName = "",
                branch = normalizedBranch,
                section = tailMatch.groupValues[1],
                subsection = tailMatch.groupValues[2],
                group = tailMatch.groupValues[3],
                mentorName = normalizeWhitespace(tailMatch.groupValues[4]),
                mentorMobile = tailMatch.groupValues[5],
                venue = normalizeWhitespace(tailMatch.groupValues[6])
            )
            records += applyVerifiedNameSplit(record, nameSplits[crn], namesPart)
        }
        return records
    }

    /**
     * Uses the bundled split only when its tokens exactly match the PDF tokens for this
     * same CRN; otherwise keeps the full concatenated PDF text as the candidate name.
     */
    private fun applyVerifiedNameSplit(
        record: StudentDirectoryRecord,
        split: NameSplit?,
        namesPart: String
    ): StudentDirectoryRecord {
        if (split == null || namesPart.isBlank()) return record
        val pdfTokens = namesPart.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (pdfTokens.isEmpty() || split.tokens != pdfTokens) return record
        return record.copy(
            candidateName = normalizeWhitespace(split.candidateName),
            fatherName = normalizeWhitespace(split.fatherName),
            motherName = normalizeWhitespace(split.motherName)
        )
    }

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}

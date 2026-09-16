package com.gndec.timetable.domain

/**
 * Parses GNDEC permanent-section PDF text (already extracted to lines) into student
 * directory records. Pure JVM logic so it can be unit tested without Android.
 *
 * THREE extraction paths, tried per row in order:
 *
 *  1. STRICT pipe format (primary) — produced by [ColumnAwarePdfTextStripper],
 *     which re-inserts the PDF's column boundaries: every token IS a table
 *     cell. Supports both layouts published by the college:
 *
 *     **Sept 2026+ layout** (current official):
 *       `…| CRN | Registration | Branch | Student | Mother | Father | Section |
 *        Subsection | MentoringGroup | Mentor | Mobile | Venue | ClassCoordinator…`
 *
 *     **Aug 2026 layout** (previous):
 *       `…| CRN | Registration | Student | Father | Mother | Branch | Section |
 *        Subsection | MentoringGroup | Mentor | Mobile | Venue…`
 *
 *     The names come straight from the official document's own columns, so
 *     father and mother can never blend into the student's name. No bundled
 *     data needed. The serial, CRN and registration columns sit close together
 *     in the PDF, so extraction may merge them into ONE cell ("2614001 26012961");
 *     such cells are decomposed strictly by digit-shape (7-digit CRN, 8-digit
 *     registration) and rejected if any non-numeric text rides along.
 *     The official document occasionally swaps the Father/Mother cells of a
 *     row (data-entry slip); when the bundled directory holds the SAME three
 *     names with father/mother exchanged, the bundled (corrected) order is
 *     used — exact token match only, never a guess.
 *  2. Legacy space-separated rows (fallback for PDFs/extractions where column
 *     gaps were not detected): the three name columns stay concatenated, so
 *     the split is taken from the bundled directory only when the bundled
 *     tokens for that CRN exactly match the PDF tokens. Rows without a
 *     verified split keep the full PDF text as [StudentDirectoryRecord.candidateName]
 *     with blank father/mother fields — search still works, identity is never guessed.
 *  3. Rows that fit neither path are skipped (headers, page numbers, other
 *     branches' tables).
 *
 * Data-safety rules (real student data):
 *  - Every field of a record is derived from that record's own PDF row, keyed by CRN.
 *    No information is ever copied across students.
 */
object StudentDirectoryParser {

    /** Row start: serial number, 7-digit college roll number (CRN), remainder. */
    private val ROW_START = Regex("^(\\d+)\\s+(\\d{7})\\s+(.+)$")

    /** Current layout: an 8-digit registration number right after the CRN. */
    private val REGISTRATION_PREFIX = Regex("^(\\d{8})\\s+(\\S.*)$")

    /** Strict-path token shapes. */
    private val CRN_TOKEN = Regex("^\\d{7}$")
    private val REGISTRATION_TOKEN = Regex("^\\d{8}$")
    private val MOBILE_TOKEN = Regex("^\\d{10}$")
    private val SERIAL_TOKEN = Regex("^\\d{1,4}$")
    private val WHITESPACE = Regex("\\s+")
    private val PIPE_RUN = Regex("\\s*\\|\\s*")

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
        // Branch, Section, Subsection, Mentoring Group, Mentor Name, 10-digit mobile, Venue
        // (+ optional Class Coordinator). Used by the legacy space-separated path.
        val tail = Regex(
            "\\s$branchToken\\s+([A-Z]{2,4})\\s+([A-Z]{2,4}\\d?)\\s+([A-Z]{2,4}\\d?M?\\d?)\\s+(.+?)\\s+(\\d{10})\\s+(.+)$"
        )
        val records = mutableListOf<StudentDirectoryRecord>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 1) STRICT path: column-aware extraction. The official document's
            //    own cell boundaries decide the name split.
            val strictRecord = parsePipeRow(line, normalizedBranch, registrationFallback, nameSplits)
            if (strictRecord != null) {
                records += strictRecord
                continue
            }
            // 2) LEGACY path: collapse any stray pipe to whitespace first, then
            //    the historical space-separated logic applies.
            val legacyLine = line.replace(PIPE_RUN, " ")
            val start = ROW_START.matchEntire(legacyLine) ?: continue
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
            // Venue may contain "Venue ClassCoordinator" — leave as-is for legacy;
            // the strict path is authoritative for the new column.
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
                venue = normalizeWhitespace(tailMatch.groupValues[6]),
                classCoordinator = ""
            )
            records += applyVerifiedNameSplit(record, nameSplits[crn], namesPart)
        }
        return records
    }

    /**
     * STRICT row parser for the column-aware extraction. Supports both the
     * Sept-2026 layout (Branch before names; Mother before Father; optional
     * Class Coordinator after Venue) and the prior Aug-2026 layout
     * (Student/Father/Mother then Branch).
     *
     * A row is accepted when it decomposes into table cells whose tail is
     * anchored on the 10-digit mobile and whose branch token matches the
     * document being parsed. Exactly three name cells must sit next to the
     * branch. Names are never guessed; they ARE the official document's columns.
     */
    internal fun parsePipeRow(
        line: String,
        normalizedBranch: String,
        registrationFallback: Map<String, String> = emptyMap(),
        nameSplits: Map<String, NameSplit> = emptyMap()
    ): StudentDirectoryRecord? {
        if (!line.contains(COLUMN_SEPARATOR)) return null
        val tokens = line.split(COLUMN_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        val mobileIdx = tokens.indexOfLast { it.matches(MOBILE_TOKEN) }
        // Tail needs at least: section, subsection, group, mentor before the mobile.
        if (mobileIdx < 0 || mobileIdx < 4) return null

        // After mobile: Venue [Class Coordinator]. When two or more remaining
        // cells exist, the last is treated as Class Coordinator and the rest
        // joined as Venue (venues can be multi-token when gaps were missed).
        val afterMobile = tokens.drop(mobileIdx + 1)
        val classCoordinator: String
        val venue: String
        when {
            afterMobile.isEmpty() -> return null
            afterMobile.size == 1 -> {
                venue = afterMobile[0]
                classCoordinator = ""
            }
            else -> {
                classCoordinator = afterMobile.last()
                venue = afterMobile.dropLast(1).joinToString(" ").trim()
            }
        }
        if (venue.isEmpty()) return null

        val mentorName = tokens[mobileIdx - 1]
        val group = tokens[mobileIdx - 2]
        val subsection = tokens[mobileIdx - 3]
        val section = tokens[mobileIdx - 4]
        // Everything before the fixed tail (section…mentor) is the leading block
        // that holds serial/CRN/registration, optional branch, and the three names.
        val leadingEnd = mobileIdx - 4  // exclusive; tokens[0 until leadingEnd]
        if (leadingEnd < 4) return null  // need room for CRN + 3 names at minimum

        // Locate the CRN inside the leading cells, tolerating merged
        // serial/CRN/registration cells but nothing else.
        var crn = ""
        var registration = ""
        var crnFound = false
        var cursor = 0
        while (cursor < leadingEnd - 2) {
            val cellTokens = tokens[cursor].split(WHITESPACE).filter { it.isNotBlank() }
            val crnToken = cellTokens.firstOrNull { it.matches(CRN_TOKEN) }
            if (crnToken == null) {
                // Cells before the CRN may be a serial number and/or a standalone
                // 8-digit registration (Sept 2026 layout places Registration before CRN).
                if (cellTokens.isEmpty() ||
                    cellTokens.any { !it.matches(SERIAL_TOKEN) && !it.matches(REGISTRATION_TOKEN) }
                ) return null
                for (t in cellTokens) {
                    if (t.matches(REGISTRATION_TOKEN)) registration = t
                }
                cursor++
                continue
            }
            val regToken = cellTokens.firstOrNull { it.matches(REGISTRATION_TOKEN) }
            val leftovers = cellTokens.filter { it != crnToken && it != regToken && !it.matches(SERIAL_TOKEN) }
            if (leftovers.isNotEmpty()) return null
            crn = crnToken
            if (regToken != null) registration = regToken
            cursor++
            if (registration.isEmpty() && cursor < leadingEnd - 2 && tokens[cursor].matches(REGISTRATION_TOKEN)) {
                registration = tokens[cursor]
                cursor++
            }
            crnFound = true
            break
        }
        if (!crnFound) return null

        // Remaining cells between CRN-block and the fixed tail: either
        //   NEW layout:  Branch Student Mother Father
        //   OLD layout:  Student Father Mother Branch
        val mid = tokens.subList(cursor, leadingEnd)
        if (mid.size != 4) return null

        val student: String
        var father: String
        var mother: String
        val branchToken: String

        val mid0 = mid[0]
        val mid1 = mid[1]
        val mid2 = mid[2]
        val mid3 = mid[3]

        if (mid0.equals(normalizedBranch, ignoreCase = true)) {
            // Sept 2026+ layout: Branch | Student | Mother | Father
            branchToken = mid0
            student = mid1
            mother = mid2
            father = mid3
        } else if (mid3.equals(normalizedBranch, ignoreCase = true)) {
            // Aug 2026 layout: Student | Father | Mother | Branch
            student = mid0
            father = mid1
            mother = mid2
            branchToken = mid3
        } else {
            return null
        }
        if (!branchToken.equals(normalizedBranch, ignoreCase = true)) return null
        if (student.isBlank() || father.isBlank() || mother.isBlank()) return null

        // The document occasionally swaps the Father/Mother cells of a row;
        // the bundled directory carries the corrected order for that CRN.
        val split = nameSplits[crn]
        if (split != null && isParentSwap(student, father, mother, split)) {
            father = normalizeWhitespace(split.fatherName)
            mother = normalizeWhitespace(split.motherName)
        }
        return StudentDirectoryRecord(
            crn = crn,
            registrationNumber = registration.ifBlank { registrationFallback[crn].orEmpty() },
            candidateName = student,
            fatherName = father,
            motherName = mother,
            branch = normalizedBranch,
            section = section,
            subsection = subsection,
            group = group,
            mentorName = normalizeWhitespace(mentorName),
            mentorMobile = tokens[mobileIdx],
            venue = normalizeWhitespace(venue),
            classCoordinator = normalizeWhitespace(classCoordinator)
        )
    }

    /** True when the bundled split holds the SAME three names with father/mother exchanged. */
    private fun isParentSwap(student: String, father: String, mother: String, split: NameSplit): Boolean {
        fun same(a: String, b: String) =
            normalizeWhitespace(a).equals(normalizeWhitespace(b), ignoreCase = true)
        return same(student, split.candidateName) &&
            same(father, split.motherName) &&
            same(mother, split.fatherName)
    }

    /**
     * Uses the bundled split when its tokens exactly match the PDF tokens for this
     * same CRN — including the father/mother-swapped variant (the document's own
     * column slip, corrected in the bundle). Otherwise keeps the full concatenated
     * PDF text as the candidate name.
     */
    private fun applyVerifiedNameSplit(
        record: StudentDirectoryRecord,
        split: NameSplit?,
        namesPart: String
    ): StudentDirectoryRecord {
        if (split == null || namesPart.isBlank()) return record
        val pdfTokens = namesPart.split(WHITESPACE).filter { it.isNotBlank() }
        if (pdfTokens.isEmpty()) return record
        val matchesDirect = split.tokens == pdfTokens
        val swappedTokens = sequenceOf(split.candidateName, split.motherName, split.fatherName)
            .flatMap { WHITESPACE.split(it.trim()) }
            .filter { it.isNotBlank() }
            .toList()
        val matchesSwapped = swappedTokens == pdfTokens
        if (!matchesDirect && !matchesSwapped) return record
        return record.copy(
            candidateName = normalizeWhitespace(split.candidateName),
            fatherName = normalizeWhitespace(split.fatherName),
            motherName = normalizeWhitespace(split.motherName)
        )
    }

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private const val COLUMN_SEPARATOR = "|"
}

package com.gndec.timetable.domain

/**
 * Parses GNDEC permanent-section PDF text (already extracted to lines) into student
 * directory records. Pure JVM logic so it can be unit tested without Android.
 *
 * THREE extraction paths, tried per row in order:
 *
 *  1. STRICT pipe format (primary) — produced by [ColumnAwarePdfTextStripper].
 *     The Sept 2026 official PDFs pack several narrow columns so tightly that
 *     the stripper often merges:
 *       - Registration + CRN + Branch → one cell ("260136532621001IT")
 *       - Mobile + Venue → one cell ("9814828414S213")
 *     The strict path therefore decomposes cells by digit shape and known
 *     branch tokens, not only by exact cell counts.
 *
 *     Supported layouts:
 *       **Sept 2026+**: Branch before names; Mother before Father; optional
 *         Class Coordinator after Venue.
 *       **Aug 2026**: Student | Father | Mother | Branch | … (no coordinator).
 *
 *  2. Legacy space-separated rows (fallback when pipes are missing or the
 *     strict path rejects a row). Name splits come from the bundled directory
 *     only when tokens match exactly for that CRN.
 *
 *  3. Rows that fit neither path are skipped.
 *
 * Data-safety: every field is derived from that row's own cells, keyed by CRN.
 */
object StudentDirectoryParser {

    /** Row start: serial number, 7-digit college roll number (CRN), remainder. */
    private val ROW_START = Regex("^(\\d+)\\s+(\\d{7})\\s+(.+)$")

    /** Alternate start for Sept 2026 layout: serial, 8-digit registration, 7-digit CRN, rest. */
    private val ROW_START_REG_FIRST = Regex("^(\\d+)\\s+(\\d{8})\\s+(\\d{7})\\s+(.+)$")

    /** Current layout: an 8-digit registration number right after the CRN. */
    private val REGISTRATION_PREFIX = Regex("^(\\d{8})\\s+(\\S.*)$")

    /** Strict-path token shapes. */
    private val CRN_TOKEN = Regex("^\\d{7}$")
    private val REGISTRATION_TOKEN = Regex("^\\d{8}$")
    private val MOBILE_TOKEN = Regex("^\\d{10}$")
    private val MOBILE_EMBEDDED = Regex("(\\d{10})")
    private val SERIAL_TOKEN = Regex("^\\d{1,4}$")
    private val CRN_EMBEDDED = Regex("(\\d{7})")
    private val REG_EMBEDDED = Regex("(\\d{8})")
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
        val tail = Regex(
            "\\s$branchToken\\s+([A-Z]{2,4})\\s+([A-Z]{2,4}\\d?)\\s+([A-Z]{2,4}\\d?M?\\d?)\\s+(.+?)\\s+(\\d{10})\\s+(.+)$"
        )
        val records = mutableListOf<StudentDirectoryRecord>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val strictRecord = parsePipeRow(line, normalizedBranch, registrationFallback, nameSplits)
            if (strictRecord != null) {
                records += strictRecord
                continue
            }
            val legacyLine = line.replace(PIPE_RUN, " ")
            val record = parseLegacyRow(legacyLine, normalizedBranch, tail, nameSplits, registrationFallback)
            if (record != null) records += record
        }
        return records
    }

    private fun parseLegacyRow(
        legacyLine: String,
        normalizedBranch: String,
        tail: Regex,
        nameSplits: Map<String, NameSplit>,
        registrationFallback: Map<String, String>
    ): StudentDirectoryRecord? {
        // Sept 2026 space-separated: serial, registration, CRN, [branch], names..., section...
        val regFirst = ROW_START_REG_FIRST.matchEntire(legacyLine)
        if (regFirst != null) {
            val registration = regFirst.groupValues[2]
            val crn = regFirst.groupValues[3]
            var rest = regFirst.groupValues[4]
            val branchPrefix = Regex("^${Regex.escape(normalizedBranch)}\\s+(.+)$", RegexOption.IGNORE_CASE)
            val bp = branchPrefix.matchEntire(rest)
            if (bp != null) rest = bp.groupValues[1]
            val sectionMatch = Regex(
                "\\s+([A-Z]{2,4})\\s+([A-Z]{2,4}\\d?)\\s+([A-Z]{2,4}\\d?M?\\d?)\\s+(.+?)\\s+(\\d{10})\\s+(.+)$",
                RegexOption.IGNORE_CASE
            ).find(rest) ?: return null
            val namesPart = normalizeWhitespace(rest.substring(0, sectionMatch.range.first))
            val (venue, coordinator) = splitVenueAndCoordinator(sectionMatch.groupValues[6])
            val record = StudentDirectoryRecord(
                crn = crn,
                registrationNumber = registration.ifBlank { registrationFallback[crn].orEmpty() },
                candidateName = namesPart,
                fatherName = "",
                motherName = "",
                branch = normalizedBranch,
                section = sectionMatch.groupValues[1],
                subsection = sectionMatch.groupValues[2],
                group = sectionMatch.groupValues[3],
                mentorName = normalizeWhitespace(sectionMatch.groupValues[4]),
                mentorMobile = sectionMatch.groupValues[5],
                venue = venue,
                classCoordinator = coordinator
            )
            return applyVerifiedNameSplit(record, nameSplits[crn], namesPart)
        }

        val start = ROW_START.matchEntire(legacyLine) ?: return null
        val crn = start.groupValues[2]
        var rest = start.groupValues[3]
        var registration = ""
        val regMatch = REGISTRATION_PREFIX.matchEntire(rest)
        if (regMatch != null) {
            registration = regMatch.groupValues[1]
            rest = regMatch.groupValues[2]
        }
        val tailMatch = tail.find(rest) ?: return null
        val namesPart = normalizeWhitespace(rest.substring(0, tailMatch.range.first))
        val (venue, coordinator) = splitVenueAndCoordinator(tailMatch.groupValues[6])
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
            venue = venue,
            classCoordinator = coordinator
        )
        return applyVerifiedNameSplit(record, nameSplits[crn], namesPart)
    }

    /**
     * STRICT row parser. Handles fused cells from Sept 2026 PDFs where narrow
     * columns sit closer than COLUMN_GAP_UNITS (reg+CRN+branch, mobile+venue).
     */
    internal fun parsePipeRow(
        line: String,
        normalizedBranch: String,
        registrationFallback: Map<String, String> = emptyMap(),
        nameSplits: Map<String, NameSplit> = emptyMap()
    ): StudentDirectoryRecord? {
        if (!line.contains(COLUMN_SEPARATOR)) return null
        val tokens = line.split(COLUMN_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.size < 6) return null

        // Locate mobile: exact 10-digit cell OR embedded in fused mobile+venue cell
        var mobileIdx = tokens.indexOfLast { it.matches(MOBILE_TOKEN) }
        var mobile = ""
        var fusedVenueFromMobile = ""
        if (mobileIdx < 0) {
            for (i in tokens.indices.reversed()) {
                val m = MOBILE_EMBEDDED.find(tokens[i]) ?: continue
                mobile = m.groupValues[1]
                mobileIdx = i
                val before = tokens[i].substring(0, m.range.first).trim()
                val after = tokens[i].substring(m.range.last + 1).trim()
                fusedVenueFromMobile = listOf(before, after).filter { it.isNotBlank() }.joinToString(" ")
                break
            }
        } else {
            mobile = tokens[mobileIdx]
        }
        if (mobileIdx < 0 || mobile.isEmpty()) return null
        if (mobileIdx < 4) return null

        val afterMobile = tokens.drop(mobileIdx + 1).toMutableList()
        if (fusedVenueFromMobile.isNotBlank()) afterMobile.add(0, fusedVenueFromMobile)

        val classCoordinator: String
        val venue: String
        when {
            afterMobile.isEmpty() -> return null
            afterMobile.size == 1 -> {
                val (v, c) = splitVenueAndCoordinator(afterMobile[0])
                venue = v
                classCoordinator = c
            }
            else -> {
                classCoordinator = afterMobile.last()
                venue = afterMobile.dropLast(1).joinToString(" ").trim()
            }
        }
        // Require some venue or coordinator so we don't accept truncated rows
        if (venue.isEmpty() && classCoordinator.isEmpty()) return null

        val mentorName = tokens[mobileIdx - 1]
        val group = tokens[mobileIdx - 2]
        val subsection = tokens[mobileIdx - 3]
        val section = tokens[mobileIdx - 4]
        val leadingEnd = mobileIdx - 4

        var crn = ""
        var registration = ""
        var branchFoundInIds = false
        var nameStart = 0

        var i = 0
        while (i < leadingEnd) {
            val cell = tokens[i]
            val extracted = extractIdsFromCell(cell, normalizedBranch)
            if (extracted.crn.isNotEmpty() || extracted.registration.isNotEmpty() || extracted.branchFound) {
                if (extracted.crn.isNotEmpty() && crn.isEmpty()) crn = extracted.crn
                if (extracted.registration.isNotEmpty() && registration.isEmpty()) registration = extracted.registration
                if (extracted.branchFound) branchFoundInIds = true
                nameStart = i + 1
                i++
                continue
            }
            val cellTokens = cell.split(WHITESPACE).filter { it.isNotBlank() }
            if (cellTokens.isNotEmpty() && cellTokens.all { it.matches(SERIAL_TOKEN) }) {
                i++
                continue
            }
            if (cell.equals(normalizedBranch, ignoreCase = true)) {
                branchFoundInIds = true
                nameStart = i + 1
                i++
                continue
            }
            if (crn.isNotEmpty()) {
                nameStart = i
                break
            }
            i++
        }

        if (crn.isEmpty()) {
            val leadingText = tokens.subList(0, leadingEnd.coerceAtMost(tokens.size)).joinToString(" ")
            val candidates = CRN_EMBEDDED.findAll(leadingText).map { it.groupValues[1] }.toList()
            crn = candidates.lastOrNull() ?: return null
            if (registration.isEmpty()) {
                REG_EMBEDDED.find(leadingText)?.let { registration = it.groupValues[1] }
            }
        }

        val nameCells = tokens.subList(nameStart.coerceAtMost(leadingEnd), leadingEnd)
        if (nameCells.size < 2) return null

        // Detect Aug layout: fourth name-area cell is the branch token
        val isAugLayout = nameCells.size >= 4 &&
            nameCells[3].equals(normalizedBranch, ignoreCase = true)

        val filteredNames = nameCells.filter { !it.equals(normalizedBranch, ignoreCase = true) }
        if (filteredNames.isEmpty()) return null

        val student = filteredNames[0]
        var father: String
        var mother: String
        when {
            isAugLayout && filteredNames.size >= 3 -> {
                // Student | Father | Mother | Branch
                father = filteredNames[1]
                mother = filteredNames[2]
            }
            filteredNames.size >= 3 -> {
                // Sept 2026 (default): Student | Mother | Father
                mother = filteredNames[1]
                father = filteredNames[2]
            }
            filteredNames.size == 2 -> {
                // Mother+Father columns fused (long multi-word names, gap missed).
                // Keep student identity; store the fused parent text under mother and
                // leave father blank — never invent a split.
                mother = filteredNames[1]
                father = ""
            }
            else -> {
                // Only student name recovered; parents unknown.
                mother = ""
                father = ""
            }
        }

        if (student.isBlank()) return null

        val split = nameSplits[crn]
        var outFather = father
        var outMother = mother
        if (split != null && isParentSwap(student, father, mother, split)) {
            outFather = normalizeWhitespace(split.fatherName)
            outMother = normalizeWhitespace(split.motherName)
        }

        return StudentDirectoryRecord(
            crn = crn,
            registrationNumber = registration.ifBlank { registrationFallback[crn].orEmpty() },
            candidateName = normalizeWhitespace(student),
            fatherName = normalizeWhitespace(outFather),
            motherName = normalizeWhitespace(outMother),
            branch = normalizedBranch,
            section = section,
            subsection = subsection,
            group = group,
            mentorName = normalizeWhitespace(mentorName),
            mentorMobile = mobile,
            venue = normalizeWhitespace(venue),
            classCoordinator = normalizeWhitespace(classCoordinator)
        )
    }

    private data class ExtractedIds(val crn: String, val registration: String, val branchFound: Boolean)

    /**
     * Pull 8-digit registration, 7-digit CRN, and optional branch token out of a
     * single (possibly fused) cell such as "260136532621001IT" or "2614001 26012961".
     */
    private fun extractIdsFromCell(cell: String, normalizedBranch: String): ExtractedIds {
        val trimmed = cell.trim()
        if (trimmed.isEmpty()) return ExtractedIds("", "", false)

        if (trimmed.matches(CRN_TOKEN)) return ExtractedIds(trimmed, "", false)
        if (trimmed.matches(REGISTRATION_TOKEN)) return ExtractedIds("", trimmed, false)
        if (trimmed.equals(normalizedBranch, ignoreCase = true)) return ExtractedIds("", "", true)

        // "260136532621001IT" or "260136532621001"
        val fused = Regex(
            "^(?:(\\d{1,4})\\s+)?(\\d{8})(\\d{7})([A-Za-z]{2,4})?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)
        if (fused != null) {
            val reg = fused.groupValues[2]
            val crn = fused.groupValues[3]
            val br = fused.groupValues[4]
            val branchFound = br.equals(normalizedBranch, ignoreCase = true)
            return ExtractedIds(crn, reg, branchFound)
        }

        // "2621001IT"
        val crnBranch = Regex("^(\\d{7})([A-Za-z]{2,4})$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
        if (crnBranch != null) {
            val branchFound2 = crnBranch.groupValues[2].equals(normalizedBranch, ignoreCase = true)
            return ExtractedIds(crnBranch.groupValues[1], "", branchFound2)
        }

        // Spaced: "2614001 26012345" or "33 2621191 26015016"
        val parts = trimmed.split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.size <= 1) return ExtractedIds("", "", false)
        var crn = ""
        var registration = ""
        var branchFound = false
        for (p in parts) {
            when {
                p.matches(CRN_TOKEN) && crn.isEmpty() -> crn = p
                p.matches(REGISTRATION_TOKEN) && registration.isEmpty() -> registration = p
                p.equals(normalizedBranch, ignoreCase = true) -> branchFound = true
                else -> {
                    // Nested fuse e.g. part "260136532621001IT" (different from whole cell)
                    if (p != trimmed) {
                        val sub = extractIdsFromCell(p, normalizedBranch)
                        if (sub.crn.isNotEmpty() && crn.isEmpty()) crn = sub.crn
                        if (sub.registration.isNotEmpty() && registration.isEmpty()) registration = sub.registration
                        if (sub.branchFound) branchFound = true
                    }
                }
            }
        }
        return ExtractedIds(crn, registration, branchFound)
    }

    /**
     * Split trailing "Venue Class Coordinator" when no pipe separates them.
     * Coordinator titles often start with Dr./Er./Mr./Ms./Mrs./Prof.
     */
    private fun splitVenueAndCoordinator(blob: String): Pair<String, String> {
        val text = normalizeWhitespace(blob)
        if (text.isEmpty()) return "" to ""
        val title = Regex("\\b((?:Dr|Er|Mr|Ms|Mrs|Prof)\\.?\\s+.+)$", RegexOption.IGNORE_CASE)
        val m = title.find(text)
        return if (m != null && m.range.first > 0) {
            normalizeWhitespace(text.substring(0, m.range.first)) to normalizeWhitespace(m.groupValues[1])
        } else {
            text to ""
        }
    }

    private fun isParentSwap(student: String, father: String, mother: String, split: NameSplit): Boolean {
        fun same(a: String, b: String) =
            normalizeWhitespace(a).equals(normalizeWhitespace(b), ignoreCase = true)
        return same(student, split.candidateName) &&
            same(father, split.motherName) &&
            same(mother, split.fatherName)
    }

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

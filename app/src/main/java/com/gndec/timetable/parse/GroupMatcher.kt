package com.gndec.timetable.parse

/**
 * Data-driven matching between a student's (year, branch, section) and the FET
 * group names actually published in a department's timetable document.
 *
 * Group naming is NOT universal across GNDEC departments (real 2026 samples):
 *  - CSE:  "D2 CS A" … "D4 CS E", first year "D1 CS A1"…
 *  - IT:   "D2IT_A" "D3IT_B", single 4th-year group "D4IT"
 *  - EE:   "D2A" "D2B" … "D4B" (no branch token at all!)
 *  - CE:   "D2 CE A" "D3 CE B" plus special 4th-year groups "D4CEMC1"…"D4CE (STR)" "D4_EIA(OA)"
 *  - ME:   "D2MEA" "D3ME B", 4th-year streams "D4 ME MANUFACTURING/THERMAL/DESIGN",
 *          practical groups "D4 ME A1", robotics "D2 RAI A1"
 *  - ECE:  "D2ECA" "D3ECB", practical subgroups "D4ECA1" "D4ECB3"
 *
 * Therefore nothing here may assume one regex fits all departments: a group is
 * structurally decomposed (year digit, optional branch token, section suffix)
 * and the document's owning department supplies the branch when a group omits
 * the token. Anything that does not decompose cleanly (M.Tech, PHD, "Automatic
 * Group", ARCH, first-year-only groups) is excluded from year selection.
 */
object GroupMatcher {

    /** Canonical B.Tech branch codes used across the app (StudentDirectoryManager.BRANCHES). */
    val BRANCHES = listOf("CE", "CS", "EC", "EE", "IT", "ME", "RAI")

    /** Branch tokens that can appear inside FET group names, mapped to canonical codes. */
    private val BRANCH_TOKENS: Map<String, String> = mapOf(
        "CSE" to "CS", "CS" to "CS",
        "ECE" to "EC", "EC" to "EC",
        "EE" to "EE",
        "CE" to "CE",
        "IT" to "IT",
        "ME" to "ME",
        "RAI" to "RAI"
    )

    /** Uppercase + strip separators/punctuation so "D2 CS A", "D2IT_A", "D4CE (STR)",
     *  "d2-cs-a" all compare equal. */
    fun normalize(raw: String): String =
        raw.uppercase().replace(Regex("[\\s_\\-.()]"), "")

    /** A group name decomposed into its structural parts. */
    data class ParsedGroup(
        val raw: String,
        /** 1..4 from the leading "D1".."D4" token, null when the name carries no year. */
        val year: Int?,
        /** Canonical branch token found after the year, null when absent (e.g. EE's "D2A"). */
        val branch: String?,
        /** Everything after the year + branch tokens (section / stream / subgroup). */
        val section: String
    )

    fun parseGroup(raw: String): ParsedGroup {
        val n = normalize(raw)
        val year = Regex("^D([1-4])").find(n)?.groupValues?.get(1)?.toIntOrNull()
        if (year == null) return ParsedGroup(raw, null, null, "")
        var rest = n.substring(2)
        // Longest branch token at the front of the remainder wins ("ECE" before
        // "EC") — EXCEPT when the longest token swallows the whole remainder while
        // a shorter token leaves a plausible section: "D4CSE" is CS + section E,
        // not branch "CSE" with no section.
        val matches = BRANCH_TOKENS.keys.filter { rest.startsWith(it) }.sortedByDescending { it.length }
        val token = matches.firstOrNull()?.let { longest ->
            val longestLeft = rest.substring(longest.length)
            val shorter = matches.firstOrNull { it.length < longest.length }
            val shorterLeft = shorter?.let { rest.substring(it.length) }
            if (longestLeft.isEmpty() && shorterLeft != null && shorterLeft.length in 1..3) shorter else longest
        }
        val branch = token?.let { BRANCH_TOKENS.getValue(it) }
        if (token != null) rest = rest.substring(token.length)
        return ParsedGroup(raw, year, branch, rest)
    }

    /**
     * Groups of the owning department that belong to the given year.
     * A group qualifies when its year matches and either it carries the
     * department's branch token or it carries no branch token at all (EE style,
     * and elective groups like CE's "D4_EIA(OA)"). Groups of OTHER branches
     * published inside the same file (cross-department classes) are excluded,
     * as are non-B.Tech programmes published by the same department (BCA/MCA
     * sections carry no branch token and would otherwise leak into the senior
     * section picker).
     */
    fun groupsForYear(allGroups: List<String>, dept: String, year: Int): List<ParsedGroup> {
        require(dept in BRANCHES) { "unknown branch $dept" }
        return allGroups.map { parseGroup(it) }.filter { pg ->
            pg.year == year && (pg.branch == null || pg.branch == dept) && !isForeignProgramme(pg)
        }
    }

    /** Non-B.Tech programmes whose sections would otherwise match "no branch token". */
    private val FOREIGN_PROGRAMME_TOKENS = listOf("BCA", "MCA", "MBA")

    private fun isForeignProgramme(pg: ParsedGroup): Boolean {
        if (pg.year == null || pg.branch != null) return false
        return FOREIGN_PROGRAMME_TOKENS.any { pg.section.startsWith(it) }
    }

    /** Distinct section suffixes for (year, dept), in a human-friendly order. */
    fun sectionsForYear(allGroups: List<String>, dept: String, year: Int): List<String> =
        groupsForYear(allGroups, dept, year)
            .map { it.section }
            .distinct()
            .sortedWith(sectionOrder)

    private val sectionOrder = Comparator<String> { a, b ->
        val ka = sectionSortKey(a)
        val kb = sectionSortKey(b)
        if (ka != kb) ka.compareTo(kb) else a.compareTo(b)
    }

    private fun sectionSortKey(section: String): Int {
        if (section.isEmpty()) return -1 // "D4IT" — the only group of its year
        val letter = Regex("^[A-Z]").find(section)?.value ?: return 1000
        return letter[0].code - 'A'.code
    }

    /**
     * FET group names for (year, dept, section). `section` is one of
     * [sectionsForYear]; the empty string selects year groups with no section
     * suffix ("D4IT"). Returns groups in published order.
     */
    fun groupsForSection(allGroups: List<String>, dept: String, year: Int, section: String): List<String> =
        groupsForYear(allGroups, dept, year)
            .filter { it.section == normalize(section) }
            .map { it.raw }

    /**
     * True when the document plausibly contains this department's groups for
     * the given year — used to validate a discovered document before it may
     * replace the cached timetable.
     */
    fun hasGroupsFor(allGroups: List<String>, dept: String, year: Int): Boolean =
        groupsForYear(allGroups, dept, year).isNotEmpty()

    /**
     * Best group in [candidates] for [wanted]: the exact published name wins,
     * otherwise the group whose normalized form (case/separator-insensitive)
     * equals the wanted one. Returns null when nothing matches. Used to link a
     * group picked from the onboarding catalog to the group names actually
     * stored in the Room cache — the two can drift by punctuation/case only.
     */
    fun matchGroup(candidates: Collection<String>, wanted: String): String? {
        if (wanted.isBlank()) return null
        return candidates.firstOrNull { it == wanted }
            ?: candidates.firstOrNull { normalize(it) == normalize(wanted) }
    }

    /**
     * Self-heal for senior (2nd–4th year) profiles whose saved timetable group
     * no longer exists in the current departmental document — e.g. profiles
     * migrated from the 1st-year source with [studentSubsection] still holding
     * the group recorded at section-pick time. Only a real D2/D3/D4 group may
     * be re-linked; 1st-year-style names ("A1") can never match, so a stale
     * 1st-year subsection can never silently select a wrong lecture set.
     * Returns null when nothing plausible matches — the caller must fail
     * honestly instead of guessing.
     */
    fun relinkCandidate(groups: Collection<String>, storedGroup: String?): String? {
        val healed = matchGroup(groups, storedGroup.orEmpty().trim()) ?: return null
        return if (parseGroup(healed).year in 2..4) healed else null
    }

    /** Friendly chip label: plain letters stay "A"/"B"; exotic suffixes show as-is. */
    fun sectionLabel(section: String): String = section.ifEmpty { "All" }
}

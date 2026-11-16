package com.gndec.timetable.parse

/**
 * Matches faculty directory names to teacher-timetable table labels.
 *
 * Departments publish wildly different label conventions, and the directory's
 * spellings do not always agree with the timetable's:
 *
 *  - Applied Science / IT / CSE publish full names — but with spelling drift
 *    ("DR. KULVINDER SINGH MANN" vs "Dr. Kulwinder Singh Mann",
 *    "PRADEEP JASWAL" vs "Pardeep Jaiswal", "SATWINDER JIT" vs "SATWINDERJIT").
 *  - EE / ECE / ME publish a Table-of-contents label "Full Name (CODE)" or
 *    "CODE (Full Name)" while the caption is only the code.
 *  - CE publishes bare codes ("HVS") with no name anywhere in the document.
 *
 * The strategy (validated against all seven live files, ~95% of directory
 * faculty resolved):
 *
 *  1. TOC/caption full-name score — exact (100), no-space-equal (100),
 *     initial-per-token (85), leading-token-subset (78), given-name prefix (75),
 *     fuzzy spelling variant (70). A department annotation in the label
 *     ("(EE)", "(MPE)") penalises matches across departments; an agreeing
 *     honorific (Dr/Er/Pf) breaks ties.
 *  2. Code-subsequence pass — a code ("HVS") is accepted only when its letters
 *     appear in order inside exactly ONE remaining faculty name of that
 *     department, iterated to a fixpoint so earlier ambiguity resolves as
 *     stronger claims remove candidates.
 */
object FacultyNameMatcher {

    // "ar" (the architect's prefix) is deliberately NOT a title here: the
    // college files its School of Architecture teachers as "AR. X", and keeping
    // the prefix in the name keeps them apart from a teacher of another
    // department with the same name.
    private val TITLES = setOf("dr", "er", "prof", "professor", "pf", "mrs", "mr", "ms", "miss", "sh", "smt", "shri")

    /** Short uppercase codes that actually name a DEPARTMENT, not a teacher. */
    private val DEPT_CODES = mapOf(
        "ee" to "Electrical Engineering",
        "ec" to "Electronics & Communication Engineering",
        "ece" to "Electronics & Communication Engineering",
        "me" to "Mechanical Engineering",
        "mpe" to "Mechanical Engineering",
        "ce" to "Civil Engineering",
        "it" to "Information Technology",
        "cse" to "Computer Science & Engg.",
        "cs" to "Computer Science & Engg.",
        "ws" to "Workshops",
        "as" to "Applied Science",
        "aps" to "Applied Science",
        "appsc" to "Applied Science",
        "applied" to "Applied Science",
        "mba" to "Business Administration",
        "mca" to "Computer Applications",
        "ca" to "Computer Applications"
    )

    /**
     * Teacher codes that are NOT department codes. The Applied Science, CSE and
     * IT timetables label their teaching assistants "TA", "TA1", "TA2" - that is
     * the assistant's own code, not the department the table belongs to, so a
     * label like "ER. JASHANDEEP KAUR (TA)" must not be read as a Workshops
     * teacher.
     */
    private val TEACHER_CODES = setOf("ta", "ta1", "ta2", "ta3")

    // --- tokenisation ---------------------------------------------------------

    /** Lowercase alnum tokens with honorific titles removed. */
    fun nameTokens(name: String?): List<String> =
        name.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in TITLES }

    fun normName(name: String?): String = nameTokens(name).joinToString(" ")

    private fun labelTokens(label: String): List<String> =
        label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** True when [token] is an honorific title ("Dr", "Er", "Ms", …). */
    fun isTitle(token: String): Boolean = token.lowercase() in TITLES

    /** Honorific at the start of the name, or "" when absent. */
    fun titleOf(name: String?): String {
        val first = name.orEmpty().trim().split(Regex("[^A-Za-z]+")).firstOrNull()?.lowercase().orEmpty()
        return if (first in TITLES) first else ""
    }

    /** Department annotation embedded in a label: "(EE)", "JJK_ME", "(AppSc)". */
    fun deptHintOf(label: String): String? {
        for (token in labelTokens(label)) {
            DEPT_CODES[token]?.let { return it }
        }
        return null
    }

    /**
     * The department a bare teacher-table code stands for when it is a
     * DEPARTMENT code ("EE" → Electrical Engineering, "WS" → Workshops).
     * Real teacher codes ("KDS", "HVS", "TA1") return null.
     */
    fun deptOfCode(code: String?): String? =
        code?.trim()?.takeIf { it.isNotEmpty() }?.let { DEPT_CODES[it.lowercase()] }

    /** True when a word is a DEPARTMENT code ("me", "ce", "appsc") — not a name. */
    fun isDeptWord(token: String): Boolean = token.lowercase() in DEPT_CODES

    // --- label → (full name, code) --------------------------------------------

    private val PARENS_TAIL = Regex("""^([^(]+)\s*\(([^)]+)\)\s*$""")
    private val DASH_CODE = Regex("""[-–]\s*([A-Za-z]{2,5})\s*$""")

    /** True for a short uppercase teacher code ("KDS", "SSK", "HVS"). */
    fun isCode(s: String): Boolean {
        val core = s.replace("-", "").replace("_", "").replace(" ", "")
        return core.length in 2..5 && core.all { it.isLetter() } && core == core.uppercase()
    }

    private fun isNameLike(s: String): Boolean {
        val tokens = s.replace("_", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.size >= 2 || s.length >= 8
    }

    /**
     * Splits a TOC/caption label into its full-name and code parts:
     *  "SSK (Dr. Sehijpal Singh)"   → "Dr. Sehijpal Singh" to "SSK"
     *  "Dr. Kanwardeep Singh (KDS)" → "Dr. Kanwardeep Singh" to "KDS"
     *  "Dr. Akshay Girdhar-AG"      → "Dr. Akshay Girdhar" to "AG"
     *  "HVS"                        → null to "HVS"
     *  "GDKL(math)"                 → null to "GDKL"
     */
    fun extractFullName(label: String): Pair<String?, String?> {
        val trimmed = label.trim()
        PARENS_TAIL.matchEntire(trimmed)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            // right may be "CODE DEPT" ("JDP MPE") — the leading token is the code
            val rightParts = right.replace("_", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            val rightCore = rightParts.firstOrNull().orEmpty()
            if (isCode(rightCore)) {
                return (left.takeIf { isNameLike(it) }) to rightCore
            }
            val leftParts = left.replace("_", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            val leftCore = leftParts.firstOrNull().orEmpty()
            if (isCode(leftCore) && leftParts.size <= 2) {
                if (isNameLike(right)) return right to leftCore
                return null to leftCore // "GDKL(math)": neither side is a real name
            }
            if (isNameLike(left)) return left to null
            if (isNameLike(right)) return right to null
            return null to null
        }
        DASH_CODE.find(trimmed)?.let { m ->
            val base = trimmed.substring(0, m.range.first).trim()
            return (base.takeIf { isNameLike(it) }) to m.groupValues[1]
        }
        if (isCode(trimmed)) {
            return null to trimmed.replace("-", "").replace("_", "").replace(" ", "")
        }
        return trimmed to null
    }

    // --- full-name scoring ------------------------------------------------------

    /**
     * How well a published teacher name matches a directory name.
     * 100 exact · 100 space-insensitive · 85 initial-per-token · 78 leading
     * token-subset (in either direction) · 75 given-name prefix · 70/65 fuzzy
     * spelling variants.
     */
    fun nameScore(published: String, directory: String): Int {
        if (normName(published) == normName(directory)) return 100
        if (nameTokens(published).joinToString("") == nameTokens(directory).joinToString("")) return 100

        val cap = nameTokens(published)
        val dir = nameTokens(directory)
        if (cap.isEmpty() || dir.isEmpty()) return 0

        if (cap.size == dir.size) {
            if (cap.indices.all { i -> cap[i] == dir[i] || (cap[i].length == 1 && dir[i].startsWith(cap[i])) }) {
                return 85 // "D S PATHANIA" vs "DALJEEP SINGH PATHANIA"
            }
            if (cap.indices.all { i -> tokenSimilar(cap[i], dir[i], 0.8) }) return 70
            if (cap.indices.all { i -> tokenSimilar(cap[i], dir[i], 0.75) }) return 65
        }
        // "AMARJOT KAUR" is a leading subset of "AMARJOT KAUR RANDHAWA"
        if (cap.size < dir.size && dir.take(cap.size) == cap) return 78
        // ... and the other way round: the directory writes "SAMREET KAUR",
        // the file "Pf. Samreet Kaur Gosal" — the same teacher under a name
        // the file carries in full.
        if (dir.size < cap.size && cap.take(dir.size) == dir) return 78
        // "PRAGYA" is the given name of "PRAGYA GOYAL"; "Aman" of "AMANDEEP SINGH"
        if (cap.size == 1 && dir.size >= 2 && cap[0].length >= 4 && dir[0].startsWith(cap[0])) return 75
        return 0
    }

    // --- published spellings of one teacher ----------------------------------------

    /**
     * Score for a table whose label is one of a teacher's other published
     * spellings ("also_published_as" in the directory). The college is not
     * consistent about names - "Er. Jaswant Singh Taur" is the directory's
     * "ER. JASWANT SINGH", "DR APK SODHI" is "DR AMANPREET KAUR SODHI" - and
     * the alias list states which published spelling belongs to which teacher.
     * A curated alias therefore outranks a merely equal name.
     */
    private const val ALIAS_SCORE = 200

    /**
     * True when [published] is one of the teacher's other published spellings.
     * Compared on the normalised name, so "Dr. Jaswant Singh Taur" and
     * "ER. JASWANT SINGH TAUR" are the same published form.
     */
    fun matchesAlias(published: String, aliases: List<String>): Boolean {
        if (aliases.isEmpty()) return false
        val target = normName(published)
        if (target.isBlank()) return false
        return aliases.any { normName(it) == target }
    }

    // --- fuzzy tokens -------------------------------------------------------------

    private fun tokenSimilar(a: String, b: String, threshold: Double): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        val longer = maxOf(a.length, b.length)
        return damerau(a, b).toDouble() / longer <= (1 - threshold)
    }

    /** Damerau-Levenshtein distance (optimal string alignment). */
    fun damerau(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    // --- code-subsequence -----------------------------------------------------------

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) i++
        }
        return i == needle.length
    }

    // --- assignment -------------------------------------------------------------------

    /**
     * Assigns every teacher table it can trust to a directory record. The
     * returned map keys are normalised directory names; a key may be served by
     * several records (the directory occasionally lists one person twice).
     *
     * Pass 1 — full-name matches, honouring department annotations and
     * honorific tie-breaks, requiring a strictly best table per record. A
     * curated published spelling ("also_published_as") wins outright: the
     * college publishes that name for this teacher.
     * Pass 2 — bare-code subsequence matching with GLOBAL uniqueness, iterated
     * to a fixpoint. A code may only claim a table that pass 1 left unclaimed,
     * and only a teacher whose first initial the code starts with.
     */
    fun <T> assignTables(
        directoryNames: List<String>,
        directoryDepartments: List<String?>,
        teacherNames: List<String?>,
        teacherCodes: List<String?>,
        teacherLabels: List<String>,
        tableAt: (Int) -> T,
        directoryAliases: List<List<String>> = emptyList()
    ): Map<String, T> {
        val assignment = mutableMapOf<String, T>()
        /** Table indexes already handed to a teacher by the name pass. */
        val claimedTables = mutableSetOf<Int>()

        // Pass 1: full names.
        for (r in directoryNames.indices) {
            val dirName = directoryNames[r]
            val dirDept = directoryDepartments[r]
            val dirTitle = titleOf(dirName)
            val aliases = directoryAliases.getOrElse(r) { emptyList() }
            var best: T? = null
            var bestIndex = -1
            var bestScore = 0
            var tie = false
            for (t in teacherNames.indices) {
                val published = teacherNames[t] ?: continue
                var score = nameScore(published, dirName)
                if (score > 0) {
                    val hint = deptHintOf(teacherLabels[t])
                    if (hint != null && hint != dirDept) score = (score - 40).coerceAtLeast(0)
                    if (score > 0 && dirTitle.isNotEmpty() && titleOf(published) == dirTitle) score += 3
                }
                // A curated alias is authoritative - it names this teacher.
                if (matchesAlias(published, aliases)) score = ALIAS_SCORE
                if (score > bestScore) {
                    best = tableAt(t); bestIndex = t; bestScore = score; tie = false
                } else if (score == bestScore && score > 0) {
                    tie = true
                }
            }
            if (best != null && bestScore >= 65 && !tie) {
                assignment[normName(dirName)] = best
                claimedTables += bestIndex
            }
        }

        // Pass 2: codes, fixpoint.
        var changed = true
        while (changed) {
            changed = false
            for (t in teacherCodes.indices) {
                val code = teacherCodes[t] ?: continue
                if (code.length < 2) continue
                if (code.lowercase() in DEPT_CODES) continue
                if (code.lowercase() in TEACHER_CODES) continue
                // A table the name pass already claimed belongs to THAT teacher:
                // a second teacher must never steal it on a loose code match.
                if (t in claimedTables) continue
                val hits = directoryNames.filterIndexed { r, name ->
                    normName(name) !in assignment &&
                        isSubsequence(code.lowercase(), nameTokens(name).joinToString("")) &&
                        // A teacher's code always starts with their own first
                        // initial. Without this a short code ("AG", "PTS")
                        // matches every name that merely CONTAINS those letters
                        // in order, and the table is handed to the wrong
                        // teacher — "AG" fits SARABMEET SINGH MASSON as well as
                        // AKSHAY GIRDHAR.
                        code.first().lowercaseChar() ==
                            nameTokens(name).firstOrNull()?.firstOrNull()?.lowercaseChar()
                }
                if (hits.size == 1) {
                    assignment[normName(hits[0])] = tableAt(t)
                    claimedTables += t
                    changed = true
                }
            }
        }
        return assignment
    }
}

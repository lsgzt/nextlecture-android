package com.gndec.timetable.parse

/**
 * Normalizes GNDEC room names so that the same physical room published by
 * different departments merges into one entry ("F-119" (IT) == "F119" (college
 * file), "G-1" (CE) == "G1", "W/S SEMINAR HALL" == "WS SEMINAR HALL", …).
 *
 * Rules derived from the actual published files (see docs/ROOM_TIMETABLE_SOURCES.md):
 *  1. upper-case, collapse whitespace, strip punctuation and parenthetical
 *     location annotations ("G10 (MPE Dept.)" -> "G10");
 *  2. "X/L" lab shorthand expands to "X LAB" ("COMP/L(EC)" -> "COMP LAB EC");
 *  3. a small alias table unifies observed spelling variants of shared rooms;
 *  4. "letter prefix + number" rooms collapse separators ("F-119" -> "F119",
 *     "S-220" -> "S220", "G 3A" -> "G3A").
 *
 * Merging errs towards BUSY (the safe direction): a wrongly merged pair of
 * names can only over-report occupancy, never show an occupied room vacant.
 */
object RoomNameNormalizer {

    /** Observed spelling variants of the same physical room -> canonical key. */
    private val ALIASES: Map<String, String> = mapOf(
        "WS SEMINAR HALL" to "W S SEMINAR HALL",
        "W SHOP SEM HALL" to "W S SEMINAR HALL",
        "W S SEM HALL" to "W S SEMINAR HALL",
        "SEM HALL" to "SEMINAR HALL BA",
        "MEAS LAB" to "MEASUREMENT LAB",
        "ADV MEAS LAB" to "ADVANCE MEASUREMENT LAB",
        "F102 AUTO BLK" to "F102",
        "MBA COMP LAB" to "COMP LAB MBA"
    )

    /**
     * FET pseudo-rooms that never represent a bookable physical room. They are
     * parsed (their occupancy is harmless) but hidden from the room list.
     */
    private val PLACEHOLDERS: Set<String> = setOf(
        "GHOST ROOM", "TEACH OFFICE", "TEACH OFFICE1", "FACULTY ROOM",
        "A OTHER DEPTT", "B OTHER DEPTT", "C OTHER DEPTT", "FIRST YEAR ROOM",
        "A", "B", "C"
    )

    private val LAB_SHORTHAND = Regex("""^(.+?)\s*/\s*L\s*(\([^)]*\))?$""")
    private val PARENS = Regex("""\(([^)]*)\)""")
    private val LAB_ANNOTATION = Regex("""^[A-Z]+\s*LAB\s*\S+$""")
    private val PUNCT = Regex("""[-_.,/]""")
    private val WHITESPACE = Regex("""\s+""")
    private val LETTER_NUMBER = Regex("""^([A-Z]{1,3})[\s\-_]*(\d{1,4}[A-Z]{0,2})$""")

    /** Normalized merge key, or null when the name carries no usable token. */
    fun canonical(raw: String): String? {
        var s = raw
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .uppercase()
            .trim()
        if (s.isEmpty()) return null
        s = WHITESPACE.replace(s, " ").trim().trimEnd('.').trim()
        val shorthand = LAB_SHORTHAND.find(s)
        if (shorthand != null) {
            // "COMP/L(EC)" -> "COMP LAB EC": the annotation disambiguates two
            // departments' labs, so it becomes part of the name.
            val annotation = shorthand.groupValues[2]
                .filter { it != '(' && it != ')' }
                .trim()
            s = (shorthand.groupValues[1] + " LAB " + annotation).trim()
        }
        // Remaining parenthesized segments: room-lab aliases like
        // "PE LAB (BEE LAB 2)" keep their content; location annotations like
        // "(AUTOMOBILE BLOCK)" or "(NR)" are dropped.
        s = PARENS.replace(s) { m ->
            val inner = m.groupValues[1].trim()
            if (LAB_ANNOTATION.matches(inner)) " $inner " else " "
        }
        s = PUNCT.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()
        if (s.isEmpty()) return null
        ALIASES[s]?.let { s = it }
        val letterNumber = LETTER_NUMBER.matchEntire(s)
        if (letterNumber != null) {
            s = letterNumber.groupValues[1] + letterNumber.groupValues[2]
        }
        return s
    }

    fun isPlaceholder(canonicalKey: String): Boolean = canonicalKey in PLACEHOLDERS

    /** True for numbered classroom names like F119, G-1, S-220, A6, F112A. */
    fun isRoomLike(canonicalKey: String): Boolean =
        Regex("""^[A-Z]{1,3}\d""").containsMatchIn(canonicalKey)

    /**
     * Search matching tolerant of separators and partial numbers: "F19" finds
     * F119 (same block, digits ending with the query digits), "f-19" finds
     * F-119/F119, "sem hall" finds SEMINAR HALL BA.
     */
    fun matches(roomName: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (roomName.contains(q, ignoreCase = true)) return true
        val qKey = canonical(q) ?: return false
        val nKey = canonical(roomName) ?: return false
        if (nKey.contains(qKey)) return true
        // "F19" must find F119: same block prefix, one number ending in the other.
        val qMatch = LETTER_NUMBER.matchEntire(qKey)
        if (qMatch != null) {
            val nMatch = LETTER_NUMBER.matchEntire(nKey)
            if (nMatch != null && nMatch.groupValues[1] == qMatch.groupValues[1]) {
                val qDigits = qMatch.groupValues[2].takeWhile { it.isDigit() }
                val nDigits = nMatch.groupValues[2].takeWhile { it.isDigit() }
                if (qDigits.isNotEmpty() && nDigits.isNotEmpty() &&
                    (nDigits == qDigits || nDigits.endsWith(qDigits) || qDigits.endsWith(nDigits))
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Picks the user-facing display name among the raw names a merged room was
     * published under, preferring the college-wide file, then the shortest name.
     */
    fun displayName(candidates: List<Pair<Int, String>>): String {
        // candidates: (rootPriority, rawName) — lower priority value wins.
        return candidates.minWithOrNull(
            compareBy({ it.first }, { it.second.length }, { it.second })
        )?.second ?: ""
    }
}

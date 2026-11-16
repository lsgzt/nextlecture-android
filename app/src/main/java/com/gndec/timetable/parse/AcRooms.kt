package com.gndec.timetable.parse

/**
 * The college's air-conditioned rooms. The published timetables carry no AC
 * information, so this list is maintained by hand (GNDEC, 2026 session):
 *
 *  F101 · F108 · F109 · F112 · F113 · F114 · CHEM LAB 1 · TNP SEMINAR HALL ·
 *  Central Library · BEE LAB 1 · OS1 LAB IT DEPT · COMPUTATIONAL LAB ·
 *  OS LAB CSE DEPT · OS LAB · CSE · PROJ/L · HW Lab
 *
 * AC rooms deliberately get NO screen or section of their own: they are simply
 * marked with a snowflake in front of their name wherever rooms are listed
 * ("❄️F108"), and a query that contains the snowflake matches exactly the AC
 * rooms — so typing ❄️ in a search field lists all of them.
 *
 * Rooms are identified by their canonical MERGE key ([RoomNameNormalizer.canonical]
 * of the published names). Generic names that several departments publish under
 * the same spelling are merged per department, so their key is the scoped form
 * "dept:NAME" (CSE's OS lab merges as "cse:OS LAB", displayed "OS LAB · CSE").
 */
object AcRooms {

    /** The snowflake glyph itself, without the emoji variation selector. */
    private const val SNOWFLAKE_CHAR = '❄' // U+2744

    /** Badge shown in front of an air-conditioned room's name (emoji presentation). */
    const val SNOWFLAKE = "❄️" // U+2744 + U+FE0F

    /** Joins a scoped room's base name and department label, mirroring RoomMerger. */
    private const val SCOPED_SEPARATOR = " · " // space + U+00B7 + space

    /** Inversion of RoomMerger.rootLabel: display label -> discovery root id. */
    private val ROOT_ID_BY_LABEL = mapOf(
        "COLLEGE" to "appsc", "CSE" to "cse", "ECE" to "ece", "EE" to "ee",
        "ME" to "me", "CE" to "ce", "IT" to "it", "MCA" to "mca", "MBA" to "mba"
    )

    /**
     * Canonical merge keys of every AC room. "PROJ/L" merges as "PROJ LAB",
     * "Central Library" as "CENTRAL LIBRARY", "HW Lab" as "HW LAB", and CSE's
     * OS lab — published "OS LAB" by both CSE and IT and therefore scoped —
     * as "cse:OS LAB" (the IT variant "OS LAB · IT" is a different room).
     */
    val AC_KEYS: Set<String> = setOf(
        "F101", "F108", "F109", "F112", "F113", "F114",
        "BEE LAB 1",
        "CHEM LAB 1",
        "COMPUTATIONAL LAB",
        "HW LAB",
        "OS LAB CSE DEPT",
        "OS1 LAB IT DEPT",
        "PROJ LAB",
        "TNP SEMINAR HALL",
        "CENTRAL LIBRARY",
        "cse:OS LAB"
    )

    /** True when a merged room with this canonical [mergeKey] is air-conditioned. */
    fun isAcRoom(mergeKey: String): Boolean = mergeKey in AC_KEYS

    /**
     * True when [displayName] — "F108", "Central Library", "PROJ/L", or a scoped
     * "OS LAB · CSE" — belongs to an AC room. Scoped display names are resolved
     * back to their "dept:NAME" merge key so "OS LAB · CSE" is AC while the
     * IT-published "OS_Lab · IT" is not.
     */
    fun isAcRoomName(displayName: String): Boolean {
        var n = displayName.trim()
        if (n.contains(SNOWFLAKE_CHAR)) n = stripSnowflake(n).trim()
        if (n.isEmpty()) return false
        val scopedAt = n.lastIndexOf(SCOPED_SEPARATOR)
        if (scopedAt >= 0) {
            val base = n.substring(0, scopedAt).trim()
            val label = n.substring(scopedAt + SCOPED_SEPARATOR.length).trim().uppercase()
            val baseKey = RoomNameNormalizer.canonical(base) ?: return false
            val rootId = ROOT_ID_BY_LABEL[label] ?: label.lowercase()
            return "$rootId:$baseKey" in AC_KEYS
        }
        val key = RoomNameNormalizer.canonical(n) ?: return false
        return key in AC_KEYS
    }

    /** AC check for a merged room whose display name and (optional) key are known. */
    fun isAc(displayName: String, mergeKey: String? = null): Boolean =
        (mergeKey != null && mergeKey in AC_KEYS) || isAcRoomName(displayName)

    /** [displayName] with the snowflake badge in front when the room has AC. */
    fun badge(displayName: String, mergeKey: String? = null): String =
        if (isAc(displayName, mergeKey)) SNOWFLAKE + displayName else displayName

    /** True when [query] contains the snowflake, with or without variation selector. */
    fun hasSnowflake(query: String): Boolean = query.contains(SNOWFLAKE_CHAR)

    /** [query] with every snowflake glyph and variation selector removed. */
    fun stripSnowflake(query: String): String =
        query.filter { it != SNOWFLAKE_CHAR && it != '\uFE0F' }

    /** True when [query] is nothing but snowflakes and whitespace ("❄️", "❄ ❄"). */
    fun isSnowflakeOnly(query: String): Boolean =
        hasSnowflake(query) && stripSnowflake(query).isBlank()
}

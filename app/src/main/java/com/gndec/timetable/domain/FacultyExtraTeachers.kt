package com.gndec.timetable.domain

import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultyNameMatcher
import com.gndec.timetable.parse.ParsedFacultyDoc
import com.gndec.timetable.parse.TeacherTable

/**
 * Profile ids of teachers that exist ONLY in a department timetable: every
 * such id looks like "tt-<rootId>-<nameKey>" (e.g. "tt-appsc-manpreet-kaur")
 * and can never collide with the numeric ids of the bundled directory.
 */
const val ExtraTeacherIdPrefix = "tt-"

/**
 * A teacher the department timetables list but the bundled college directory
 * (the official website's faculty list) does not carry — for example a
 * "Ms. MANPREET KAUR." table in the Applied Science file with no directory
 * record anywhere. [record] is the synthetic directory-shaped entry built for
 * search and navigation; [table] is the parsed weekly schedule that proves
 * the teacher exists.
 */
data class ExtraFacultyTeacher(
    val record: FacultyRecord,
    /** Root id of the department document the table came from. */
    val rootId: String,
    val table: TeacherTable
)

/**
 * Pure discovery of timetable-only teachers: runs the same directory matcher
 * the detail screen uses, then explains every UNCLAIMED teacher table as one
 * of these cases:
 *
 *  1. The label carries a department code ("DR. X (EE)" while the file is the
 *     Applied Science one) — a visiting teacher from another department.
 *     When that other department's directory records include the name, the
 *     teacher is known and their own department's page serves them: skip.
 *     Otherwise the extra is attributed to the annotated department.
 *  2. A plain label ("Ms. MANPREET KAUR.") that matched no directory record
 *     of this department — the teacher is simply not in the directory. When
 *     a directory record of THIS department matches loosely (spelling drift
 *     like "PRADEEP JASWAL" vs "PARDEEP JASWAL", or a fuzzy tie), the label
 *     still belongs to that known teacher: skip. Otherwise the extra is
 *     attributed to this document's department.
 *
 * Tables without any full name (Civil publishes bare codes like "HVS") can
 * never become searchable extras — there is nothing to look up.
 */
object FacultyExtraDiscovery {

    /**
     * Directory-shaped display name from a published timetable label:
     * "Ms. MANPREET KAUR." → "MS. MANPREET KAUR" (the same style the
     * directory stores, so `prettyName` renders "Ms. Manpreet Kaur").
     * Returns null when nothing name-like remains.
     */
    fun directoryStyleName(raw: String): String? {
        val tokens = raw.split(Regex("[^A-Za-z]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val title = tokens.firstOrNull()?.takeIf { FacultyNameMatcher.isTitle(it) }
            ?.let { TITLE_DISPLAY[it.lowercase()] ?: "${it.uppercase()}." }
        val words = tokens.filterNot { FacultyNameMatcher.isTitle(it) }
        if (words.isEmpty()) return null
        return (listOfNotNull(title) + words.map { it.uppercase() }).joinToString(" ")
    }

    /**
     * Synthetic profile id for a discovered teacher: stable across restarts
     * and republishes because it derives from the root and the normalised
     * name, never from a table index.
     */
    fun syntheticProfileId(rootId: String, name: String): String =
        ExtraTeacherIdPrefix + rootId + "-" +
            FacultyNameMatcher.normName(name).replace(' ', '-')

    /** All timetable-only teachers of one department document. */
    fun discoverForDoc(
        doc: ParsedFacultyDoc,
        root: RoomSourceRoot,
        directory: List<FacultyRecord>
    ): List<ExtraFacultyTeacher> {
        val rootPool = directory.filter { FacultyDirectoryManager.rootForDepartment(it.department) == root }
        val rootDepartment = FacultyDirectoryManager.departmentForRoot(root)

        val labels = doc.tables.map { listOfNotNull(it.teacherName, it.code).joinToString(" ") }
        val assignment = FacultyNameMatcher.assignTables(
            directoryNames = rootPool.map { it.name },
            directoryDepartments = rootPool.map { it.department },
            teacherNames = doc.tables.map { it.teacherName },
            teacherCodes = doc.tables.map { it.code },
            teacherLabels = labels,
            tableAt = { doc.tables[it] },
            directoryAliases = rootPool.map { it.alsoPublishedAs }
        )
        val claimed = assignment.values.toSet()

        // Best raw nameScore each directory record of this department sees over
        // the whole document — mirrors the matcher's own strict-best rule.
        val bestPerRecord = rootPool.associateWith { record ->
            doc.tables.maxOfOrNull { table ->
                FacultyNameMatcher.nameScore(table.teacherName.orEmpty(), record.name)
            } ?: 0
        }

        // The college files a few teachers under TWO departments (Harpuneet
        // Singh in Mechanical and Production Engineering) and keeps one e-mail
        // address for them, so the directory holds two records of ONE person.
        // Count people, not records: a visiting teacher who is listed twice is
        // still one known teacher, while three DIFFERENT Harminder Kaurs stay
        // ambiguous.
        val people = groupByPerson(directory)

        val out = mutableListOf<ExtraFacultyTeacher>()
        val seen = mutableSetOf<String>()
        for (table in doc.tables) {
            if (table in claimed) continue
            val published = table.teacherName ?: continue
            val display = directoryStyleName(published) ?: continue
            if (isAcronymJunk(display)) continue

            val annotatedDepartment = FacultyNameMatcher.deptOfCode(table.code)
            val department = annotatedDepartment ?: rootDepartment
            // A curated published spelling names this teacher outright.
            val aliasHit = directory.any { FacultyNameMatcher.matchesAlias(published, it.alsoPublishedAs) }
            val known = aliasHit || if (annotatedDepartment != null) {
                // The label itself says which department the teacher belongs to;
                // a similar name in that department attributes the table to them
                // (their own department's page serves the full week).
                val knownPool = directory.filter { it.department == annotatedDepartment }
                knownPool.any { FacultyNameMatcher.nameScore(published, it.name) >= 65 }
            } else {
                val uniqueGlobalHit = people.count { person ->
                    person.any { record -> FacultyNameMatcher.nameScore(published, record.name) >= 85 }
                } == 1
                // Plain label: the assignment already claimed every record's
                // unambiguous best table of THIS department. A candidate that
                // only FUZZY-matches a record whose own (better) table exists is
                // a different person — "MANPREET KAUR" vs "SUMANPREET KAUR" —
                // and must stay discoverable; a candidate that TIES as a
                // record's best is the same person published twice, and stays
                // skipped.
                uniqueGlobalHit || rootPool.any { record ->
                    val score = FacultyNameMatcher.nameScore(published, record.name)
                    score >= 65 && score >= (bestPerRecord[record] ?: 0)
                }
            }
            if (known) continue

            val key = FacultyNameMatcher.normName(display)
            if (key.isBlank() || !seen.add("$department|$key")) continue
            out += ExtraFacultyTeacher(
                record = FacultyRecord(
                    name = display,
                    department = department,
                    profileId = syntheticProfileId(doc.rootId, display)
                ),
                rootId = doc.rootId,
                table = table
            )
        }
        return out
    }

    /**
     * Merges the per-document extras of several roots. The same visiting
     * teacher can appear unclaimed in more than one department file; the
     * entry published by their OWN department (the richer, authoritative
     * week) wins, and ties keep the first entry in [all]'s order.
     */
    fun mergeExtras(all: List<ExtraFacultyTeacher>): List<ExtraFacultyTeacher> {
        val byKey = LinkedHashMap<String, ExtraFacultyTeacher>()
        for (extra in all) {
            val key = "${extra.record.department}|${FacultyNameMatcher.normName(extra.record.name)}"
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = extra
                continue
            }
            val homeRootId = FacultyDirectoryManager.rootForDepartment(extra.record.department)?.id
            val extraIsHome = extra.rootId == homeRootId
            val existingIsHome = existing.rootId == homeRootId
            if (extraIsHome && !existingIsHome) byKey[key] = extra
        }
        return byKey.values.sortedBy { it.record.name }
    }

    /** Honorific as stored by the directory ("DR.", "MS.", …). */
    private val TITLE_DISPLAY = mapOf(
        "dr" to "DR.", "er" to "ER.", "prof" to "PROF.", "professor" to "PROF.",
        "pf" to "PF.", "ms" to "MS.", "mrs" to "MRS.", "mr" to "MR.", "miss" to "MISS.",
        "sh" to "SH.", "smt" to "SMT.", "shri" to "SHRI."
    )

    /**
     * Splits the directory into people: the college files a few teachers under
     * two departments and keeps one e-mail address for them, so records that
     * share an address are one person.
     */
    private fun groupByPerson(directory: List<FacultyRecord>): List<List<FacultyRecord>> {
        val groups = mutableListOf<MutableList<FacultyRecord>>()
        for (record in directory) {
            val addresses = record.email.split(',', ';').map { it.trim().trimEnd('.').lowercase() }
                .filter { it.isNotEmpty() }
            val group = groups.firstOrNull { g ->
                g.any { other ->
                    val otherAddresses = other.email.split(',', ';').map { it.trim().trimEnd('.').lowercase() }
                    addresses.any { it in otherAddresses }
                }
            }
            if (group != null) group += record else groups += mutableListOf(record)
        }
        return groups
    }

    /**
     * Acronym-shaped junk ("PDT", "CH", "X", "ME2") — a "name" whose every
     * token is a vowel-less uppercase code or a department word is a timetable
     * pseudo-entry that slipped through as a label, not a person. Real names
     * always carry vowels.
     */
    private fun isAcronymJunk(display: String): Boolean {
        val tokens = display.split(Regex("[^A-Za-z]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        if (tokens.all { FacultyNameMatcher.isDeptWord(it) }) return true
        return tokens.all { token ->
            token.length <= 5 && token.all { it.isLetter() } && token.uppercase() == token &&
                token.none { it in "aeiouAEIOU" }
        }
    }
}

package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.FacultyTimetableClient
import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultyNameMatcher
import com.gndec.timetable.parse.FacultySlot
import com.gndec.timetable.parse.ParsedFacultyDoc
import com.gndec.timetable.parse.TeacherTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** A teacher's resolved weekly schedule, ready for display. */
data class FacultySchedule(
    val faculty: FacultyRecord,
    val table: TeacherTable,
    val sourceUrl: String,
    val sourceRootId: String,
    val generatedAtMillis: Long?,
    val fetchedAtMillis: Long
) {
    val slots: List<FacultySlot> get() = table.slots

    fun slotsForDay(day: Int): List<FacultySlot> = slots.filter { it.day == day }

    val publishedName: String? get() = table.teacherName
}

/**
 * What the teacher is doing right now.
 *
 * "Free right now" is only claimed from a table that actually covers today —
 * a teacher with no classes listed today is shown as free only when today is
 * one of the published weekdays; on a weekend no claim is made at all.
 */
sealed interface FacultyStatus {
    data class Busy(val slot: FacultySlot, val untilMinutes: Int) : FacultyStatus
    data class FreeWithNextClass(val slot: FacultySlot) : FacultyStatus
    data object FreeRestOfDay : FacultyStatus
    data object NoClassesToday : FacultyStatus
    data object Weekend : FacultyStatus
}

/** Outcome of resolving one faculty member's timetable. */
sealed interface FacultyTimetableResult {
    data class Ready(val schedule: FacultySchedule) : FacultyTimetableResult

    /** Honest absence: the department does not publish this teacher's schedule. */
    data object NotPublished : FacultyTimetableResult

    /** The department is covered but the live document could not be fetched. */
    data class Unavailable(val reason: String) : FacultyTimetableResult
}

/**
 * Keeps the per-department teacher timetables loaded, cached and current.
 *
 * The directory's contact details are ALWAYS available offline (bundled in the
 * APK); only the weekly schedule needs the network. Each department root is
 * loaded lazily — the first search hit on a teacher of that department
 * discovers the newest published document, parses it and caches the parsed
 * snapshot on disk for [FRESH_WINDOW_MS]. A cached document is re-served
 * instantly while a refresh runs in the background, mirroring the
 * vacant-rooms manager's behaviour.
 */
class FacultyTimetableManager(
    context: Context,
    private val directory: FacultyDirectoryManager,
    private val client: FacultyTimetableClient = FacultyTimetableClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    private val loadMutex = Mutex()

    private val memory = mutableMapOf<RoomSourceRoot, ParsedFacultyDoc?>()
    private val inFlight = mutableMapOf<RoomSourceRoot, Mutex>()

    /** Ready schedules this process already built, keyed by profile id, so
     * re-opening a teacher's detail page is instant (no loading flash). */
    private val resultMemory = mutableMapOf<String, FacultyTimetableResult.Ready>()

    /** Per-document name→table assignment so opening a second teacher in the
     * same department does not re-run the full matcher on the UI path. */
    private val assignmentMemory = mutableMapOf<String, Map<String, TeacherTable>>()

    /** The directory + timetable-only-extras teacher pool, as search and the
     * browser show it — built once per process so returning from a detail
     * page renders the list synchronously (and keeps its scroll position). */
    @Volatile private var combinedSnapshot: List<FacultyRecord>? = null

    // Disk caches are read on demand from [ensureDoc] on Dispatchers.IO —
    // warming every department on the main thread froze the faculty list open.

    /**
     * Resolves one teacher's schedule. Departments without a published teacher
     * timetable return [FacultyTimetableResult.NotPublished] immediately
     * (offline) — the detail screen then shows the directory's contact details
     * plus the "timetable not available" note.
     *
     * Teachers that exist only inside a department timetable (synthetic
     * "tt-…" ids, see [extraTeachersFromMemory]) resolve through the same
     * discovery that produced their record.
     */
    suspend fun load(faculty: FacultyRecord, forceRefresh: Boolean = false): FacultyTimetableResult {
        if (!forceRefresh) {
            synchronized(resultMemory) { resultMemory[faculty.profileId] }?.let { return it }
        }
        // Name matching and JSON/doc work must not run on Main — it froze the
        // detail-screen enter animation for large department files.
        val result = withContext(Dispatchers.Default) {
            loadFresh(faculty, forceRefresh)
        }
        if (result is FacultyTimetableResult.Ready) {
            synchronized(resultMemory) { resultMemory[faculty.profileId] = result }
        }
        return result
    }

    /** A Ready schedule this process already resolved for [profileId], or null
     * — the detail screen seeds its state from this so a revisit shows the
     * timetable on the first frame instead of the loading card. */
    fun cachedResult(profileId: String): FacultyTimetableResult.Ready? =
        synchronized(resultMemory) { resultMemory[profileId] }

    /** The teacher pool (bundled directory + timetable-only extras) already in
     * memory, or null when it has not been built yet. Synchronous, main-thread
     * safe: call to seed a list's initial state. */
    fun combinedFacultyFast(): List<FacultyRecord>? = combinedSnapshot

    /** Builds (or returns the cached) teacher pool. Call from a background
     * dispatcher the first time — parsing the bundled JSON on the main thread
     * freezes the UI. Afterwards [combinedFacultyFast] is fine on main. */
    fun combinedFaculty(): List<FacultyRecord> {
        combinedSnapshot?.let { return it }
        val extras = extraTeachersFromMemory().map { it.record }
        val combined = directory.directory + extras
        combinedSnapshot = combined
        return combined
    }

    private suspend fun loadFresh(faculty: FacultyRecord, forceRefresh: Boolean): FacultyTimetableResult {
        if (faculty.profileId.startsWith(ExtraTeacherIdPrefix)) {
            return loadExtraTeacher(faculty.profileId, forceRefresh)
        }
        val root = FacultyDirectoryManager.rootForDepartment(faculty.department)
            ?: return FacultyTimetableResult.NotPublished

        val doc = ensureDoc(root, forceRefresh)
            ?: return FacultyTimetableResult.Unavailable(
                "The ${faculty.department} teacher timetable could not be loaded. Check your connection and try again."
            )

        val table = matchTable(root, doc, faculty) ?: return FacultyTimetableResult.NotPublished
        return FacultyTimetableResult.Ready(
            FacultySchedule(
                faculty = faculty,
                table = table,
                sourceUrl = doc.url,
                sourceRootId = doc.rootId,
                generatedAtMillis = doc.generatedAtMillis,
                fetchedAtMillis = doc.fetchedAtMillis
            )
        )
    }

    /** Doc from cache when fresh, else discover + download + parse. */
    private suspend fun ensureDoc(root: RoomSourceRoot, force: Boolean): ParsedFacultyDoc? {
        val mutex = synchronized(inFlight) { inFlight.getOrPut(root) { Mutex() } }
        return mutex.withLock {
            var cached = synchronized(this@FacultyTimetableManager) { memory[root] }
            if (cached == null) {
                cached = withContext(Dispatchers.IO) { readCache(root) }
                if (cached != null) {
                    synchronized(this@FacultyTimetableManager) { memory[root] = cached }
                }
            }
            val fresh = cached != null && System.currentTimeMillis() - cached!!.fetchedAtMillis < FRESH_WINDOW_MS
            if (!force && fresh) return@withLock cached
            if (!force && cached != null) return@withLock cached

            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val candidates = client.discoverTeacherCandidates(root)
                    client.fetchTeacherDoc(root, candidates)
                }.getOrNull()
            }
            // A failed refresh keeps the previously cached document usable.
            if (fetched != null) {
                synchronized(this@FacultyTimetableManager) { memory[root] = fetched }
                runCatching { writeCache(root, fetched) }
                fetched
            } else {
                cached
            }
        }
    }

    /**
     * Loads every department's teacher document (cache-first, exactly like
     * [load]) so the teachers that exist only in the timetables can be
     * discovered. Safe to call repeatedly: fresh documents are not refetched.
     */
    suspend fun ensureAllDocs(forceRefresh: Boolean = false): List<ParsedFacultyDoc?> = coroutineScope {
        val docs = ROOTS.map { root ->
            async(Dispatchers.IO) {
                runCatching { ensureDoc(root, forceRefresh) }.getOrNull()
            }
        }.awaitAll()
        // Fresh documents can carry teachers the pool has not seen yet —
        // rebuild the snapshot on a worker thread instead of leaving it null
        // so list screens do not flash empty.
        withContext(Dispatchers.Default) {
            val extras = extraTeachersFromMemory().map { it.record }
            combinedSnapshot = directory.directory + extras
        }
        docs
    }

    /**
     * Teachers the timetables list but the directory does not — computed from
     * whatever documents are already in memory (the disk cache is warmed at
     * construction, so this works offline after the first fetch). Cheap string
     * matching over the cached docs; call from a background dispatcher.
     */
    fun extraTeachersFromMemory(): List<ExtraFacultyTeacher> {
        val docs = synchronized(this@FacultyTimetableManager) { memory.values.filterNotNull().toList() }
        if (docs.isEmpty()) return emptyList()
        val directoryRecords = directory.directory
        val perDoc = docs.map { doc ->
            val root = ROOTS.firstOrNull { it.id == doc.rootId } ?: return@map emptyList()
            FacultyExtraDiscovery.discoverForDoc(doc, root, directoryRecords)
        }
        return FacultyExtraDiscovery.mergeExtras(perDoc.flatten())
    }

    /** The timetable-only teacher with this synthetic "tt-…" id, if loaded. */
    fun extraTeacherById(profileId: String): ExtraFacultyTeacher? =
        extraTeachersFromMemory().firstOrNull { it.record.profileId == profileId }

    /** Resolves a synthetic "tt-…" teacher back to its table in the live doc. */
    private suspend fun loadExtraTeacher(profileId: String, force: Boolean): FacultyTimetableResult {
        val rootId = profileId.removePrefix(ExtraTeacherIdPrefix).substringBefore('-')
        val root = ROOTS.firstOrNull { it.id == rootId }
            ?: return FacultyTimetableResult.NotPublished
        val doc = ensureDoc(root, force)
            ?: return FacultyTimetableResult.Unavailable(
                "The teacher timetable could not be loaded. Check your connection and try again."
            )
        val extra = FacultyExtraDiscovery.discoverForDoc(doc, root, directory.directory)
            .firstOrNull { it.record.profileId == profileId }
            // The college may have renamed or removed the teacher's table in a
            // newer document; the directory has no record either way.
            ?: return FacultyTimetableResult.NotPublished
        return FacultyTimetableResult.Ready(
            FacultySchedule(
                faculty = extra.record,
                table = extra.table,
                sourceUrl = doc.url,
                sourceRootId = doc.rootId,
                generatedAtMillis = doc.generatedAtMillis,
                fetchedAtMillis = doc.fetchedAtMillis
            )
        )
    }

    /**
     * Runs the matcher for one root's whole pool (the ambiguity resolution in
     * [FacultyNameMatcher] needs every candidate, not just the requested
     * teacher) and picks this teacher's table. Same-name records of the same
     * department share one table.
     */
    private fun matchTable(root: RoomSourceRoot, doc: ParsedFacultyDoc, faculty: FacultyRecord): TeacherTable? {
        val pool = directory.poolForRoot(root)
        if (pool.isEmpty()) return null
        val cacheKey = "${root.id}|${doc.fetchedAtMillis}|${doc.tables.size}|${pool.size}"
        val assignment = synchronized(assignmentMemory) {
            assignmentMemory.getOrPut(cacheKey) {
                val labels = doc.tables.map { listOfNotNull(it.teacherName, it.code).joinToString(" ") }
                FacultyNameMatcher.assignTables(
                    directoryNames = pool.map { it.name },
                    directoryDepartments = pool.map { it.department },
                    teacherNames = doc.tables.map { it.teacherName },
                    teacherCodes = doc.tables.map { it.code },
                    teacherLabels = labels,
                    tableAt = { doc.tables[it] }
                )
            }
        }
        return assignment[FacultyNameMatcher.normName(faculty.name)]
    }

    // --- disk cache ------------------------------------------------------------

    @Serializable
    private data class CachedDoc(val doc: ParsedFacultyDoc)

    private fun cacheFile(root: RoomSourceRoot): File = File(cacheDir, "${root.id}.json")

    private fun readCache(root: RoomSourceRoot): ParsedFacultyDoc? = runCatching {
        val file = cacheFile(root)
        if (!file.isFile) return null
        json.decodeFromString<CachedDoc>(file.readText()).doc
    }.getOrNull()

    private fun writeCache(root: RoomSourceRoot, doc: ParsedFacultyDoc) {
        runCatching {
            val file = cacheFile(root)
            val tmp = File(cacheDir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedDoc.serializer(), CachedDoc(doc)))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(CachedDoc.serializer(), CachedDoc(doc)))
                tmp.delete()
            }
        }
    }

    companion object {
        /** Roots that publish a teacher timetable (MCA/MBA do not publish one). */
        private val ROOTS = listOf(
            RoomSourceRoot.APPSC, RoomSourceRoot.CSE, RoomSourceRoot.ECE, RoomSourceRoot.EE,
            RoomSourceRoot.ME, RoomSourceRoot.CE, RoomSourceRoot.IT
        )

        private const val CACHE_DIR = "faculty_timetables_v1"

        /** The college republishes roughly weekly; half a working day is cheap. */
        const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

        /**
         * What the teacher is doing at [nowMinutes] on [today]. A teacher whose
         * table shows nothing today still counts as covered when the document
         * publishes that weekday.
         */
        fun statusAt(schedule: FacultySchedule, today: LocalDate, nowMinutes: Int): FacultyStatus {
            val dow = today.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return FacultyStatus.Weekend

            val day = dow.value - 1
            val todaySlots = schedule.slotsForDay(day).sortedBy { it.startMinutes }
            val current = todaySlots.lastOrNull { it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes }
            if (current != null) return FacultyStatus.Busy(current, untilMinutes = current.endMinutes)
            val next = todaySlots.firstOrNull { it.startMinutes > nowMinutes }
            return when {
                next != null -> FacultyStatus.FreeWithNextClass(next)
                todaySlots.isNotEmpty() -> FacultyStatus.FreeRestOfDay
                else -> FacultyStatus.NoClassesToday
            }
        }
    }
}

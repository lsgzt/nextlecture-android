package com.gndec.timetable.domain

import com.gndec.timetable.data.db.AppDatabase
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.db.TimetableMetaEntity
import com.gndec.timetable.data.db.TimetableSnapshotEntity
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.DeptGroupSourceResolver
import com.gndec.timetable.net.FetchOutcome
import com.gndec.timetable.net.TimetableFetcher
import com.gndec.timetable.net.TimetableSourceResolver
import com.gndec.timetable.parse.ParseException
import com.gndec.timetable.parse.TimetableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed class RefreshResult {
    data class Success(val lecturesForGroup: Int) : RefreshResult()
    object UpToDate : RefreshResult()
    data class Failed(val reason: String, val hadCachedTimetable: Boolean) : RefreshResult()
}

/**
 * Orchestrates: fetch → parse → normalize → validate → save → reschedule alarms.
 *
 * lastSuccessfulFetch is ONLY updated when every step succeeds.
 * A failed refresh NEVER deletes or degrades the cached timetable, and its
 * alarms stay scheduled.
 */
class RefreshManager(
    private val db: AppDatabase,
    private val settings: SettingsManager,
    private val keys: SecureKeyStore,
    private val fetcher: TimetableFetcher,
    private val sourceResolver: TimetableSourceResolver,
    private val normalizer: AiNormalizer,
    private val scheduler: AlarmScheduler,
    /** Used when the profile's academic year is 2..4; the appsc resolver stays for 1st year. */
    private val deptResolver: DeptGroupSourceResolver = DeptGroupSourceResolver()
) {
    /** Minimum age of last check before an automatic (non-forced) refresh is attempted. */
    var autoRefreshMinAgeMillis: Long = 6L * 3_600_000

    private val mutex = Mutex()

    /** Called on app start / foreground; cheap no-op when data was checked recently. */
    suspend fun refreshIfStale(): RefreshResult? {
        val meta = db.metaDao().get()
        val lastCheck = meta?.lastChecked ?: 0L
        if (System.currentTimeMillis() - lastCheck < autoRefreshMinAgeMillis) return null
        return refresh(force = false)
    }

    suspend fun refresh(force: Boolean): RefreshResult = mutex.withLock {
        val cfg = settings.flow.first()
        val meta = db.metaDao().get()
        val now = System.currentTimeMillis()
        val resolved = when {
            // 2nd/3rd/4th year: resolve the OFFICIAL departmental document.
            // No silent fallback to the 1st-year file — if discovery fails the
            // refresh fails honestly and the cached timetable stays untouched.
            cfg.academicYear >= 2 -> {
                val dept = deptResolver.resolve(cfg.branch, cfg.academicYear, meta?.sourceUrl)
                dept?.let { TimetableSourceResolver.Resolution(it.url, it.source) }
                    ?: return RefreshResult.Failed(
                        "Could not reach the official ${cfg.branch.uppercase()} timetable page. " +
                            "Check your internet connection and refresh again.",
                        hadCachedTimetable = db.lectureDao().countAll() > 0
                    )
            }
            else -> sourceResolver.resolve(cfg.pyqRagBackendUrl, cfg.sourceUrl)
        }
        val sourceUrlChanged = meta?.sourceUrl != resolved.url
        val etag = if (sourceUrlChanged) null else meta?.etag
        val lastModified = if (sourceUrlChanged) null else meta?.lastModified

        fun markChecked() = TimetableMetaEntity(
            id = 1, sourceUrl = resolved.url,
            lastSuccessfulFetch = meta?.lastSuccessfulFetch,
            lastChecked = now,
            etag = etag, lastModified = lastModified,
            timetableHash = meta?.timetableHash
        )

        when (val outcome = fetcher.fetch(resolved.url, etag, lastModified)) {
            is FetchOutcome.NotModified -> {
                db.metaDao().put(markChecked())
                snapshotCurrentWeekIfNeeded(cfg.group, now)
                RefreshResult.UpToDate
            }
            is FetchOutcome.Failed -> {
                db.metaDao().put(markChecked())
                RefreshResult.Failed(outcome.reason, hadCachedTimetable = db.lectureDao().countAll() > 0)
            }
            is FetchOutcome.Changed ->
                parseAndSave(outcome.html, cfg.group, resolved.url, outcome.etag, outcome.lastModified, now)
        }
    }

    private suspend fun parseAndSave(
        html: String,
        group: String?,
        sourceUrl: String,
        etag: String?,
        lastModified: String?,
        now: Long
    ): RefreshResult {
        val hadCache = db.lectureDao().countAll() > 0
        // Parse off the main thread
        val parsed = try {
            withContext(Dispatchers.Default) { TimetableParser.parse(html) }
        } catch (e: ParseException) {
            return RefreshResult.Failed(e.message ?: "parse failed", hadCache)
        }

        val rawForGroup = group?.let {
            parsed[it] ?: return RefreshResult.Failed(
                "group \"$it\" was not found in the fetched timetable", hadCache
            )
        }
        // Validation: refuse catastrophically small parses — never overwrite a good cache with junk
        val total = parsed.values.sumOf { it.size }
        if (total < 10 || (rawForGroup != null && rawForGroup.size < 3)) {
            return RefreshResult.Failed("timetable validation failed (implausibly few lectures)", hadCache)
        }

        val cfg = settings.flow.first()
        val route = AiNormalizer.AiRoute(
            enabled = cfg.aiEnabled,
            userApiKey = keys.getGeminiKey(),
            backendUrl = cfg.backendUrl,
            model = cfg.model
        )
        val entities = mutableListOf<LectureEntity>()
        for ((g, rawList) in parsed) {
            val fields = normalizer.normalizeAll(rawList, route)
            rawList.zip(fields).forEach { (raw, f) ->
                entities.add(
                    LectureEntity(
                        groupName = g, dayOfWeek = raw.dayOfWeek,
                        startMinutes = raw.startMinutes, endMinutes = raw.endMinutes,
                        subject = f.subject, teacher = f.teacher, venue = f.venue,
                        lectureType = f.lectureType, rawText = raw.rawText, fetchId = now
                    )
                )
            }
        }

        db.lectureDao().deleteAll()
        db.lectureDao().insertAll(entities)
        saveCurrentWeekSnapshots(entities, now)
        db.metaDao().put(
            TimetableMetaEntity(
                id = 1, sourceUrl = sourceUrl,
                lastSuccessfulFetch = now, lastChecked = now,
                etag = etag, lastModified = lastModified,
                timetableHash = TimetableParser.sha256(html)
            )
        )
        if (group != null && rawForGroup != null) {
            scheduler.rescheduleAll(db, group, ReminderConfig.from(cfg))
        }
        return RefreshResult.Success(rawForGroup?.size ?: total)
    }

    suspend fun ensureCurrentWeekSnapshots(group: String? = null) {
        val cfg = settings.flow.first()
        snapshotCurrentWeekIfNeeded(group ?: cfg.group, System.currentTimeMillis())
    }

    private suspend fun snapshotCurrentWeekIfNeeded(group: String?, fetchId: Long) {
        if (group.isNullOrBlank()) return
        val today = LocalDate.now()
        if (db.timetableSnapshotDao().getForGroupAndDate(group, today.toString()).isEmpty()) {
            saveCurrentWeekSnapshots(db.lectureDao().getForGroup(group), fetchId)
        }
    }

    private suspend fun saveCurrentWeekSnapshots(entities: List<LectureEntity>, fetchId: Long) {
        val monday = LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
        val snapshots = entities.map { lecture ->
            val date = monday.plusDays((lecture.dayOfWeek - 1).toLong())
            TimetableSnapshotEntity(
                id = AttendanceManager.lectureKey(date, lecture),
                groupName = lecture.groupName,
                attendanceDate = date.toString(),
                dayOfWeek = lecture.dayOfWeek,
                startMinutes = lecture.startMinutes,
                endMinutes = lecture.endMinutes,
                subject = lecture.subject,
                teacher = lecture.teacher,
                venue = lecture.venue,
                lectureType = lecture.lectureType,
                rawText = lecture.rawText,
                fetchId = fetchId
            )
        }
        if (snapshots.isNotEmpty()) db.timetableSnapshotDao().putAll(snapshots)
        db.timetableSnapshotDao().deleteBefore(LocalDate.now().minusDays(366).toString())
    }

    /**
     * Group change: lectures for ALL groups are cached locally, so this is instant —
     * save preference, cancel old alarms, schedule alarms for the new group.
     */
    suspend fun changeGroup(newGroup: String): Boolean {
        if (db.lectureDao().countForGroup(newGroup) == 0) return false
        settings.setGroup(newGroup)
        val cfg = settings.flow.first()
        scheduler.rescheduleAll(db, newGroup, ReminderConfig.from(cfg))
        return true
    }
}

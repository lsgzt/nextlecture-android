package com.gndec.timetable.domain

import com.gndec.timetable.data.db.AppDatabase
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.db.TimetableMetaEntity
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.FetchOutcome
import com.gndec.timetable.net.TimetableFetcher
import com.gndec.timetable.parse.ParseException
import com.gndec.timetable.parse.TimetableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val normalizer: AiNormalizer,
    private val scheduler: AlarmScheduler
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

        fun markChecked() = TimetableMetaEntity(
            id = 1, sourceUrl = cfg.sourceUrl,
            lastSuccessfulFetch = meta?.lastSuccessfulFetch,
            lastChecked = now,
            etag = meta?.etag, lastModified = meta?.lastModified,
            timetableHash = meta?.timetableHash
        )

        when (val outcome = fetcher.fetch(cfg.sourceUrl, meta?.etag, meta?.lastModified)) {
            is FetchOutcome.NotModified -> {
                db.metaDao().put(markChecked())
                RefreshResult.UpToDate
            }
            is FetchOutcome.Failed -> {
                db.metaDao().put(markChecked())
                RefreshResult.Failed(outcome.reason, hadCachedTimetable = db.lectureDao().countAll() > 0)
            }
            is FetchOutcome.Changed ->
                parseAndSave(outcome.html, cfg.group, cfg.sourceUrl, outcome.etag, outcome.lastModified, now)
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

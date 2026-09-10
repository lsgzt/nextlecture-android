package com.gndec.timetable

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig
import com.microsoft.clarity.models.LogLevel
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gndec.timetable.data.db.TimetableMetaEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.work.RefreshWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TimetableApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val config = ClarityConfig(
            projectId = "yaflc6peum",
            logLevel = LogLevel.None
        )
        Clarity.initialize(applicationContext, config)

        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)

        // On APK upgrade: invalidate HTTP validators + AI cell cache and force a
        // full re-download/re-parse. Lectures are NOT deleted first — RefreshManager
        // replaces them atomically only after a successful parse, so a failed
        // network/parse can never leave the user with an empty timetable.
        container.appScope.launch {
            runCatching { migrateTimetableCacheIfVersionChanged() }
        }

        container.appScope.launch {
            runCatching { container.studentDirectoryManager.migrateSavedProfileIfNeeded() }
        }
        container.appScope.launch {
            runCatching { container.announcementManager.loadCached() }
            runCatching { container.announcementManager.refreshAndNotify() }
        }
        container.appScope.launch {
            runCatching { container.erpNoticeManager.loadCached() }
        }
        container.appScope.launch {
            runCatching { container.holidayManager.loadCached() }
            runCatching { container.holidayManager.refresh() }
        }
        container.appScope.launch {
            runCatching { container.releaseUpdateManager.loadCached() }
            runCatching { container.releaseUpdateManager.refreshIfStale() }
        }

        val work = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "timetable_refresh", ExistingPeriodicWorkPolicy.KEEP, work
        )
    }

    private suspend fun migrateTimetableCacheIfVersionChanged() {
        val currentCode = currentVersionCode()
        val lastCode = container.settings.getLastInstalledVersionCode()
        if (lastCode == currentCode) return

        val db = container.db

        // Drop AI cell cache so parser-versioned keys cannot serve stale fields.
        db.aiCacheDao().deleteAll()

        // Invalidate HTTP validators so the next fetch cannot short-circuit as 304.
        val meta = db.metaDao().get()
        if (meta != null) {
            db.metaDao().put(
                TimetableMetaEntity(
                    id = 1,
                    sourceUrl = meta.sourceUrl,
                    lastSuccessfulFetch = meta.lastSuccessfulFetch,
                    lastChecked = 0L,
                    etag = null,
                    lastModified = null,
                    timetableHash = null
                )
            )
        }

        // Record migration first so a crash mid-refresh does not loop forever.
        container.settings.setLastInstalledVersionCode(currentCode)

        // Force re-fetch + re-parse. Existing lectures stay until parse succeeds.
        val group = container.settings.flow.first().group
        if (!group.isNullOrBlank() || db.lectureDao().countAll() > 0) {
            runCatching { container.refreshManager.refresh(force = true) }
        }
    }

    private fun currentVersionCode(): Int {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }
}

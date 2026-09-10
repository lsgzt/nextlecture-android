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

        // Microsoft Clarity analytics (initialized once, before any other app logic).
        val config = ClarityConfig(
            projectId = "yaflc6peum",
            logLevel = LogLevel.None
        )
        Clarity.initialize(applicationContext, config)

        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)

        // On every app-version upgrade: wipe parsed timetable caches so the new
        // parser always re-downloads and re-parses the official document.
        // Profile/settings (group, name, reminders) are intentionally kept.
        container.appScope.launch {
            runCatching { migrateTimetableCacheIfVersionChanged() }
        }

        // Load local caches immediately, then refresh live sources on every app launch.
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

        // Non-urgent periodic background refresh (12h cadence).
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "timetable_refresh", ExistingPeriodicWorkPolicy.KEEP, work
        )
    }

    /**
     * Detects an APK upgrade via [versionCode]. When the installed code differs
     * from the last recorded one, all parsed timetable data is deleted so the
     * next refresh cannot serve stale/wrong cells from a previous parser.
     */
    private suspend fun migrateTimetableCacheIfVersionChanged() {
        val currentCode = currentVersionCode()
        val lastCode = container.settings.getLastInstalledVersionCode()
        if (lastCode == currentCode) return

        val db = container.db
        db.lectureDao().deleteAll()
        db.metaDao().deleteAll()
        db.aiCacheDao().deleteAll()
        db.timetableSnapshotDao().deleteAll()
        db.alarmDao().clear()

        container.settings.setLastInstalledVersionCode(currentCode)

        // Force a live re-fetch when the user already has a group configured.
        val group = container.settings.flow.first().group
        if (!group.isNullOrBlank()) {
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

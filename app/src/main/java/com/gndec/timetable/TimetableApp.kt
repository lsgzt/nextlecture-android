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
import com.gndec.timetable.push.FcmRegistration
import com.gndec.timetable.work.RefreshWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TimetableApp : Application() {

    lateinit var container: AppContainer
        private set

    private val startupWorkStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()

        val config = ClarityConfig(
            projectId = "yaflc6peum",
            logLevel = LogLevel.None
        )
        Clarity.initialize(applicationContext, config)

        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)
        // FCM registration deliberately stays in onCreate: a device whose process is
        // only ever woken by a reminder alarm must still be able to receive a push.
        FcmRegistration.registerCurrent(this)
    }

    /**
     * Content refresh + periodic work, deferred until the student actually opens the app.
     *
     * Application.onCreate also runs when a reminder alarm cold-starts the process on
     * an OEM-reaped device, so doing the network work there re-ran the whole feed
     * refresh roughly twice per lecture with the user nowhere in sight. Runs at most
     * once per process and is safe to call from every Activity create.
     */
    fun ensureStartupWork() {
        if (!startupWorkStarted.compareAndSet(false, true)) return

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
        // Arming is idempotent (the next 05:32 IST boundary is recomputed from "now"), so
        // every foreground re-arms safely. ensureCurrent() only hits the network when a
        // fetch is genuinely owed — a fresh install, or a day whose 05:32 alarm was lost.
        container.appScope.launch {
            runCatching { container.quoteScheduler.scheduleNext() }
            runCatching { container.quoteManager.ensureCurrent() }
        }

        enqueuePeriodicRefresh()
    }

    private fun enqueuePeriodicRefresh() {
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // Spread the first run: with no initial delay every fresh install fires
            // inside the same flex window, so a release or a new cohort syncs in one
            // synchronized burst against GitHub and the college host.
            .setInitialDelay(ThreadLocalRandom.current().nextLong(0, 45), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "timetable_refresh", ExistingPeriodicWorkPolicy.UPDATE, work
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
                    timetableHash = null,
                    generatedAtMillis = meta.generatedAtMillis
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

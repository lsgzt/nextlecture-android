package com.gndec.timetable

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.work.RefreshWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TimetableApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)

        // Refresh on app start when the cache is old enough (cheap: ETag-guarded)
        container.appScope.launch {
            runCatching { container.refreshManager.refreshIfStale() }
        }
        container.appScope.launch {
            runCatching { container.announcementManager.loadCached() }
            runCatching { container.announcementManager.refreshAndNotify() }
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
}

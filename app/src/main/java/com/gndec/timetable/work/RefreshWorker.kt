package com.gndec.timetable.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gndec.timetable.TimetableApp
import com.gndec.timetable.domain.RefreshResult

/** Periodic non-urgent background refresh (ETag-guarded, ~12h cadence). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TimetableApp
        runCatching { app.container.announcementManager.refreshAndNotify() }
        runCatching { app.container.erpNoticeManager.refresh() }
        runCatching { app.container.releaseUpdateManager.refreshIfStale() }
        return when (val r = app.container.refreshManager.refresh(force = false)) {
            is RefreshResult.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
            is RefreshResult.Success, RefreshResult.UpToDate -> Result.success()
        }
    }
}

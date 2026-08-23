package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.db.AppDatabase
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.BackendClient
import com.gndec.timetable.net.GeminiClient
import com.gndec.timetable.net.TimetableFetcher
import com.gndec.timetable.net.PyqRagClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple service locator (kept lightweight on purpose — no DI framework). */
class AppContainer(val context: Context) {
    val db: AppDatabase by lazy { AppDatabase.get(context) }
    val settings by lazy { SettingsManager(context) }
    val keys by lazy { SecureKeyStore(context) }
    val fetcher by lazy { TimetableFetcher() }
    val normalizer by lazy { AiNormalizer(db.aiCacheDao(), GeminiClient(), BackendClient()) }
    val scheduler by lazy { AlarmScheduler(context) }
    val announcementManager by lazy { AnnouncementManager(context, settings) }
    val erpNoticeManager by lazy { ErpNoticeManager(context, settings) }
    val pyqRagClient by lazy { PyqRagClient() }
    val syllabusManager by lazy { SyllabusManager(context, settings, keys, GeminiClient(), db.syllabusChatDao()) }
    val releaseUpdateManager by lazy { ReleaseUpdateManager(context, settings) }
    val studentDirectoryManager by lazy { StudentDirectoryManager(context, settings) }
    val refreshManager by lazy { RefreshManager(db, settings, keys, fetcher, normalizer, scheduler) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

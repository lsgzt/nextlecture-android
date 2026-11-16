package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.db.AppDatabase
import com.gndec.timetable.data.prefs.SecureKeyStore
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.AiClients
import com.gndec.timetable.net.AttendanceClient
import com.gndec.timetable.net.BackendClient
import com.gndec.timetable.net.TimetableFetcher
import com.gndec.timetable.net.TimetableSourceResolver
import com.gndec.timetable.net.LostFoundClient
import com.gndec.timetable.net.PyqRagClient
import com.gndec.timetable.net.ZenQuoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple service locator (kept lightweight on purpose — no DI framework). */
class AppContainer(val context: Context) {
    val db: AppDatabase by lazy { AppDatabase.get(context) }
    val settings by lazy { SettingsManager(context) }
    val keys by lazy { SecureKeyStore(context) }
    val fetcher by lazy { TimetableFetcher() }
    val aiClients by lazy { AiClients() }
    val normalizer by lazy { AiNormalizer(db.aiCacheDao(), aiClients, BackendClient()) }
    val scheduler by lazy { AlarmScheduler(context) }
    val announcementManager by lazy { AnnouncementManager(context, settings) }
    val pyqRagClient by lazy { PyqRagClient() }
    val erpNoticeManager by lazy { ErpNoticeManager(context, settings, pyqRagClient) }
    val holidayManager by lazy { HolidayManager(settings, pyqRagClient) }
    val attendanceClient by lazy { AttendanceClient() }
    val attendanceManager by lazy { AttendanceManager(attendanceClient, keys, settings) }
    val timetableSourceResolver by lazy { TimetableSourceResolver(pyqRagClient) }
    val syllabusManager by lazy { SyllabusManager(context, settings, keys, aiClients, db.syllabusChatDao()) }
    val releaseUpdateManager by lazy { ReleaseUpdateManager(context, settings) }
    val studentDirectoryManager by lazy { StudentDirectoryManager(context, settings, keys) }
    val groupTimetableManager by lazy { GroupTimetableManager(context) }
    val vacantRoomsManager by lazy { VacantRoomsManager(context) }
    val downloadsManager by lazy { DownloadsManager(context) }
    val deptNoticesManager by lazy { DeptNoticesManager(context, settings) }
    val lostFoundManager by lazy { LostFoundManager(context, LostFoundClient()) }
    val lostFoundImages by lazy { LostFoundImageStore(context) }
    val facultyDirectory by lazy { FacultyDirectoryManager(context) }
    val facultyTimetables by lazy { FacultyTimetableManager(context, facultyDirectory) }
    val facultyPhotos by lazy { FacultyPhotoStore(context) }
    val quoteClient by lazy { ZenQuoteClient() }
    val quoteManager by lazy { DailyQuoteManager(settings, quoteClient) }
    val quoteScheduler by lazy { QuoteScheduler(context) }
    val refreshManager by lazy { RefreshManager(db, settings, keys, fetcher, timetableSourceResolver, normalizer, scheduler) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

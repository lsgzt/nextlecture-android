package com.gndec.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.domain.ReminderConfig
import com.gndec.timetable.ui.alerts.AlertsScreen
import com.gndec.timetable.ui.attendance.AttendanceScreen
import com.gndec.timetable.ui.day.DayScreen
import com.gndec.timetable.ui.details.LectureDetailScreen
import com.gndec.timetable.ui.downloads.DownloadsScreen
import com.gndec.timetable.ui.faculty.FacultyBirthdaysScreen
import com.gndec.timetable.ui.faculty.FacultyDetailScreen
import com.gndec.timetable.ui.faculty.FacultyMembersScreen
import com.gndec.timetable.ui.home.HomeScreen
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.ReducedMotionProvider
import com.gndec.timetable.ui.motion.screenEnter
import com.gndec.timetable.ui.motion.screenExit
import com.gndec.timetable.ui.onboarding.NotificationPermissionOnboardingScreen
import com.gndec.timetable.ui.onboarding.OnboardingScreen
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.ui.more.MoreScreen
import com.gndec.timetable.ui.notice.NoticeDetailScreen
import com.gndec.timetable.ui.notice.NoticeScreen
import com.gndec.timetable.ui.lostfound.LostFoundScreen
import com.gndec.timetable.ui.profile.ProfileScreen
import com.gndec.timetable.ui.search.SearchScreen
import com.gndec.timetable.ui.settings.SettingsScreen
import com.gndec.timetable.ui.syllabus.FrequentlyAskedGroupScreen
import com.gndec.timetable.ui.syllabus.FrequentlyAskedScreen
import com.gndec.timetable.ui.syllabus.PreviousYearPapersScreen
import com.gndec.timetable.ui.syllabus.SyllabusScreen
import com.gndec.timetable.ui.theme.GndecTheme
import com.gndec.timetable.ui.vacant.VacantRoomsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        val app = application as TimetableApp
        app.container.appScope.launch {
            runCatching {
                val group = app.container.settings.flow.first().group ?: return@runCatching
                // Stale-checked, not forced. A forced refresh ran on every foreground —
                // every screen-on, rotation, and return from a permission dialog — and
                // for 2nd-4th year it first scrapes the department page to rediscover
                // the document URL. A genuinely new document is still picked up, because
                // RefreshManager reschedules the alarms itself when the parse replaced rows.
                app.container.refreshManager.refreshIfStale()
                // Self-heal only: rebuild when the reminder set is actually empty — the
                // system cleared it, or the device rebooted in a way that did not deliver
                // our broadcast. Rebuilding unconditionally on every foreground tore down
                // and recreated a few hundred PendingIntents for nothing.
                if (app.container.db.alarmDao().countFuture(System.currentTimeMillis()) == 0 &&
                    app.container.db.lectureDao().countForGroup(group) > 0
                ) {
                    val cfg = app.container.settings.flow.first()
                    // Holiday-aware: a full holiday gets no reminders even when the
                    // self-heal rebuilds the whole reminder set.
                    app.container.scheduler.rescheduleAll(
                        app.container.db, group, ReminderConfig.from(cfg),
                        com.gndec.timetable.domain.HolidayGate.decode(cfg.holidayJson),
                        cfg.weekendFollowDayIso, cfg.weekendFollowDow
                    )
                }
            }
        }
        app.container.appScope.launch {
            runCatching { app.container.erpNoticeManager.refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TimetableApp).container
        // Once per process, and only when a human actually opens the app — an alarm
        // cold-start must not trigger the whole content-feed refresh.
        (application as TimetableApp).ensureStartupWork()
        setContent {
            ReducedMotionProvider {
                val settingsFlow = remember(container.settings) { container.settings.flow.map { value: AppSettings -> value as AppSettings? } }
                val settings by settingsFlow.collectAsState(initial = null)
                var selectedLecture by remember { mutableStateOf<LectureEntity?>(null) }
                var selectedNotice by remember { mutableStateOf<ErpNotice?>(null) }
                var selectedLectureDate by remember { mutableStateOf(LocalDate.now()) }

                if (settings == null) {
                    // No stored preference yet, so follow the system rather than
                    // flashing a light screen before the real theme resolves.
                    GndecTheme(mode = "system") {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    GndecTheme(mode = settings!!.themeMode) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            val nav = rememberNavController()
                            val reduced = LocalReducedMotion.current
                            fun navigate(route: String) {
                                if (nav.currentDestination?.route == route) return
                                nav.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            val hasIdentity = settings!!.studentName.isNotBlank() ||
                                settings!!.registrationNumber.isNotBlank() ||
                                settings!!.rollNumber.isNotBlank()
                            // A profile claiming 2nd-4th year without a departmental
                            // group pick (profileSource != manual_departmental) is a
                            // MIGRATED 1st-year profile — its saved group still points
                            // at the appsc document. Route through onboarding once so
                            // the student picks their section from the official
                            // departmental timetable instead of silently keeping the
                            // wrong source. A senior whose onboarding SAVE could not
                            // link a group (the departmental download failed, or the
                            // app was killed right after the profile was written) is
                            // also incomplete: letting them in would land on an empty
                            // "Select group" timetable that only Settings → Change
                            // group can repair. Onboarding resumes straight at the
                            // section picker, so nothing is asked twice.
                            val seniorSetupIncomplete = settings!!.academicYear >= 2 &&
                                (settings!!.profileSource != "manual_departmental" ||
                                    settings!!.group.isNullOrBlank())
                            val setupComplete = (settings!!.onboardingDone || hasIdentity) && !seniorSetupIncomplete
                            val permissionPromptNeeded = setupComplete &&
                                !settings!!.notificationPermissionPrompted &&
                                !NotificationHelper.notificationsEnabled(container.context)
                            val startDestination = when {
                                !setupComplete -> "onboarding"
                                permissionPromptNeeded -> "notification_onboarding"
                                else -> "home"
                            }
                            // One hoisted bottom bar for all tab destinations: it lives above the
                            // NavHost, so screen transitions can never move or redraw the bar itself.
                            Box(Modifier.fillMaxSize()) {
                                NavHost(
                                    navController = nav,
                                    startDestination = startDestination,
                                    enterTransition = { screenEnter(reduced, initialState.destination.route, targetState.destination.route) },
                                    exitTransition = { screenExit(reduced, initialState.destination.route, targetState.destination.route) },
                                    popEnterTransition = { screenEnter(reduced, initialState.destination.route, targetState.destination.route) },
                                    popExitTransition = { screenExit(reduced, initialState.destination.route, targetState.destination.route) }
                                ) {
                                    composable("onboarding") {
                                        OnboardingScreen(container = container) {
                                            container.appScope.launch { container.settings.setOnboardingDone(true) }
                                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                                        }
                                    }
                                    composable("notification_onboarding") {
                                        NotificationPermissionOnboardingScreen(container = container) {
                                            nav.navigate("home") { popUpTo("notification_onboarding") { inclusive = true } }
                                        }
                                    }
                                    composable("home") {
                                        HomeScreen(
                                            container = container,
                                            onOpenToday = { navigate("today") },
                                            onOpenAlerts = { navigate("syllabus") },
                                            onOpenNotice = { navigate("notice") },
                                            onOpenHolidays = { navigate("notice/holidays") },
                                            onOpenNoticeDetail = { notice ->
                                                selectedNotice = notice
                                                navigate("notice_detail")
                                            },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenProfile = { navigate("profile") },
                                            onOpenSearch = { navigate("search") },
                                            onOpenAttendance = { navigate("attendance") },
                                            onOpenLecture = { selectedLecture = it; selectedLectureDate = LocalDate.now(); navigate("detail") }
                                        )
                                    }
                                    composable("today") {
                                        DayScreen(
                                            container = container,
                                            onOpenHome = { navigate("home") },
                                            onOpenAlerts = { navigate("syllabus") },
                                            onOpenNotice = { navigate("notice") },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenLecture = { lecture, date -> selectedLecture = lecture; selectedLectureDate = date; navigate("detail") }
                                        )
                                    }
                                    composable("syllabus") {
                                        SyllabusScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() },
                                            onOpenPreviousYearPapers = { navigate("previous_year_papers") }
                                        )
                                    }
                                    composable("previous_year_papers") {
                                        PreviousYearPapersScreen(
                                            context = container.context,
                                            onBack = { nav.popBackStack() },
                                            onOpenFrequentlyAsked = { navigate("frequently_asked") }
                                        )
                                    }
                                    composable("frequently_asked") {
                                        FrequentlyAskedScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() },
                                            onOpenGroup = { groupId -> navigate("frequently_asked_group/$groupId") }
                                        )
                                    }
                                    composable("frequently_asked_group/{groupId}") { entry ->
                                        val groupId = entry.arguments?.getString("groupId")?.toLongOrNull()
                                        if (groupId == null) nav.popBackStack()
                                        else FrequentlyAskedGroupScreen(container = container, groupId = groupId, onBack = { nav.popBackStack() })
                                    }
                                    composable("alerts") {
                                        AlertsScreen(
                                            container = container,
                                            onOpenHome = { navigate("home") },
                                            onOpenToday = { navigate("today") },
                                            onOpenNotice = { navigate("notice") },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenLecture = { selectedLecture = it; selectedLectureDate = LocalDate.now(); navigate("detail") },
                                            onBack = { nav.popBackStack() }
                                        )
                                    }
                                    composable("notice") {
                                        NoticeScreen(
                                            container = container,
                                            onOpenHome = { navigate("home") },
                                            onOpenToday = { navigate("today") },
                                            onOpenAlerts = { navigate("syllabus") },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenNoticeDetail = { notice ->
                                                selectedNotice = notice
                                                navigate("notice_detail")
                                            }
                                        )
                                    }
                                    composable("notice/holidays") {
                                        NoticeScreen(
                                            container = container,
                                            onOpenHome = { navigate("home") },
                                            onOpenToday = { navigate("today") },
                                            onOpenAlerts = { navigate("syllabus") },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenNoticeDetail = { notice ->
                                                selectedNotice = notice
                                                navigate("notice_detail")
                                            },
                                            initialTab = com.gndec.timetable.ui.notice.NoticeTab.HOLIDAYS
                                        )
                                    }
                                    composable("notice_detail") {
                                        selectedNotice?.let { notice ->
                                            NoticeDetailScreen(
                                                notice = notice,
                                                onBack = { nav.popBackStack() },
                                                onOpenSettings = { navigate("settings") }
                                            )
                                        }
                                    }
                                    composable("detail") {
                                        selectedLecture?.let { lecture ->
                                            LectureDetailScreen(
                                                container = container,
                                                lecture = lecture,
                                                lectureDate = selectedLectureDate,
                                                onBack = { nav.popBackStack() },
                                                onOpenHome = { navigate("home") },
                                                onOpenToday = { navigate("today") },
                                                onOpenAlerts = { navigate("syllabus") },
                                                onOpenSettings = { navigate("settings") },
                                                onOpenFaculty = { profileId -> navigate("faculty/$profileId") }
                                            )
                                        }
                                    }
                                    composable("settings") {
                                        SettingsScreen(container = container, onBack = { nav.popBackStack() }, onOpenAlerts = { navigate("alerts") })
                                    }
                                    composable("search") {
                                        SearchScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() },
                                            onNavigate = { route -> navigate(route) },
                                            onOpenSettings = { navigate("settings") },
                                            onOpenLecture = { lecture, date ->
                                                selectedLecture = lecture
                                                selectedLectureDate = date
                                                navigate("detail")
                                            },
                                            onOpenFaculty = { profileId -> navigate("faculty/$profileId") },
                                            onOpenNotice = { noticeId ->
                                                val notice = container.erpNoticeManager.notices.value
                                                    .firstOrNull { it.id == noticeId }
                                                if (notice != null) {
                                                    selectedNotice = notice
                                                    navigate("notice_detail")
                                                } else {
                                                    navigate("notice")
                                                }
                                            }
                                        )
                                    }
                                    composable("faculty/{facultyId}") { entry ->
                                        val facultyId = entry.arguments?.getString("facultyId").orEmpty()
                                        FacultyDetailScreen(
                                            container = container,
                                            profileId = facultyId,
                                            onBack = { nav.popBackStack() },
                                            onOpenSettings = { navigate("settings") }
                                        )
                                    }
                                    composable("profile") {
                                        ProfileScreen(container = container, onBack = { nav.popBackStack() }, onOpenAttendance = { navigate("attendance") })
                                    }
                                    composable("attendance") {
                                        AttendanceScreen(container = container, onBack = { nav.popBackStack() })
                                    }
                                    composable("vacant_rooms") {
                                        VacantRoomsScreen(container = container, onBack = { nav.popBackStack() })
                                    }
                                    composable("faculty_members") {
                                        FacultyMembersScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() },
                                            onOpenFaculty = { profileId -> navigate("faculty/$profileId") },
                                            onOpenSettings = { navigate("settings") }
                                        )
                                    }
                                    composable("faculty_birthdays") {
                                        FacultyBirthdaysScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() },
                                            onOpenFaculty = { profileId -> navigate("faculty/$profileId") },
                                            onOpenSettings = { navigate("settings") }
                                        )
                                    }
                                    composable("more") {
                                        MoreScreen(
                                            onOpenDownloads = { navigate("downloads") },
                                            onOpenLostFound = { navigate("lost_found") },
                                            onOpenFacultyBirthdays = { navigate("faculty_birthdays") },
                                            onOpenVacantRooms = { navigate("vacant_rooms") },
                                            onOpenFacultyMembers = { navigate("faculty_members") },
                                            onOpenSettings = { navigate("settings") }
                                        )
                                    }
                                    composable("downloads") {
                                        DownloadsScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() }
                                        )
                                    }
                                    composable("lost_found") {
                                        LostFoundScreen(
                                            container = container,
                                            onBack = { nav.popBackStack() }
                                        )
                                    }
                                }
                                val backStackEntry by nav.currentBackStackEntryAsState()
                                val tabRoute = when (val route = backStackEntry?.destination?.route) {
                                    "home", "today", "notice", "more" -> route
                                    "notice/holidays" -> "notice"
                                    // Detail keeps its previous behavior of highlighting Home.
                                    "detail" -> "home"
                                    else -> null
                                }
                                if (tabRoute != null) {
                                    PremiumBottomBar(
                                        selected = tabRoute,
                                        onNavigate = { route -> navigate(route) },
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

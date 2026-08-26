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
import com.gndec.timetable.ui.alerts.AlertsScreen
import com.gndec.timetable.ui.attendance.AttendanceScreen
import com.gndec.timetable.ui.day.DayScreen
import com.gndec.timetable.ui.details.LectureDetailScreen
import com.gndec.timetable.ui.home.HomeScreen
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.ReducedMotionProvider
import com.gndec.timetable.ui.motion.screenEnter
import com.gndec.timetable.ui.motion.screenExit
import com.gndec.timetable.ui.onboarding.NotificationPermissionOnboardingScreen
import com.gndec.timetable.ui.onboarding.OnboardingScreen
import com.gndec.timetable.ui.notice.NoticeScreen
import com.gndec.timetable.ui.profile.ProfileScreen
import com.gndec.timetable.ui.settings.SettingsScreen
import com.gndec.timetable.ui.syllabus.FrequentlyAskedGroupScreen
import com.gndec.timetable.ui.syllabus.FrequentlyAskedScreen
import com.gndec.timetable.ui.syllabus.PreviousYearPapersScreen
import com.gndec.timetable.ui.syllabus.SyllabusScreen
import com.gndec.timetable.ui.theme.GndecTheme
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
                val beforeRefresh = app.container.settings.flow.first()
                if (beforeRefresh.group != null) {
                    // Always perform a conditional network check on foreground launch.
                    app.container.refreshManager.refresh(force = true)
                    // Rebuild alarms even when the server returns 304, so upgrades use
                    // the fresh per-stage notification IDs and bundled sound behavior.
                    val refreshed = app.container.settings.flow.first()
                    refreshed.group?.let { group ->
                        app.container.scheduler.rescheduleAll(
                            app.container.db,
                            group,
                            com.gndec.timetable.domain.ReminderConfig.from(refreshed)
                        )
                    }
                }
            }
        }
        app.container.appScope.launch {
            runCatching { app.container.erpNoticeManager.refresh() }
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as TimetableApp
        app.container.appScope.launch {
            runCatching {
                val cfg = app.container.settings.flow.first()
                val group = cfg.group
                if (group != null && app.container.db.lectureDao().countForGroup(group) > 0) {
                    app.container.scheduler.rescheduleAll(
                        app.container.db,
                        group,
                        com.gndec.timetable.domain.ReminderConfig.from(cfg)
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TimetableApp).container
        setContent {
            ReducedMotionProvider {
                val settingsFlow = remember(container.settings) { container.settings.flow.map { value: AppSettings -> value as AppSettings? } }
                val settings by settingsFlow.collectAsState(initial = null)
                var selectedLecture by remember { mutableStateOf<LectureEntity?>(null) }
                var selectedLectureDate by remember { mutableStateOf(LocalDate.now()) }

                if (settings == null) {
                    GndecTheme(mode = "light") {
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
                            val setupComplete = settings!!.onboardingDone || settings!!.studentName.isNotBlank() || settings!!.registrationNumber.isNotBlank() || settings!!.rollNumber.isNotBlank()
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
                                            onOpenSettings = { navigate("settings") },
                                            onOpenProfile = { navigate("profile") },
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
                                            onOpenSettings = { navigate("settings") }
                                        )
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
                                                onOpenSettings = { navigate("settings") }
                                            )
                                        }
                                    }
                                    composable("settings") {
                                        SettingsScreen(container = container, onBack = { nav.popBackStack() }, onOpenAlerts = { navigate("alerts") })
                                    }
                                    composable("profile") {
                                        ProfileScreen(container = container, onBack = { nav.popBackStack() }, onOpenAttendance = { navigate("attendance") })
                                    }
                                    composable("attendance") {
                                        AttendanceScreen(container = container, onBack = { nav.popBackStack() })
                                    }
                                }
                                val backStackEntry by nav.currentBackStackEntryAsState()
                                val tabRoute = when (backStackEntry?.destination?.route) {
                                    "home", "today", "notice" -> backStackEntry?.destination?.route
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

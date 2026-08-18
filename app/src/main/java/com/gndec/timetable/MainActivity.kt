package com.gndec.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.navigation.compose.rememberNavController
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.ui.alerts.AlertsScreen
import com.gndec.timetable.ui.day.DayScreen
import com.gndec.timetable.ui.details.LectureDetailScreen
import com.gndec.timetable.ui.home.HomeScreen
import com.gndec.timetable.ui.onboarding.NotificationPermissionOnboardingScreen
import com.gndec.timetable.ui.onboarding.OnboardingScreen
import com.gndec.timetable.ui.profile.ProfileScreen
import com.gndec.timetable.ui.settings.SettingsScreen
import com.gndec.timetable.ui.theme.GndecTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
            val settingsFlow = remember(container.settings) { container.settings.flow.map { value: AppSettings -> value as AppSettings? } }
            val settings by settingsFlow.collectAsState(initial = null)
            var selectedLecture by remember { mutableStateOf<LectureEntity?>(null) }

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
                        NavHost(navController = nav, startDestination = startDestination) {
                            composable("onboarding", enterTransition = { fadeIn(tween(140)) }, exitTransition = { fadeOut(tween(100)) }) {
                                OnboardingScreen(container = container) {
                                    container.appScope.launch { container.settings.setOnboardingDone(true) }
                                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                                }
                            }
                            composable("notification_onboarding", enterTransition = { fadeIn(tween(140)) }, exitTransition = { fadeOut(tween(100)) }) {
                                NotificationPermissionOnboardingScreen(container = container) {
                                    nav.navigate("home") { popUpTo("notification_onboarding") { inclusive = true } }
                                }
                            }
                            composable("home", enterTransition = { fadeIn(tween(140)) }, exitTransition = { fadeOut(tween(100)) }) {
                                HomeScreen(
                                    container = container,
                                    onOpenToday = { navigate("today") },
                                    onOpenAlerts = { navigate("alerts") },
                                    onOpenSettings = { navigate("settings") },
                                    onOpenProfile = { navigate("profile") },
                                    onOpenLecture = { selectedLecture = it; navigate("detail") }
                                )
                            }
                            composable(
                                "today",
                                enterTransition = { fadeIn(tween(100)) },
                                exitTransition = { fadeOut(tween(70)) }
                            ) {
                                DayScreen(
                                    container = container,
                                    onOpenHome = { navigate("home") },
                                    onOpenAlerts = { navigate("alerts") },
                                    onOpenSettings = { navigate("settings") },
                                    onOpenLecture = { selectedLecture = it; navigate("detail") }
                                )
                            }
                            composable(
                                "alerts",
                                enterTransition = { fadeIn(tween(100)) },
                                exitTransition = { fadeOut(tween(70)) }
                            ) {
                                AlertsScreen(
                                    container = container,
                                    onOpenHome = { navigate("home") },
                                    onOpenToday = { navigate("today") },
                                    onOpenSettings = { navigate("settings") },
                                    onOpenLecture = { selectedLecture = it; navigate("detail") }
                                )
                            }
                            composable(
                                "detail",
                                enterTransition = { fadeIn(tween(100)) },
                                exitTransition = { fadeOut(tween(70)) }
                            ) {
                                selectedLecture?.let { lecture ->
                                    LectureDetailScreen(
                                        container = container,
                                        lecture = lecture,
                                        onBack = { nav.popBackStack() },
                                        onOpenHome = { navigate("home") },
                                        onOpenToday = { navigate("today") },
                                        onOpenAlerts = { navigate("alerts") },
                                        onOpenSettings = { navigate("settings") }
                                    )
                                }
                            }
                            composable(
                                "settings",
                                enterTransition = { fadeIn(tween(100)) },
                                exitTransition = { fadeOut(tween(70)) }
                            ) {
                                SettingsScreen(container = container, onBack = { nav.popBackStack() })
                            }
                            composable(
                                "profile",
                                enterTransition = { fadeIn(tween(100)) },
                                exitTransition = { fadeOut(tween(70)) }
                            ) {
                                ProfileScreen(container = container, onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}

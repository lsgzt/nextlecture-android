package com.gndec.timetable.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.BottomBar
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.InfoRow
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.util.Formatters

@Composable
fun LectureDetailScreen(
    container: AppContainer,
    lecture: LectureEntity,
    onBack: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    val reminderEnabled = settings.remind15 || settings.remind30 || settings.remind5 || settings.remindAtStart
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar("home") { route -> when (route) { "home" -> onOpenHome(); "today" -> onOpenToday(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Header(title = "Lecture details", onBack = onBack, onSettings = onOpenSettings, modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.School, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 72.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.headlineSmall)
                            Text("Today · ${Formatters.hm(lecture.startMinutes)}", style = MaterialTheme.typography.titleMedium)
                            Text("◷ Upcoming", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                            InfoRow(Icons.Default.AccessTime, Formatters.range(lecture.startMinutes, lecture.endMinutes), divider = true)
                            InfoRow(Icons.Default.LocationOn, lecture.venue?.takeIf { it.isNotBlank() } ?: "Venue unavailable", divider = true)
                            InfoRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable", divider = true)
                            InfoRow(Icons.Default.School, lecture.lectureType ?: "Lecture")
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Default.Notifications, size = 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Lecture reminder", style = MaterialTheme.typography.titleLarge)
                                Text(if (settings.remind15) "15 minutes before" else if (settings.remind30) "30 minutes before" else "Reminder preferences", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.size(4.dp))
                                Text(if (reminderEnabled) "✓ Scheduled on this device" else "Reminders are currently off", color = if (reminderEnabled) GndecTeal else GndecMuted)
                            }
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Default.Info, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 42.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("This reminder works without internet.", fontWeight = FontWeight.Bold)
                                Text("The saved timetable is used at lecture time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    TealOutlineButton(
                        text = "Change reminder",
                        icon = Icons.Default.CalendarMonth,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        onClick = onOpenSettings
                    )
                }
            }
        }
    }
}

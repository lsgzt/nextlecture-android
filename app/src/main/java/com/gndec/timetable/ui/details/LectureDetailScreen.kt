package com.gndec.timetable.ui.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.AttendanceManager
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.InfoRow
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.util.Formatters
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun LectureDetailScreen(
    container: AppContainer,
    lecture: LectureEntity,
    lectureDate: LocalDate,
    onBack: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    val scope = rememberCoroutineScope()
    val attendanceDate = remember { LocalDate.now() }
    val detailDayLabel = remember(lectureDate) {
        val today = LocalDate.now()
        when (lectureDate) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> "${Formatters.dayName(lectureDate.dayOfWeek.value)} · ${lectureDate.dayOfMonth} ${lectureDate.month.name.lowercase().replaceFirstChar(Char::titlecase)}"
        }
    }
    var attendanceStatus by remember { mutableStateOf<String?>(null) }
    var attendanceBusy by remember { mutableStateOf(true) }
    var attendanceSaving by remember { mutableStateOf(false) }
    var attendanceError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lecture.id, attendanceDate) {
        attendanceBusy = true
        attendanceError = null
        runCatching { container.attendanceManager.load(attendanceDate, attendanceDate, 75.0) }
            .onSuccess { loaded ->
                val key = AttendanceManager.lectureKey(attendanceDate, lecture)
                attendanceStatus = loaded.records.firstOrNull { it.lectureKey == key }?.status
            }
            .onFailure { attendanceError = it.message ?: "Attendance is unavailable" }
        attendanceBusy = false
    }
    val reminderEnabled = settings.remind15 || settings.remind30 || settings.remind5 || settings.remindAtStart
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 22.dp + PremiumBottomBarContentClearance),
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
                            Text("$detailDayLabel · ${Formatters.hm(lecture.startMinutes)}", style = MaterialTheme.typography.titleMedium)
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
                    AttendanceDetailCard(
                        status = attendanceStatus,
                        busy = attendanceBusy || attendanceSaving,
                        error = attendanceError,
                        onStatus = { status ->
                            attendanceSaving = true
                            attendanceError = null
                            scope.launch {
                                runCatching { container.attendanceManager.mark(attendanceDate, lecture, status) }
                                    .onSuccess { saved -> attendanceStatus = saved.status }
                                    .onFailure { attendanceError = it.message ?: "Could not save attendance" }
                                attendanceSaving = false
                            }
                        },
                        onClear = {
                            attendanceSaving = true
                            attendanceError = null
                            scope.launch {
                                runCatching { container.attendanceManager.unmark(attendanceDate, lecture) }
                                    .onSuccess { attendanceStatus = null }
                                    .onFailure { attendanceError = it.message ?: "Could not clear attendance" }
                                attendanceSaving = false
                            }
                        }
                    )
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


@Composable
private fun AttendanceDetailCard(
    status: String?,
    busy: Boolean,
    error: String?,
    onStatus: (String) -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.animateContentSize(motionTween(Motion.Normal)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.CheckCircle, containerColor = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.primary, size = 46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Attendance", style = MaterialTheme.typography.titleLarge)
                    val statusSwapIn = motionTween<Float>(Motion.Fast)
                    val statusSwapOut = motionTween<Float>(Motion.Fast)
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = { fadeIn(statusSwapIn) togetherWith fadeOut(statusSwapOut) },
                        label = "detailAttendanceStatus"
                    ) { current ->
                        Text(
                            when (current) {
                                "present" -> "Marked present on this device"
                                "absent" -> "Marked absent on this device"
                                else -> "Not marked yet"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val busySwapIn = motionTween<Float>(Motion.Fast)
            val busySwapOut = motionTween<Float>(Motion.Fast)
            AnimatedContent(
                targetState = busy,
                transitionSpec = { fadeIn(busySwapIn) togetherWith fadeOut(busySwapOut) },
                label = "detailAttendanceBusy"
            ) { isBusy ->
                if (isBusy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presentPress = remember { MutableInteractionSource() }
                        androidx.compose.material3.Button(
                            onClick = { onStatus("present") },
                            modifier = Modifier.weight(1f).pressFeedback(presentPress, pressedScale = 0.96f),
                            interactionSource = presentPress,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = GndecTeal, contentColor = androidx.compose.ui.graphics.Color.White)
                        ) { Text("Present") }
                        val absentPress = remember { MutableInteractionSource() }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onStatus("absent") },
                            modifier = Modifier.weight(1f).pressFeedback(absentPress, pressedScale = 0.96f),
                            interactionSource = absentPress
                        ) {
                            Text("Absent", color = MaterialTheme.colorScheme.error)
                        }
                        if (status != null) androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
            }
        }
    }
}

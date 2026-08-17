package com.gndec.timetable.ui.day

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.PremiumMetaRow
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumPill
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed class DayItem {
    data class Lecture(val lecture: LectureEntity, val state: LectureState) : DayItem()
    data class Free(val start: Int, val end: Int, val next: LectureEntity?) : DayItem()
}

private enum class LectureState { COMPLETED, HAPPENING, UPCOMING }

private val DeviceZone = ZoneId.systemDefault()
private val DayDateFormatter = DateTimeFormatter.ofPattern("EEEE · d MMMM")

@Composable
fun DayScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLecture: (LectureEntity) -> Unit
) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val group = settings.group
    val lectures by remember(group) {
        if (group == null) flowOf(emptyList()) else container.db.lectureDao().observeForGroup(group)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val waitMillis = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(waitMillis.coerceAtLeast(1_000L))
            now = System.currentTimeMillis()
        }
    }

    val zoned = Instant.ofEpochMilli(now).atZone(DeviceZone)
    val dow = zoned.dayOfWeek.value
    val nowMinutes = zoned.hour * 60 + zoned.minute
    val todays = lectures.filter { it.dayOfWeek == dow }.sortedBy { it.startMinutes }
    val items = buildList<DayItem> {
        var previousEnd: Int? = null
        todays.forEach { lecture ->
            previousEnd?.let { if (lecture.startMinutes > it) add(DayItem.Free(it, lecture.startMinutes, lecture)) }
            val state = when {
                lecture.endMinutes <= nowMinutes -> LectureState.COMPLETED
                lecture.startMinutes <= nowMinutes -> LectureState.HAPPENING
                else -> LectureState.UPCOMING
            }
            add(DayItem.Lecture(lecture, state))
            previousEnd = maxOf(previousEnd ?: 0, lecture.endMinutes)
        }
    }
    val freeMinutes = items.filterIsInstance<DayItem.Free>().sumOf { it.end - it.start }
    val dateLabel = DayDateFormatter.format(zoned)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PremiumBottomBar("today") { route -> when (route) { "home" -> onOpenHome(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                item {
                    PremiumPageHeader(
                        title = "Today",
                        subtitle = "${group ?: "ITB2"}  ·  $dateLabel",
                        onSettings = onOpenSettings
                    )
                }
                item { PremiumDaySummary(todays.size, freeMinutes, 0) }
                if (items.isEmpty()) {
                    item {
                        Card(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(28.dp)) {
                                Text("A free day", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("No lectures are scheduled for today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    items(items, key = { item -> when (item) {
                        is DayItem.Free -> "free-${item.start}-${item.end}"
                        is DayItem.Lecture -> "lecture-${item.lecture.id}"
                    } }) { item ->
                        when (item) {
                            is DayItem.Free -> PremiumFreePeriod(item.start, item.end, item.next)
                            is DayItem.Lecture -> PremiumTimelineLecture(item.lecture, item.state, onClick = { onOpenLecture(item.lecture) })
                        }
                    }
                }
                item {
                    Row(
                        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = androidx.compose.ui.Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Saved timetable · reminders work offline", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumDaySummary(lectures: Int, freeMinutes: Int, reminders: Int) {
    Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PremiumMetric(Icons.Default.CalendarMonth, lectures.toString(), if (lectures == 1) "lecture" else "lectures", androidx.compose.ui.Modifier.weight(1f))
        PremiumMetric(Icons.Default.AccessTime, "${freeMinutes / 60}h", "free", androidx.compose.ui.Modifier.weight(1f))
        PremiumMetric(Icons.Default.Notifications, reminders.toString(), "scheduled", androidx.compose.ui.Modifier.weight(1f))
    }
}

@Composable
private fun PremiumMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: androidx.compose.ui.Modifier) {
    Card(
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun PremiumTimelineLecture(lecture: LectureEntity, state: LectureState, onClick: () -> Unit) {
    val isHappening = state == LectureState.HAPPENING
    val accent = if (isHappening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val cardColor = when (state) {
        LectureState.HAPPENING -> MaterialTheme.colorScheme.primary
        LectureState.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
        LectureState.UPCOMING -> MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isHappening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Column(Modifier.width(66.dp).padding(top = 18.dp), horizontalAlignment = androidx.compose.ui.Alignment.Start) {
            Text(formatTime(lecture.startMinutes), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(lecture.endMinutes), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.width(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Box(Modifier.width(3.dp).height(30.dp).background(accent))
            Box(Modifier.size(25.dp).background(accent.copy(alpha = 0.18f), CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(if (state == LectureState.COMPLETED) Icons.Default.CheckCircle else Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Box(Modifier.width(3.dp).height(124.dp).background(accent.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.width(10.dp))
        Card(
            onClick = onClick,
            modifier = androidx.compose.ui.Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = if (isHappening) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    PremiumPill(
                        when (state) { LectureState.COMPLETED -> "Completed"; LectureState.HAPPENING -> "Happening now"; LectureState.UPCOMING -> "Upcoming" },
                        if (isHappening) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        if (isHappening) contentColor else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = if (isHappening) contentColor else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(lecture.subject ?: "Lecture", color = contentColor, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                lecture.venue?.takeIf { it.isNotBlank() }?.let {
                    PremiumMetaRow(Icons.Default.LocationOn, it, if (isHappening) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                PremiumMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable", if (isHappening) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(13.dp))
                Text(lecture.lectureType ?: "Lecture", color = if (isHappening) contentColor.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PremiumFreePeriod(start: Int, end: Int, next: LectureEntity?) {
    Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp)).padding(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.width(76.dp)) {
            Text(formatTime(start), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(end), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text("You’re free for ${formatDuration(end - start)}", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            next?.let { Text("Next: ${it.subject ?: "Lecture"} · ${formatTime(it.startMinutes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
    }
}

private fun formatTime(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val suffix = if (hour >= 12) "PM" else "AM"
    val displayHour = when (val h = hour % 12) { 0 -> 12; else -> h }
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

private fun formatDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

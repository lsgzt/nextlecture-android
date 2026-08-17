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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumPill
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.PremiumMetaRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed class TimelineItem {
    data class Lecture(val lecture: LectureEntity, val state: LectureState) : TimelineItem()
    data class Free(val start: Int, val end: Int, val next: LectureEntity?) : TimelineItem()
}

private enum class LectureState { COMPLETED, HAPPENING, UPCOMING }

private val Zone = ZoneId.systemDefault()
private val DateFormatter = DateTimeFormatter.ofPattern("d MMMM")

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
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay((60_000L - (nowMillis % 60_000L)).coerceAtLeast(1_000L))
        }
    }

    val time = Instant.ofEpochMilli(nowMillis).atZone(Zone)
    val nowMinutes = time.hour * 60 + time.minute
    val todays = lectures.filter { it.dayOfWeek == time.dayOfWeek.value }.sortedBy { it.startMinutes }
    val timeline = buildTimeline(todays, nowMinutes)
    val freeMinutes = timeline.filterIsInstance<TimelineItem.Free>().sumOf { it.end - it.start }
    val dateLabel = "${group ?: "Select group"}  ·  ${time.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}  ·  ${DateFormatter.format(time)}"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PremiumBottomBar("today") { route -> when (route) { "home" -> onOpenHome(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PremiumPageHeader("Today", dateLabel, onSettings = onOpenSettings)
                }
                item { DaySummary(lectures = todays.size, freeMinutes = freeMinutes, reminders = 0) }
                if (timeline.isEmpty()) {
                    item { EmptyDayCard() }
                } else {
                    items(timeline, key = { item -> when (item) {
                        is TimelineItem.Lecture -> "lecture-${item.lecture.id}"
                        is TimelineItem.Free -> "free-${item.start}-${item.end}"
                    } }) { item ->
                        when (item) {
                            is TimelineItem.Lecture -> TimelineLecture(item.lecture, item.state, onClick = { onOpenLecture(item.lecture) })
                            is TimelineItem.Free -> FreePeriod(item.start, item.end, item.next)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Using saved timetable  ·  reminders work offline", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun buildTimeline(lectures: List<LectureEntity>, nowMinutes: Int): List<TimelineItem> = buildList {
    var previousEnd: Int? = null
    lectures.forEach { lecture ->
        previousEnd?.let { if (lecture.startMinutes > it) add(TimelineItem.Free(it, lecture.startMinutes, lecture)) }
        val state = when {
            lecture.endMinutes <= nowMinutes -> LectureState.COMPLETED
            lecture.startMinutes <= nowMinutes -> LectureState.HAPPENING
            else -> LectureState.UPCOMING
        }
        add(TimelineItem.Lecture(lecture, state))
        previousEnd = maxOf(previousEnd ?: 0, lecture.endMinutes)
    }
}

@Composable
private fun DaySummary(lectures: Int, freeMinutes: Int, reminders: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MetricCard(Icons.Default.CalendarMonth, lectures.toString(), if (lectures == 1) "lecture" else "lectures", Modifier.weight(1f))
        MetricCard(Icons.Default.AccessTime, formatDurationShort(freeMinutes), "free", Modifier.weight(1f))
        MetricCard(Icons.Default.Notifications, reminders.toString(), "scheduled", Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier) {
    Card(
        modifier.height(100.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyDayCard() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(22.dp)) {
            Text("You’re free today", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("No lectures are scheduled for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimelineLecture(lecture: LectureEntity, state: LectureState, onClick: () -> Unit) {
    val happening = state == LectureState.HAPPENING
    val accent = if (happening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val cardColor = if (happening) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(72.dp).padding(top = 13.dp), horizontalAlignment = Alignment.Start) {
            Text(formatTime(lecture.startMinutes), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(lecture.endMinutes), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.width(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(22.dp).background(accent.copy(alpha = 0.75f)))
            Box(Modifier.size(24.dp).background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (state == LectureState.COMPLETED) Icons.Default.CheckCircle else Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Box(Modifier.width(2.dp).height(148.dp).background(accent.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.width(9.dp))
        Card(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PremiumPill(
                        when (state) { LectureState.COMPLETED -> "Completed"; LectureState.HAPPENING -> "Happening now"; LectureState.UPCOMING -> "Upcoming" },
                        accent.copy(alpha = if (happening) 0.18f else 0.12f),
                        accent
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                lecture.venue?.takeIf { it.isNotBlank() }?.let {
                    PremiumMetaRow(Icons.Default.LocationOn, it)
                    Spacer(Modifier.height(7.dp))
                }
                PremiumMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable")
                Spacer(Modifier.height(10.dp))
                Text(lecture.lectureType ?: "Lecture", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FreePeriod(start: Int, end: Int, next: LectureEntity?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(72.dp)) {
            Text(formatTime(start), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(end), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(1f)) {
            Text("You’re free for ${formatDuration(end - start)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            next?.let { Text("Next: ${it.subject ?: "Lecture"} · ${formatTime(it.startMinutes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
    }
}

private fun formatTime(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val suffix = if (hour >= 12) "PM" else "AM"
    val displayHour = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

private fun formatDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
private fun formatDurationShort(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"

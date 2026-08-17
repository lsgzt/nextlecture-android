package com.gndec.timetable.ui.day

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.BottomBar
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.StatusPill
import com.gndec.timetable.ui.PillTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed class DayItem {
    data class Lecture(val lecture: LectureEntity, val state: LectureState) : DayItem()
    data class Free(val start: Int, val end: Int, val next: LectureEntity?) : DayItem()
}

private val DeviceZone = ZoneId.systemDefault()
private val DayDateFormatter = DateTimeFormatter.ofPattern("d MMMM")

private enum class LectureState { COMPLETED, HAPPENING, UPCOMING }

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
    val freeMinutes = freeMinutes(items)
    val dateLabel = DayDateFormatter.format(zoned)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar("today") { route -> when (route) { "home" -> onOpenHome(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Header(
                        title = "Today · $dateLabel",
                        subtitle = group ?: "ITB2",
                        onSettings = onOpenSettings,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item { SummaryStats(todays.size, freeMinutes, 0) }
                if (items.isEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(22.dp)) {
                                    Text("No lectures today 🎉", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(6.dp))
                                    Text("Enjoy your free day!", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items,
                        key = { item -> when (item) {
                            is DayItem.Free -> "free-${item.start}-${item.end}"
                            is DayItem.Lecture -> "lecture-${item.lecture.id}"
                        } }
                    ) { item ->
                        when (item) {
                            is DayItem.Free -> FreePeriodCard(item.start, item.end, item.next)
                            is DayItem.Lecture -> TimelineLectureCard(item.lecture, item.state, onClick = { onOpenLecture(item.lecture) })
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(Icons.Default.CheckCircle, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Using saved timetable", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Updated", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStats(lectures: Int, freeMinutes: Int, reminders: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryStat(Icons.Default.CalendarMonth, lectures.toString(), "lectures", Modifier.weight(1f))
        SummaryStat(Icons.Default.AccessTime, "${freeMinutes / 60}h", "free", Modifier.weight(1f))
        SummaryStat(Icons.Default.Notifications, reminders.toString(), "scheduled", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier.heightIn(min = 104.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IconBadge(icon, size = 38.dp)
            Spacer(Modifier.height(7.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimelineLectureCard(lecture: LectureEntity, state: LectureState, onClick: () -> Unit) {
    val label = when (state) {
        LectureState.COMPLETED -> "Completed"
        LectureState.HAPPENING -> "Happening now"
        LectureState.UPCOMING -> "Upcoming"
    }
    val tone = when (state) {
        LectureState.COMPLETED -> PillTone.TEAL
        LectureState.HAPPENING -> PillTone.ORANGE
        LectureState.UPCOMING -> PillTone.TEAL
    }
    val accent = if (state == LectureState.HAPPENING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(56.dp).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatTime(lecture.startMinutes), fontWeight = FontWeight.Bold)
            Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(lecture.endMinutes), fontWeight = FontWeight.Bold)
        }
        Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(22.dp))
            androidx.compose.foundation.layout.Box(Modifier.width(3.dp).height(22.dp).background(accent))
            androidx.compose.foundation.layout.Box(Modifier.size(27.dp).background(if (state == LectureState.HAPPENING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (state == LectureState.COMPLETED) Icons.Default.CheckCircle else Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            androidx.compose.foundation.layout.Box(Modifier.width(3.dp).height(112.dp).background(accent))
        }
        Card(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = if (state == LectureState.HAPPENING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(label, tone)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = if (state == LectureState.HAPPENING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(7.dp))
                Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(5.dp))
                lecture.venue?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    StatusPill(lecture.lectureType ?: "Lecture", PillTone.GRAY)
                }
            }
        }
    }
}

@Composable
private fun FreePeriodCard(start: Int, end: Int, next: LectureEntity?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(72.dp)) {
                Text(formatTime(start), fontWeight = FontWeight.Bold)
                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(end), fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("You're free for ${formatDuration(end - start)} 🎉", style = MaterialTheme.typography.titleMedium)
                next?.let { Text("Next lecture: ${it.subject ?: "Lecture"} · ${formatTime(it.startMinutes)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(33.dp))
        }
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
private fun freeMinutes(items: List<DayItem>): Int = items.filterIsInstance<DayItem.Free>().sumOf { it.end - it.start }
private fun nextLectureAfter(items: List<DayItem>, end: Int): LectureEntity? = items.filterIsInstance<DayItem.Lecture>().map { it.lecture }.firstOrNull { it.startMinutes >= end }

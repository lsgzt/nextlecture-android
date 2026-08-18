package com.gndec.timetable.ui.day

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
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

    val scheduledReminders by produceState(initialValue = 0, group, nowMillis / 60_000L) {
        value = if (group == null) 0 else container.db.alarmDao().countFuture(nowMillis)
    }
    val time = Instant.ofEpochMilli(nowMillis).atZone(Zone)
    val nowMinutes = time.hour * 60 + time.minute
    val todays = lectures.filter { it.dayOfWeek == time.dayOfWeek.value }.sortedBy { it.startMinutes }
    val timeline = buildTimeline(todays, nowMinutes)
    val freeMinutes = timeline.filterIsInstance<TimelineItem.Free>().sumOf { it.end - it.start }
    val background = MaterialTheme.colorScheme.background
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryText = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val dateLabel = "${group ?: "Select group"} · ${time.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} · ${DateFormatter.format(time)}"

    Scaffold(
        containerColor = background,
        bottomBar = {
            PremiumBottomBar("today") { route ->
                when (route) {
                    "home" -> onOpenHome()
                    "alerts" -> onOpenAlerts()
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(background)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PremiumPageHeader("Today", dateLabel, onSettings = onOpenSettings)
                }
                item {
                    DaySummary(
                        lectures = todays.size,
                        freeMinutes = freeMinutes,
                        reminders = scheduledReminders,
                        accent = accent,
                        primaryText = primaryText,
                        muted = muted,
                        cardColor = MaterialTheme.colorScheme.surface
                    )
                }
                if (timeline.isEmpty()) {
                    item { EmptyDayCard(cardColor = MaterialTheme.colorScheme.surface, primaryText = primaryText, muted = muted) }
                } else {
                    items(
                        timeline,
                        key = { item ->
                            when (item) {
                                is TimelineItem.Lecture -> "lecture-${item.lecture.id}"
                                is TimelineItem.Free -> "free-${item.start}-${item.end}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is TimelineItem.Lecture -> TimelineLectureCard(
                                lecture = item.lecture,
                                state = item.state,
                                nowMinutes = nowMinutes,
                                accent = accent,
                                primaryText = primaryText,
                                muted = muted,
                                onClick = { onOpenLecture(item.lecture) }
                            )
                            is TimelineItem.Free -> FreePeriod(
                                start = item.start,
                                end = item.end,
                                muted = muted,
                                accent = accent
                            )
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Using saved timetable · reminders work offline", color = muted, style = MaterialTheme.typography.bodySmall)
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
private fun TodayHeader(
    group: String,
    dateLabel: String,
    accent: Color,
    primaryText: Color,
    muted: Color,
    onSettings: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Today", color = primaryText, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(group, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(dateLabel, color = muted, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = accent, modifier = Modifier.size(27.dp))
        }
    }
}

@Composable
private fun DaySummary(
    lectures: Int,
    freeMinutes: Int,
    reminders: Int,
    accent: Color,
    primaryText: Color,
    muted: Color,
    cardColor: Color
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(lectures.toString(), "LECTURES", accent, primaryText, muted, cardColor, Modifier.weight(1f))
        MetricCard(formatDurationShort(freeMinutes), "FREE", primaryText, primaryText, muted, cardColor, Modifier.weight(1f))
        MetricCard(reminders.toString(), "SCHEDULED", primaryText, primaryText, muted, cardColor, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(
    value: String,
    label: String,
    valueColor: Color,
    primaryText: Color,
    muted: Color,
    cardColor: Color,
    modifier: Modifier
) {
    Card(
        modifier.height(102.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(value, color = valueColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(label, color = if (label == "LECTURES") primaryText else muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyDayCard(cardColor: Color, primaryText: Color, muted: Color) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(22.dp)) {
            Text("You’re free today", color = primaryText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("No lectures are scheduled for this day.", color = muted)
        }
    }
}

@Composable
private fun TimelineLectureCard(
    lecture: LectureEntity,
    state: LectureState,
    nowMinutes: Int,
    accent: Color,
    primaryText: Color,
    muted: Color,
    onClick: () -> Unit
) {
    val live = state == LectureState.HAPPENING
    val cardColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val railColor = if (live) accent else muted.copy(alpha = 0.65f)
    val remaining = (lecture.endMinutes - nowMinutes).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
        TimelineRail(lecture, state, railColor, primaryText, muted)
        Spacer(Modifier.width(12.dp))
        Card(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (state) {
                            LectureState.COMPLETED -> "COMPLETED"
                            LectureState.HAPPENING -> "LIVE NOW"
                            LectureState.UPCOMING -> "UPCOMING"
                        },
                        color = if (live) accent else muted,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.7.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (live) {
                        Text("${formatDurationUpper(remaining)} REMAINING", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    } else {
                        if (state == LectureState.UPCOMING) Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = muted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(formatTime(lecture.startMinutes), color = primaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(15.dp))
                Text((lecture.subject ?: "Lecture").uppercase(), color = primaryText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(13.dp))
                TimelineMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() }?.uppercase() ?: "TEACHER UNAVAILABLE", primaryText, muted)
                lecture.venue?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    TimelineMetaRow(Icons.Default.LocationOn, it, primaryText, muted)
                }
            }
        }
    }
}

@Composable
private fun TimelineRail(lecture: LectureEntity, state: LectureState, railColor: Color, primaryText: Color, muted: Color) {
    Column(Modifier.width(116.dp).heightIn(min = 185.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(2.dp).height(26.dp).background(railColor.copy(alpha = 0.45f)))
        Box(Modifier.size(56.dp).background(railColor.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                when (state) {
                    LectureState.COMPLETED -> Icons.Default.CheckCircle
                    LectureState.HAPPENING -> Icons.Default.Notifications
                    LectureState.UPCOMING -> Icons.Default.AccessTime
                },
                contentDescription = null,
                tint = railColor,
                modifier = Modifier.size(25.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(formatTime(lecture.startMinutes), color = if (state == LectureState.HAPPENING) railColor else primaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(formatTime(lecture.endMinutes), color = muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.width(2.dp).weight(1f).background(railColor.copy(alpha = 0.35f)))
    }
}

@Composable
private fun TimelineMetaRow(icon: ImageVector, text: String, primaryText: Color, muted: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = primaryText.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FreePeriod(start: Int, end: Int, muted: Color, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(22.dp).background(muted.copy(alpha = 0.32f)))
            Box(Modifier.size(14.dp).background(accent.copy(alpha = 0.35f), CircleShape))
            Box(Modifier.width(2.dp).height(22.dp).background(muted.copy(alpha = 0.32f)))
        }
        Spacer(Modifier.width(12.dp))
        Text("${formatDurationUpper(end - start)} FREE PERIOD", color = muted, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.7.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val suffix = if (hour >= 12) "PM" else "AM"
    val displayHour = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

private fun formatDurationUpper(minutes: Int): String = when {
    minutes >= 60 -> {
        val hours = minutes / 60
        val remainder = minutes % 60
        if (remainder == 0) "${hours}H" else "${hours}H ${remainder}M"
    }
    else -> "${minutes}M"
}

private fun formatDurationShort(minutes: Int): String = when {
    minutes >= 60 -> {
        val hours = minutes / 60
        val remainder = minutes % 60
        if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
    }
    else -> "${minutes}m"
}

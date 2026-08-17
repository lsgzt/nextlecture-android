package com.gndec.timetable.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.ui.BottomBar
import com.gndec.timetable.ui.CompactUpcomingCard
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.InfoRow
import com.gndec.timetable.ui.NextLectureCard
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.util.Formatters
import java.time.Instant
import java.time.ZoneId

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLecture: (LectureEntity) -> Unit
) {
    val vm = remember { HomeViewModel(container) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val state by vm.ui.collectAsStateWithLifecycle()
    val fetchState by vm.fetchState.collectAsStateWithLifecycle()
    val next = when (val status = state.status) {
        is NextLectureEngine.Status.Next -> status.lecture
        is NextLectureEngine.Status.HappeningNow -> status.lecture
        else -> null
    }
    val isHappening = state.status is NextLectureEngine.Status.HappeningNow
    val nextLater = when (val status = state.status) {
        is NextLectureEngine.Status.DoneForToday -> status.next
        is NextLectureEngine.Status.FreeDay -> status.next
        else -> null
    }
    val countdown = when (val status = state.status) {
        is NextLectureEngine.Status.Next -> Formatters.countdown(status.startsInMinutes)
        is NextLectureEngine.Status.HappeningNow -> Formatters.countdown(status.endsInMinutes)
        else -> ""
    }
    val hour = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).hour
    val greeting = when {
        hour < 12 -> "Good morning 👋"
        hour < 17 -> "Good afternoon 👋"
        else -> "Good evening 👋"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar("home") { route -> when (route) { "today" -> onOpenToday(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Header(
                        title = state.group ?: "ITB2",
                        subtitle = greeting,
                        onSettings = onOpenSettings,
                        onProfile = onOpenProfile,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            modifier = Modifier.weight(1f).height(74.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Default.CheckCircle, size = 34.dp)
                                Spacer(Modifier.size(9.dp))
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text("Timetable", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        if (state.lastFetch == null) "Not fetched yet" else Formatters.freshnessText(state.lastFetch, state.nowMillis).removePrefix("Updated "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        TealOutlineButton(
                            text = "Fetch again",
                            icon = Icons.Default.Refresh,
                            onClick = vm::fetchAgain,
                            enabled = fetchState !is FetchState.Running,
                            modifier = Modifier.weight(1f).height(74.dp)
                        )
                    }
                }
                if (fetchState !is FetchState.Idle) {
                    item {
                        FetchMessage(fetchState, onRetry = vm::fetchAgain, onDismiss = vm::clearFetchState)
                    }
                }
                item {
                    when {
                        next != null -> Column(Modifier.padding(horizontal = 20.dp)) {
                            NextLectureCard(next, countdown, isHappening, onOpen = { onOpenLecture(next) })
                        }
                        nextLater != null -> Column(Modifier.padding(horizontal = 20.dp)) {
                            NextDayLectureCard(nextLater, onOpen = { onOpenLecture(nextLater.lecture) })
                        }
                        else -> Column(Modifier.padding(horizontal = 20.dp)) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp)) {
                                    Text(
                                        when (state.status) {
                                            is NextLectureEngine.Status.FreeDay -> "No upcoming lectures 🎉"
                                            is NextLectureEngine.Status.DoneForToday -> "You're done for today 🎉"
                                            else -> "No timetable yet"
                                        },
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text("Your saved timetable will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (state.upcomingToday.isNotEmpty()) {
                    item { SectionHeading("UPCOMING", onClick = onOpenToday) }
                    items(state.upcomingToday.take(3), key = { it.id }) { lecture ->
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            CompactUpcomingCard(lecture, onOpen = { onOpenLecture(lecture) })
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        TealOutlineButton(
                            text = "Today's timetable",
                            icon = Icons.Default.CalendarMonth,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenToday
                        )
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Default.CloudDone, size = 44.dp)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reminders work offline", fontWeight = FontWeight.Bold)
                                Text("We'll alert you using this cached timetable even if you're offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextDayLectureCard(timed: NextLectureEngine.TimedLecture, onOpen: () -> Unit) {
    val lecture = timed.lecture
    val dayLabel = when (timed.daysAhead) {
        1 -> "Tomorrow"
        else -> "In ${timed.daysAhead} days"
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.CalendarMonth, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 44.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("NEXT LECTURE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 0.6f.sp)
                    Text(dayLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            InfoRow(Icons.Default.AccessTime, Formatters.range(lecture.startMinutes, lecture.endMinutes))
            lecture.venue?.takeIf { it.isNotBlank() }?.let { InfoRow(Icons.Default.LocationOn, it) }
            InfoRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable")
        }
    }
}

@Composable
private fun SectionHeading(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onClick) { Text("See all", color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun FetchMessage(state: FetchState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                FetchState.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                is FetchState.Ok -> IconBadge(Icons.Default.CheckCircle, size = 28.dp)
                FetchState.UpToDate -> IconBadge(Icons.Default.CheckCircle, size = 28.dp)
                is FetchState.Failed -> IconBadge(Icons.Default.Refresh, size = 28.dp)
                else -> Unit
            }
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                when (state) {
                    FetchState.Running -> "Fetching timetable…"
                    is FetchState.Ok -> "Timetable updated"
                    FetchState.UpToDate -> "Timetable is already up to date"
                    is FetchState.Failed -> "Couldn't update timetable"
                    else -> ""
                },
                modifier = Modifier.weight(1f)
            )
            if (state is FetchState.Failed) androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
            else if (state !is FetchState.Running) androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

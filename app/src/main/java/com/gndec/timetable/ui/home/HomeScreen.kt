package com.gndec.timetable.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.ui.PremiumAnnouncementCard
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.PremiumErpNoticeBanner
import com.gndec.timetable.ui.PremiumBrandHeader
import com.gndec.timetable.ui.PremiumNextLectureCard
import com.gndec.timetable.ui.PremiumOfflineCard
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.PremiumStatusRow
import com.gndec.timetable.ui.PremiumTodayPreview
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.util.Formatters
import java.time.Instant
import java.time.ZoneId

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLecture: (LectureEntity) -> Unit
) {
    val vm = remember { HomeViewModel(container) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val state by vm.ui.collectAsStateWithLifecycle()
    val fetchState by vm.fetchState.collectAsStateWithLifecycle()
    val announcement by container.announcementManager.latest.collectAsStateWithLifecycle()
    val erpNotices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val releaseUpdate by container.releaseUpdateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val next = when (val status = state.status) {
        is NextLectureEngine.Status.Next -> status.lecture
        is NextLectureEngine.Status.HappeningNow -> status.lecture
        else -> null
    }
    val nextLater = when (val status = state.status) {
        is NextLectureEngine.Status.DoneForToday -> status.next
        is NextLectureEngine.Status.FreeDay -> status.next
        else -> null
    }
    val isHappening = state.status is NextLectureEngine.Status.HappeningNow
    val countdown = when (val status = state.status) {
        is NextLectureEngine.Status.Next -> Formatters.countdown(status.startsInMinutes)
        is NextLectureEngine.Status.HappeningNow -> Formatters.countdown(status.endsInMinutes)
        else -> ""
    }
    val hour = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).hour
    val greeting = when {
        hour < 12 -> "Good morning."
        hour < 17 -> "Good afternoon."
        else -> "Good evening."
    }
    val dateLabel = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).let {
        "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar(Char::uppercase)}"
    }
    val updatedText = state.lastFetch?.let { Formatters.freshnessText(it, state.nowMillis).removePrefix("Updated ") } ?: "No sync yet"
    val todayIso = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val todayNotice = erpNotices.firstOrNull { it.publishedDate == todayIso }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PremiumBottomBar("home") { route -> when (route) { "today" -> onOpenToday(); "notice" -> onOpenNotice(); "alerts" -> onOpenAlerts() } } }
    ) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    PremiumBrandHeader(
                        group = state.group ?: "ITB2",
                        greeting = greeting,
                        onSettings = onOpenSettings,
                        onProfile = onOpenProfile
                    )
                }
                todayNotice?.let { notice ->
                    item {
                        PremiumErpNoticeBanner(
                            notice = notice,
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.url))) },
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
                item {
                    PremiumStatusRow(
                        updatedText = updatedText,
                        onFetch = vm::fetchAgain,
                        fetchEnabled = fetchState !is FetchState.Running
                    )
                }
                if (fetchState !is FetchState.Idle) {
                    item {
                        Text(
                            when (fetchState) {
                                FetchState.Running -> "Refreshing your timetable…"
                                is FetchState.Ok -> "Timetable updated"
                                FetchState.UpToDate -> "Already up to date"
                                is FetchState.Failed -> "Couldn’t refresh timetable"
                                else -> ""
                            },
                            color = if (fetchState is FetchState.Failed) GndecOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                if (releaseUpdate.updateAvailable) {
                    item {
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("UPDATE AVAILABLE · RELEASE ${releaseUpdate.latestMarker}", color = GndecOrange, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.1.sp)
                                Text(releaseUpdate.releaseName.ifBlank { "A newer GNDEC Timetable build is ready" }, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("Download the latest APK from GitHub to get the newest fixes and features.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                androidx.compose.material3.TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.gndec.timetable.domain.ReleaseUpdateManager.DOWNLOAD_URL)))
                                }) { Text("Download update") }
                            }
                        }
                    }
                }
                if (next != null) {
                    item {
                        PremiumNextLectureCard(
                            lecture = next,
                            dayLabel = if (isHappening) "Now" else "Today",
                            countdown = countdown,
                            isHappening = isHappening,
                            onClick = { onOpenLecture(next) },
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                        )
                    }
                } else if (nextLater != null) {
                    item {
                        PremiumNextLectureCard(
                            lecture = nextLater.lecture,
                            dayLabel = if (nextLater.daysAhead == 1) "Tomorrow" else "In ${nextLater.daysAhead} days",
                            countdown = "",
                            isHappening = false,
                            onClick = { onOpenLecture(nextLater.lecture) },
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                        )
                    }
                } else {
                    item {
                        androidx.compose.material3.Card(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(26.dp)) {
                                Text("A clear day ahead", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("No upcoming lecture is scheduled. Enjoy the breathing room.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                announcement?.let { current ->
                    item { PremiumAnnouncementCard(current, androidx.compose.ui.Modifier.padding(horizontal = 20.dp)) }
                }
                item {
                    PremiumTodayPreview(
                        lectures = state.todayLectures,
                        dateLabel = dateLabel,
                        onOpen = onOpenToday,
                        onLecture = onOpenLecture,
                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                    )
                }
                item { PremiumOfflineCard(androidx.compose.ui.Modifier.padding(horizontal = 20.dp)) }
            }
        }
    }
}

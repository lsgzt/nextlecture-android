package com.gndec.timetable.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.PremiumAnnouncementCard
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumErpNoticeBanner
import com.gndec.timetable.ui.PremiumBrandHeader
import com.gndec.timetable.ui.PremiumNextLectureCard
import com.gndec.timetable.ui.PremiumOfflineCard
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.PremiumStatusRow
import com.gndec.timetable.ui.PremiumTodayPreview
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.util.Formatters
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import com.gndec.timetable.ui.motion.pressFeedback
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
    onOpenVacantRooms: () -> Unit,
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

    // Hero slot identity. Countdown strings are deliberately excluded so the
    // per-minute ticker never re-triggers the transition — only meaningful
    // state changes (which lecture / which phase) do.
    val hero = when (val s = state.status) {
        is NextLectureEngine.Status.Next -> HeroTarget("next", s.lecture.id, s.lecture)
        is NextLectureEngine.Status.HappeningNow -> HeroTarget("happening", s.lecture.id, s.lecture)
        is NextLectureEngine.Status.DoneForToday -> HeroTarget("later", s.next?.lecture?.id, s.next?.lecture, s.next?.daysAhead ?: 0)
        is NextLectureEngine.Status.FreeDay -> HeroTarget("free", s.next?.lecture?.id, s.next?.lecture, s.next?.daysAhead ?: 0)
        NextLectureEngine.Status.NoData -> HeroTarget("none", null, null)
    }
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
    val todayNotice = erpNotices
        .filter { notice ->
            notice.source != "GNDEC ERP Notice Board" &&
                notice.bannerStartDate.isNotBlank() &&
                notice.bannerUntilDate.isNotBlank() &&
                todayIso >= notice.bannerStartDate && todayIso <= notice.bannerUntilDate
        }
        .maxByOrNull { it.firstSeenAt.ifBlank { it.bannerStartDate } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 22.dp + PremiumBottomBarContentClearance),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(key = "header") {
                    PremiumBrandHeader(
                        group = state.group ?: "ITB2",
                        greeting = greeting,
                        onSettings = onOpenSettings,
                        onProfile = onOpenProfile,
                        modifier = androidx.compose.ui.Modifier.itemEntrance(0)
                    )
                }
                todayNotice?.let { notice ->
                    item(key = "homepage-notice") {
                        PremiumErpNoticeBanner(
                            notice = notice,
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.url))) },
                            modifier = androidx.compose.ui.Modifier.itemEntrance(1).padding(horizontal = 20.dp).animateItem()
                        )
                    }
                }
                item(key = "status") {
                    Box(androidx.compose.ui.Modifier.itemEntrance(2).animateItem()) {
                        PremiumStatusRow(
                            updatedText = updatedText,
                            onFetch = vm::fetchAgain,
                            fetchEnabled = fetchState !is FetchState.Running
                        )
                    }
                }
                if (fetchState !is FetchState.Idle) {
                    item(key = "fetch-message") {
                        val swapIn = motionTween<Float>(Motion.Normal)
                        val swapOut = motionTween<Float>(Motion.Fast)
                        AnimatedContent(
                            targetState = fetchState,
                            modifier = androidx.compose.ui.Modifier.animateItem(),
                            transitionSpec = { fadeIn(swapIn) togetherWith fadeOut(swapOut) },
                            label = "fetchMessage"
                        ) { current ->
                            when (current) {
                                FetchState.Idle -> {}
                                FetchState.Running -> FetchMessage("Refreshing your timetable…", false)
                                is FetchState.Ok -> FetchMessage("Timetable updated", false)
                                FetchState.UpToDate -> FetchMessage("Already up to date", false)
                                is FetchState.Failed -> FetchMessage("Couldn’t refresh timetable", true)
                            }
                        }
                    }
                }
                if (releaseUpdate.updateAvailable) {
                    item(key = "release-update") {
                        ReleaseUpdateCard(
                            releaseUpdate = releaseUpdate,
                            onDownload = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.gndec.timetable.domain.ReleaseUpdateManager.DOWNLOAD_URL))) },
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp).animateItem()
                        )
                    }
                }
                item(key = "hero") {
                    val heroIn = motionTween<Float>(Motion.Emphasized)
                    val heroOut = motionTween<Float>(Motion.Fast)
                    val heroSlide = motionTween<IntOffset>(Motion.Emphasized, Motion.EasingEnter)
                    AnimatedContent(
                        targetState = hero,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().animateItem().itemEntrance(3),
                        transitionSpec = {
                            (fadeIn(heroIn) + slideInVertically(heroSlide) { it / 10 }) togetherWith fadeOut(heroOut)
                        },
                        label = "homeHero"
                    ) { target ->
                        when {
                            (target.kind == "next" || target.kind == "happening") && target.lecture != null -> {
                                val happening = target.kind == "happening"
                                PremiumNextLectureCard(
                                    lecture = target.lecture!!,
                                    dayLabel = if (happening) "Now" else "Today",
                                    countdown = countdown,
                                    isHappening = happening,
                                    onClick = { onOpenLecture(target.lecture!!) },
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                                )
                            }
                            target.lecture != null && (target.kind == "later" || target.kind == "free") -> {
                                PremiumNextLectureCard(
                                    lecture = target.lecture!!,
                                    dayLabel = if (target.daysAhead == 1) "Tomorrow" else "In ${target.daysAhead} days",
                                    countdown = "",
                                    isHappening = false,
                                    onClick = { onOpenLecture(target.lecture!!) },
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                                )
                            }
                            else -> {
                                androidx.compose.material3.Card(
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp)
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 30.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                        }
                                        Spacer(Modifier.height(14.dp))
                                        Text("A clear day ahead", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text("No upcoming lecture is scheduled. Enjoy the breathing room.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
                announcement?.let { current ->
                    item(key = "announcement") {
                        PremiumAnnouncementCard(current, androidx.compose.ui.Modifier.itemEntrance(4).padding(horizontal = 20.dp).animateItem())
                    }
                }
                item(key = "today-preview") {
                    Box(androidx.compose.ui.Modifier.itemEntrance(5).animateItem()) {
                        PremiumTodayPreview(
                            lectures = state.todayLectures,
                            dateLabel = dateLabel,
                            onOpen = onOpenToday,
                            onLecture = onOpenLecture,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
                item(key = "vacant-rooms") {
                    VacantRoomsEntryCard(
                        onOpen = onOpenVacantRooms,
                        modifier = androidx.compose.ui.Modifier.itemEntrance(6).padding(horizontal = 20.dp).animateItem()
                    )
                }
                item(key = "offline") {
                    PremiumOfflineCard(androidx.compose.ui.Modifier.itemEntrance(7).padding(horizontal = 20.dp).animateItem())
                }
            }
        }
    }
}

@Composable
private fun FetchMessage(text: String, isError: Boolean) {
    if (text.isEmpty()) return
    Text(
        text,
        color = if (isError) GndecOrange else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
    )
}

/** Quick-action card that opens the Find vacant rooms screen. */
@Composable
private fun VacantRoomsEntryCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val pressInteraction = remember { MutableInteractionSource() }
    androidx.compose.material3.Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Find vacant rooms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("See which rooms are free right now or at any slot", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open vacant rooms", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReleaseUpdateCard(
    releaseUpdate: com.gndec.timetable.domain.ReleaseUpdateState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("UPDATE AVAILABLE · RELEASE ${releaseUpdate.latestMarker}", color = GndecOrange, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.1.sp)
            Text(releaseUpdate.releaseName.ifBlank { "A newer NextLecture build is ready" }, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("Download the latest APK from GitHub to get the newest fixes and features.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.TextButton(onClick = onDownload) { Text("Download update") }
        }
    }
}

/** Hero slot identity — excludes volatile countdown data so minute ticks don't replay transitions. */
private data class HeroTarget(
    val kind: String,
    val lectureId: Long?,
    val lecture: LectureEntity?,
    val daysAhead: Int = 0
)

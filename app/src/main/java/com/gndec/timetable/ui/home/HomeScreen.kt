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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.ExamGate
import com.gndec.timetable.domain.ExamTimeline
import com.gndec.timetable.domain.HolidayGate
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.PremiumAnnouncementCard
import com.gndec.timetable.ui.ExamGroupPickerDialog
import com.gndec.timetable.ui.PremiumExamHeroCard
import com.gndec.timetable.ui.attendance.AttendanceHomeSummary
import com.gndec.timetable.ui.attendance.rememberAttendanceHomeState
import com.gndec.timetable.ui.attendance.subjectAttendance
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumHolidayHeroCard
import com.gndec.timetable.ui.PremiumHolidayOverrideStrip
import com.gndec.timetable.ui.PremiumHomeBannerCarousel
import com.gndec.timetable.ui.PremiumNextAfterHolidayCard
import com.gndec.timetable.ui.holidayHomeBannerItems
import com.gndec.timetable.ui.toHomeBannerItem
import com.gndec.timetable.ui.PremiumBrandHeader
import com.gndec.timetable.ui.PremiumDailyQuote
import com.gndec.timetable.ui.PremiumNextLectureCard
import com.gndec.timetable.ui.PremiumOfflineCard
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.PremiumTodayPreview
import com.gndec.timetable.ui.PremiumUpcomingExamsCard
import com.gndec.timetable.ui.isBannerEligibleOn
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.util.Formatters
import java.time.Instant
import java.time.ZoneId

/** How many notice banners the homepage carousel will show at once. */
private const val MAX_HOME_NOTICE_BANNERS = 4

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenHolidays: () -> Unit,
    onOpenNoticeDetail: (com.gndec.timetable.domain.ErpNotice) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenLecture: (LectureEntity) -> Unit
) {
    val vm = remember { HomeViewModel(container) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val state by vm.ui.collectAsStateWithLifecycle()
    val fetchState by vm.fetchState.collectAsStateWithLifecycle()
    val announcements by container.announcementManager.announcements.collectAsStateWithLifecycle()
    // Hoisted out of the list so the request is not re-issued every time the
    // summary card scrolls out of and back into the viewport.
    val attendanceState = rememberAttendanceHomeState(container)

    // Per-subject verdict for whichever lecture the hero is currently showing.
    // Remembered because the countdown ticks every minute and would otherwise
    // re-scan every attendance record on each tick.
    @Composable
    fun heroAttendance(lecture: LectureEntity) =
        remember(attendanceState, lecture.id) { attendanceState.subjectAttendance(lecture.subject) }
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    val erpNotices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    val releaseUpdate by container.releaseUpdateManager.state.collectAsStateWithLifecycle()
    val quote by container.quoteManager.quote.collectAsStateWithLifecycle()
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
    val studentName by remember {
        container.settings.flow
            .map { settings -> settings.studentName.trim() }
    }.collectAsStateWithLifecycle(initialValue = "")
    val dateLabel = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).let {
        "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar(Char::uppercase)}"
    }
    val todayIso = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    // Holiday banners (today / tomorrow) plus notices from every feed source that
    // is still current. Holidays first, then the freshest notices — all share the
    // same swipeable carousel.
    val tomorrowIso = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).toString()
    val holidayBanners = holidayHomeBannerItems(holidays, todayIso, tomorrowIso)

    // Full-holiday gate: the schedule steps aside for the holiday notice until the
    // student asks for the timetable anyway. The choice is day-scoped (it expires on
    // its own at midnight) and closable, so an accidental tap never sticks.
    val todayFullHolidays = HolidayGate.fullHolidaysOn(holidays, todayIso)
    val holidayNames = remember(todayFullHolidays) { HolidayGate.displayNames(todayFullHolidays) }
    val holidayOverride = settings.holidayTimetableOverrideDay == todayIso
    val holidayActive = todayFullHolidays.isNotEmpty() && !holidayOverride
    val nextAfterHoliday = remember(holidayActive, state.lectures, holidays, todayIso) {
        if (holidayActive) HolidayGate.nextLectureAfter(state.lectures, holidays, todayIso) else null
    }
    val scope = rememberCoroutineScope()
    val showTimetableAnyway: () -> Unit = {
        scope.launch { container.settings.setHolidayTimetableOverrideDay(todayIso) }
    }
    val closeHolidayOverride: () -> Unit = {
        scope.launch { container.settings.setHolidayTimetableOverrideDay("") }
    }
    // Exam mode: a date-sheet announcement that is on takes the big card away from
    // the next lecture, and its own notice card is not repeated further down.
    val exam = state.exam
    val todayDate = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val examView = remember(exam?.exams, state.nowMillis) { exam?.let { ExamTimeline.compute(it.exams, state.nowMillis) } }
    // Only a sheet the exam cards show in full is left out of the feed below. One the
    // parser could not read, or read only in part, stays as an ordinary notice, so no
    // exam is lost.
    val shownAnnouncements =
        if (exam != null && exam.readable && !exam.partial) announcements.filterNot { ExamGate.isOn(it.examMode) } else announcements
    // Every newly published date sheet asks for the group once per launch until it is
    // answered; "Change" on the big card opens the same dialog for a group already known.
    var examGroupPromptSeenFor by rememberSaveable { mutableStateOf("") }
    var showExamGroupDialog by rememberSaveable { mutableStateOf(false) }
    val pickExamGroup: (String) -> Unit = { group ->
        showExamGroupDialog = false
        examGroupPromptSeenFor = exam?.announcement?.id ?: ""
        scope.launch { ExamGate.rememberGroup(container.settings, group) }
    }
    if (exam != null && exam.schedule.groups.size > 1 &&
        (showExamGroupDialog || (exam.needsGroupPick && examGroupPromptSeenFor != exam.announcement.id))
    ) {
        ExamGroupPickerDialog(
            groups = exam.schedule.groups,
            onPick = pickExamGroup,
            onDismiss = {
                showExamGroupDialog = false
                examGroupPromptSeenFor = exam.announcement.id
            }
        )
    }
    val erpBannerNotices = erpNotices
        .filter { it.isBannerEligibleOn(todayIso) }
        .sortedWith(
            compareByDescending<com.gndec.timetable.domain.ErpNotice> { it.firstSeenAt.ifBlank { it.publishedDate } }
                .thenByDescending { it.publishedDate }
                .thenByDescending { it.id }
        )
        // A dated source can qualify for a whole week at once, so cap the carousel
        // to keep it swippable and stop a backlog burying the holiday banners.
        .take(MAX_HOME_NOTICE_BANNERS)
    // On a full holiday the large hero already covers "today is a holiday" — keep
    // official ERP notices in the top carousel and only drop the redundant today-holiday banner.
    // While exams are on the hero shows an exam instead, so that banner is kept.
    val homeBanners = (
        if (holidayActive && exam == null) holidayBanners.filterNot { it.id.startsWith("holiday-today") }
        else holidayBanners
    ) + erpBannerNotices.map { it.toHomeBannerItem() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            // Pull-to-refresh replaces the old "Using saved timetable" card: the
            // refresh affordance lives in the gesture every other app uses, and the
            // fetch-message row below reports the outcome in text.
            val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = fetchState is FetchState.Running,
                onRefresh = { vm.fetchAgain() },
                state = pullState,
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding)
            ) {
                LazyColumn(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 22.dp + PremiumBottomBarContentClearance),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    item(key = "header") {
                        PremiumBrandHeader(
                            group = state.group ?: "ITB2",
                            greeting = greeting,
                            studentName = studentName,
                            title = "NextLecture",
                            onSettings = onOpenSettings,
                            onProfile = onOpenProfile,
                            onSearch = onOpenSearch,
                            modifier = androidx.compose.ui.Modifier.itemEntrance(0)
                        )
                    }
                    if (homeBanners.isNotEmpty()) {
                        item(key = "homepage-notices") {
                            PremiumHomeBannerCarousel(
                                items = homeBanners,
                                onOpen = { item ->
                                    if (item.isHoliday) {
                                        onOpenHolidays()
                                    } else {
                                        val noticeId = item.noticeId
                                        val notice = if (noticeId != null) {
                                            erpNotices.firstOrNull { it.id == noticeId }
                                        } else null
                                        if (notice != null) {
                                            onOpenNoticeDetail(notice)
                                        } else if (!item.url.isNullOrBlank()) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                                        } else {
                                            onOpenNotice()
                                        }
                                    }
                                },
                                modifier = androidx.compose.ui.Modifier.itemEntrance(1).padding(horizontal = 20.dp).animateItem()
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
                                    is FetchState.Ok -> FetchMessage("Timetable updated just now", false)
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
                    // The strip leads back to the timetable, which exam mode has stepped aside.
                    if (todayFullHolidays.isNotEmpty() && holidayOverride && exam == null) {
                        item(key = "holiday-override-strip") {
                            PremiumHolidayOverrideStrip(
                                onClose = closeHolidayOverride,
                                modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp).animateItem()
                            )
                        }
                    }
                    if (exam != null) {
                        item(key = "exam-hero") {
                            val examIn = motionTween<Float>(Motion.Emphasized)
                            val examOut = motionTween<Float>(Motion.Fast)
                            // The slot is keyed to the exam in it, so handing the big card to the
                            // next exam animates, while the minute tick that updates the countdown does not.
                            val slotted = examView?.featured?.let { "${it.exam.date}|${it.exam.startMinutes}" }
                            AnimatedContent(
                                targetState = slotted,
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth().animateItem().itemEntrance(3),
                                transitionSpec = { fadeIn(examIn) togetherWith fadeOut(examOut) },
                                label = "examHero"
                            ) { _ ->
                                PremiumExamHeroCard(
                                    state = exam,
                                    view = examView ?: ExamTimeline.compute(exam.exams, state.nowMillis),
                                    today = todayDate,
                                    onOpenSchedule = onOpenToday,
                                    onPickGroup = pickExamGroup,
                                    onChangeGroup = { showExamGroupDialog = true },
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                                )
                            }
                        }
                        // What is still to come, under the big card. Nothing is listed while
                        // the card is asking for the group, or once the schedule has run out.
                        val upcomingExams = examView?.upcoming.orEmpty()
                        if (!exam.needsGroupPick && upcomingExams.isNotEmpty()) {
                            item(key = "upcoming-exams") {
                                PremiumUpcomingExamsCard(
                                    upcoming = upcomingExams,
                                    today = todayDate,
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp).itemEntrance(3).animateItem()
                                )
                            }
                        }
                    } else if (holidayActive) {
                        item(key = "holiday-hero") {
                            PremiumHolidayHeroCard(
                                holidayNames = holidayNames,
                                onViewTimetable = showTimetableAnyway,
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp).animateItem().itemEntrance(3)
                            )
                        }
                        // Sit directly under the holiday hero (not below attendance).
                        nextAfterHoliday?.let { next ->
                            item(key = "next-after-holiday") {
                                PremiumNextAfterHolidayCard(
                                    next = next,
                                    todayIso = todayIso,
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp).itemEntrance(3).animateItem()
                                )
                            }
                        }
                    } else {
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
                                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp),
                                            attendance = heroAttendance(target.lecture!!),
                                            mentorName = settings.mentorName,
                                            mentorVenue = settings.mentorVenue
                                        )
                                    }
                                    target.lecture != null && (target.kind == "later" || target.kind == "free") -> {
                                        PremiumNextLectureCard(
                                            lecture = target.lecture!!,
                                            dayLabel = if (target.daysAhead == 1) "Tomorrow" else "In ${target.daysAhead} days",
                                            countdown = "",
                                            isHappening = false,
                                            onClick = { onOpenLecture(target.lecture!!) },
                                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp),
                                            attendance = heroAttendance(target.lecture!!),
                                            mentorName = settings.mentorName,
                                            mentorVenue = settings.mentorVenue
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
                    }
                    // Rendered only once a quote has been fetched: before the first successful
                    // call there is nothing to say, and a placeholder would just be a gap.
                    quote?.let { current ->
                        item(key = "daily-quote") {
                            PremiumDailyQuote(current, androidx.compose.ui.Modifier.itemEntrance(4).animateItem())
                        }
                    }
                    // Every eligible announcement, newest first, stacked in feed order. Notices from
                    // the college and every other announcement stay; only a date sheet that is
                    // already shown as exam cards above is left out.
                    shownAnnouncements.forEachIndexed { index, current ->
                        item(key = "announcement-${current.id}") {
                            PremiumAnnouncementCard(
                                announcement = current,
                                modifier = androidx.compose.ui.Modifier
                                    .itemEntrance(5 + index)
                                    .padding(horizontal = 20.dp)
                                    .animateItem()
                            )
                        }
                    }
                    item(key = "attendance-summary") {
                        Box(androidx.compose.ui.Modifier.itemEntrance(6).padding(horizontal = 20.dp).animateItem()) {
                            AttendanceHomeSummary(state = attendanceState, onClick = onOpenAttendance)
                        }
                    }
                    if (!holidayActive && exam == null) {
                        item(key = "today-preview") {
                            Box(androidx.compose.ui.Modifier.itemEntrance(6).animateItem()) {
                                PremiumTodayPreview(
                                    lectures = state.todayLectures,
                                    dateLabel = dateLabel,
                                    onOpen = onOpenToday,
                                    onLecture = onOpenLecture,
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp)
                                )
                            }
                        }
                    }
                    item(key = "offline") {
                        PremiumOfflineCard(
                            androidx.compose.ui.Modifier.itemEntrance(7).padding(horizontal = 20.dp).animateItem(),
                            text = if (exam != null) "Your exam schedule stays available without internet."
                            else "Saved timetable and reminders stay available without internet."
                        )
                    }
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
            Text("Download the latest APK from GitHub Releases to get the newest fixes and features.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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

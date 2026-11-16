package com.gndec.timetable.ui.day

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.Friend
import com.gndec.timetable.domain.FriendDay
import com.gndec.timetable.domain.FriendEarlySlot
import com.gndec.timetable.domain.FriendLateSlot
import com.gndec.timetable.domain.earlySlotsBefore
import com.gndec.timetable.domain.lateSlotsAfter
import com.gndec.timetable.domain.FriendFreeWindow
import com.gndec.timetable.domain.HolidayGate
import com.gndec.timetable.domain.friendAvailabilitySentences
import com.gndec.timetable.domain.friendsFreeWithin
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumHolidayHeroCard
import com.gndec.timetable.ui.PremiumHolidayOverrideStrip
import com.gndec.timetable.ui.PremiumNextAfterHolidayCard
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionSpring
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.lectureTypeColors
import com.gndec.timetable.util.Formatters
import com.gndec.timetable.util.displayTeacher
import com.gndec.timetable.util.resolvedVenue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private sealed class TimelineItem {
    data class Lecture(val lecture: LectureEntity, val state: LectureState) : TimelineItem()
    data class Free(val start: Int, val end: Int, val next: LectureEntity?) : TimelineItem()
}

private enum class LectureState { COMPLETED, HAPPENING, UPCOMING }
private enum class DayViewMode(val title: String) { TODAY("Today"), TOMORROW("Tomorrow"), WEEK("Full week") }

/** Keys day content so Today↔Tomorrow and holiday↔timetable both animate. */
private data class DayContentKey(val mode: DayViewMode, val showHoliday: Boolean)

private val Zone = ZoneId.systemDefault()
private val DateFormatter = DateTimeFormatter.ofPattern("d MMMM")
private val TimelineHorizontalPadding = 16.dp
private val TimelineCardGap = 8.dp
private val TimelineItemGap = 10.dp
private val RailColumnWidth = 116.dp
private val RailNodeSize = 56.dp
// Floor for every lecture card body. The rail content (~139dp) always fits
// inside it, so each row is exactly as tall as its card and the gap between
// cards stays constant regardless of subject length or venue presence.
private val RailMinCardHeight = 150.dp
private val RailCenterX = TimelineHorizontalPadding + RailColumnWidth / 2

@Composable
fun DayScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit
) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val group = settings.group
    val lectures by remember(group) {
        if (group == null) flowOf(emptyList()) else container.db.lectureDao().observeForGroup(group)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val meta by container.db.metaDao().observe().collectAsStateWithLifecycle(initialValue = null)
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewModeName by rememberSaveable { mutableStateOf(DayViewMode.TODAY.name) }
    val viewMode = DayViewMode.valueOf(viewModeName)

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay((60_000L - (nowMillis % 60_000L)).coerceAtLeast(1_000L))
        }
    }

    val scheduledReminders by produceState(initialValue = 0, group, nowMillis / 60_000L) {
        value = if (group == null) 0 else container.db.alarmDao().countFuture(nowMillis)
    }
    // Friends are stored locally; their timetables come from the groups this
    // device has already parsed, so this costs no network at all.
    val friends = remember(settings.friendsJson) { container.settings.decodeFriends(settings.friendsJson) }
    val time = Instant.ofEpochMilli(nowMillis).atZone(Zone)
    val nowMinutes = time.hour * 60 + time.minute
    val todayDow = time.dayOfWeek.value
    val tomorrowDow = if (todayDow == 7) 1 else todayDow + 1
    val todayFriendDays = rememberFriendDays(container, friends, todayDow)
    val tomorrowFriendDays = rememberFriendDays(container, friends, tomorrowDow)
    val reduced = LocalReducedMotion.current
    val background = MaterialTheme.colorScheme.background
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryText = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    // Full-holiday gate (see HolidayGate): on a full holiday, today's schedule is
    // replaced by the holiday notice. "Not a holiday?" reveals the timetable for
    // that one day — day-scoped and closable, so an accidental tap never sticks.
    val scope = rememberCoroutineScope()
    val todayIso = time.toLocalDate().toString()
    val todayFullHolidays = HolidayGate.fullHolidaysOn(holidays, todayIso)
    val holidayNames = HolidayGate.displayNames(todayFullHolidays)
    val holidayOverride = settings.holidayTimetableOverrideDay == todayIso
    val holidayActive = todayFullHolidays.isNotEmpty() && !holidayOverride
    // Keep next-after-holiday available during the holiday↔timetable exit animation
    // (holidayActive flips before the outgoing content is fully gone).
    val nextAfterHoliday = if (todayFullHolidays.isNotEmpty()) {
        HolidayGate.nextLectureAfter(lectures, holidays, todayIso)
    } else {
        null
    }
    val selectedDate = when (viewMode) {
        DayViewMode.TODAY -> time.toLocalDate()
        DayViewMode.TOMORROW -> time.toLocalDate().plusDays(1)
        DayViewMode.WEEK -> time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val dateLabel = when (viewMode) {
        DayViewMode.TODAY -> "${group ?: "Select group"} · Today · ${DateFormatter.format(selectedDate)}"
        DayViewMode.TOMORROW -> "${group ?: "Select group"} · Tomorrow · ${DateFormatter.format(selectedDate)}"
        DayViewMode.WEEK -> "${group ?: "Select group"} · ${DateFormatter.format(selectedDate)} – ${DateFormatter.format(selectedDate.plusDays(6))}"
    }
    Scaffold(containerColor = background) { padding ->
        Box(Modifier.fillMaxSize().background(background)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp + PremiumBottomBarContentClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { PremiumPageHeader(viewMode.title, dateLabel, onSettings = onOpenSettings) }
                item {
                    DayModeSelector(viewMode) { viewModeName = it.name }
                }
                item(key = "day-view-content") {
                    AnimatedContent(
                        targetState = viewMode == DayViewMode.WEEK,
                        transitionSpec = {
                            if (reduced) {
                                fadeIn(tween(Motion.Emphasized, easing = Motion.EasingStandard)) togetherWith
                                    fadeOut(tween(Motion.Normal, easing = Motion.EasingStandard))
                            } else {
                                (scaleIn(tween(Motion.Emphasized, easing = Motion.EasingEnter), initialScale = 0.98f) +
                                    fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                    fadeOut(tween(Motion.Normal, easing = Motion.EasingExit))
                            }
                        },
                        label = "weekModeContent"
                    ) { isWeekMode ->
                        if (isWeekMode) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                WeekSummary(
                                    totalLectures = lectures.size,
                                    weekStart = selectedDate,
                                    cardColor = MaterialTheme.colorScheme.surface,
                                    primaryText = primaryText,
                                    muted = muted,
                                    accent = accent
                                )
                                WeekTypeLegend(muted = muted)
                                (0L..6L).map { selectedDate.plusDays(it) }.forEach { date ->
                                    WeekDaySection(
                                        date = date,
                                        lectures = lectures.filter { it.dayOfWeek == date.dayOfWeek.value }.sortedBy { it.startMinutes },
                                        primaryText = primaryText,
                                        muted = muted,
                                        accent = accent,
                                        onOpenLecture = onOpenLecture,
                                        mentorName = settings.mentorName,
                                        mentorVenue = settings.mentorVenue
                                    )
                                }
                            }
                        } else {
                            // Animate Today ↔ Tomorrow and holiday ↔ timetable with the same
                            // premium motion so neither switch hard-cuts.
                            AnimatedContent(
                                targetState = DayContentKey(viewMode, holidayActive),
                                transitionSpec = {
                                    if (reduced) {
                                        fadeIn(tween(Motion.Normal, easing = Motion.EasingStandard)) togetherWith
                                            fadeOut(tween(Motion.Fast, easing = Motion.EasingStandard))
                                    } else if (initialState.mode != targetState.mode) {
                                        // Segmented-control motion: content slides toward the
                                        // direction of the newly selected tab.
                                        val dir = if (targetState.mode.ordinal >= initialState.mode.ordinal) 1 else -1
                                        (slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 12 } +
                                            fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                            (slideOutHorizontally(tween(Motion.Normal, easing = Motion.EasingExit)) { -dir * it / 14 } +
                                                fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)))
                                    } else {
                                        // Holiday hero ↔ timetable reveal on the same day.
                                        // Timetable rises in under a lifting holiday card (and the
                                        // reverse when closing the override).
                                        val revealingTimetable = initialState.showHoliday && !targetState.showHoliday
                                        if (revealingTimetable) {
                                            (slideInVertically(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { it / 10 } +
                                                fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter)) +
                                                scaleIn(tween(Motion.Emphasized, easing = Motion.EasingEnter), initialScale = 0.98f)) togetherWith
                                                (slideOutVertically(tween(Motion.Normal, easing = Motion.EasingExit)) { -it / 12 } +
                                                    fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)) +
                                                    scaleOut(tween(Motion.Normal, easing = Motion.EasingExit), targetScale = 0.98f))
                                        } else {
                                            (slideInVertically(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { -it / 12 } +
                                                fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter)) +
                                                scaleIn(tween(Motion.Emphasized, easing = Motion.EasingEnter), initialScale = 0.98f)) togetherWith
                                                (slideOutVertically(tween(Motion.Normal, easing = Motion.EasingExit)) { it / 10 } +
                                                    fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)) +
                                                    scaleOut(tween(Motion.Normal, easing = Motion.EasingExit), targetScale = 0.98f))
                                        }
                                    }
                                },
                                label = "dayModeContent"
                            ) { key ->
                                val mode = key.mode
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (mode == DayViewMode.TODAY && key.showHoliday) {
                                        // Full holiday: the timeline itself steps aside for the
                                        // holiday notice and the next teaching day.
                                        PremiumHolidayHeroCard(
                                            holidayNames = holidayNames,
                                            onViewTimetable = { scope.launch { container.settings.setHolidayTimetableOverrideDay(todayIso) } }
                                        )
                                        nextAfterHoliday?.let { next ->
                                            PremiumNextAfterHolidayCard(
                                                next = next,
                                                todayIso = todayIso
                                            )
                                        }
                                    } else {
                                        if (mode == DayViewMode.TODAY && todayFullHolidays.isNotEmpty()) {
                                            // Timetable shown by choice on a holiday: offer the way
                                            // back to the holiday notice (accidental-tap escape).
                                            PremiumHolidayOverrideStrip(
                                                onClose = { scope.launch { container.settings.setHolidayTimetableOverrideDay("") } }
                                            )
                                        }
                                        val modeLectures = when (mode) {
                                            DayViewMode.TODAY -> lectures.filter { it.dayOfWeek == todayDow }
                                            DayViewMode.TOMORROW -> lectures.filter { it.dayOfWeek == tomorrowDow }
                                            DayViewMode.WEEK -> emptyList()
                                        }.sortedBy { it.startMinutes }
                                        val modeTimeline = buildTimeline(modeLectures, if (mode == DayViewMode.TODAY) nowMinutes else -1)
                                        val modeFreeMinutes = modeTimeline.filterIsInstance<TimelineItem.Free>().sumOf { it.end - it.start }
                                        val modeFriends = if (mode == DayViewMode.TODAY) todayFriendDays else tomorrowFriendDays
                                        val myFirstStart = modeLectures.minOfOrNull { it.startMinutes }
                                        val earlyFriendSlots = if (myFirstStart != null) {
                                            modeFriends.earlySlotsBefore(myFirstStart)
                                        } else {
                                            emptyList()
                                        }
                                        // Mirror of the top note: friends still in class after
                                        // the student's last lecture of the day ends.
                                        val myLastEnd = modeLectures.maxOfOrNull { it.endMinutes }
                                        val lateFriendSlots = if (myLastEnd != null) {
                                            modeFriends.lateSlotsAfter(myLastEnd)
                                        } else {
                                            emptyList()
                                        }
                                        DaySummary(
                                            lectures = modeLectures.size,
                                            freeMinutes = modeFreeMinutes,
                                            reminders = if (mode == DayViewMode.TODAY) scheduledReminders else 0,
                                            accent = accent,
                                            primaryText = primaryText,
                                            muted = muted,
                                            cardColor = MaterialTheme.colorScheme.surface
                                        )
                                        if (earlyFriendSlots.isNotEmpty()) {
                                            FriendsBeforeDayNote(
                                                slots = earlyFriendSlots,
                                                accent = accent,
                                                muted = muted
                                            )
                                        }
                                        if (modeTimeline.isEmpty()) {
                                            EmptyDayCard(
                                                cardColor = MaterialTheme.colorScheme.surface,
                                                primaryText = primaryText,
                                                muted = muted,
                                                dayLabel = if (mode == DayViewMode.TODAY) "today" else "tomorrow"
                                            )
                                        } else {
                                            val shownDate = if (mode == DayViewMode.TODAY) time.toLocalDate() else time.toLocalDate().plusDays(1)
                                            TimelineSection(
                                                timeline = modeTimeline,
                                                nowMinutes = if (mode == DayViewMode.TODAY) nowMinutes else -1,
                                                accent = accent,
                                                railBackground = background,
                                                primaryText = primaryText,
                                                muted = muted,
                                                lectureDate = shownDate,
                                                friends = modeFriends,
                                                mentorName = settings.mentorName,
                                                mentorVenue = settings.mentorVenue,
                                                onOpenLecture = onOpenLecture
                                            )
                                        }
                                        if (lateFriendSlots.isNotEmpty()) {
                                            FriendsAfterDayNote(
                                                slots = lateFriendSlots,
                                                accent = accent,
                                                muted = muted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    DayViewActionCard(
                        mode = viewMode,
                        cardColor = MaterialTheme.colorScheme.secondaryContainer,
                        primaryText = primaryText,
                        muted = muted,
                        onSelect = { viewModeName = it.name }
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        // One compact status line (matches FacultyDetail source footer style).
                        // Prefer FET generation time when known; otherwise keep the offline cue.
                        val status = meta?.generatedAtMillis?.let { generatedAt ->
                            "Using saved timetable · generated ${Formatters.dateTime(generatedAt)}"
                        } ?: "Using saved timetable · reminders work offline"
                        Text(status, color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayModeSelector(mode: DayViewMode, onSelect: (DayViewMode) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        DayViewMode.values().forEach { item ->
            val active = item == mode
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "modeContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "modeBorder"
            )
            val textColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "modeText"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = { view.hapticTick(); onSelect(item) },
                modifier = Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Text(item.title, modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 11.dp), color = textColor, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DayViewActionCard(mode: DayViewMode, cardColor: Color, primaryText: Color, muted: Color, onSelect: (DayViewMode) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Explore more timetable views", color = primaryText, fontWeight = FontWeight.Bold)
            Text("Switch without leaving Today", color = muted, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.animateContentSize(motionTween(Motion.Normal)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (mode != DayViewMode.TODAY) TextButton(onClick = { onSelect(DayViewMode.TODAY) }) { Text("Today") }
                if (mode != DayViewMode.TOMORROW) TextButton(onClick = { onSelect(DayViewMode.TOMORROW) }) { Text("Tomorrow") }
                if (mode != DayViewMode.WEEK) TextButton(onClick = { onSelect(DayViewMode.WEEK) }) { Text("Full week") }
            }
        }
    }
}

@Composable
private fun WeekSummary(totalLectures: Int, weekStart: java.time.LocalDate, cardColor: Color, primaryText: Color, muted: Color, accent: Color) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("FULL WEEK", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("$totalLectures lectures", color = primaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Monday to Sunday", color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Colour key for the full-week rail marks (teal / blue / orange). */
@Composable
private fun WeekTypeLegend(muted: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WeekTypeLegendItem(color = lectureTypeColors("LECTURE").line, label = "Lecture", muted = muted)
        WeekTypeLegendItem(color = lectureTypeColors("PRACTICAL").line, label = "Practical", muted = muted)
        WeekTypeLegendItem(color = lectureTypeColors("TUTORIAL").line, label = "Tutorial", muted = muted)
    }
}

@Composable
private fun WeekTypeLegendItem(
    color: Color,
    label: String,
    muted: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .width(14.dp)
                .height(3.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            label,
            color = muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}


@Composable
private fun WeekDaySection(date: java.time.LocalDate, lectures: List<LectureEntity>, primaryText: Color, muted: Color, accent: Color, onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit, mentorName: String = "", mentorVenue: String = "") {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase), color = primaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(DateFormatter.format(date), color = muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("${lectures.size} lectures", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            if (lectures.isEmpty()) {
                Text("No lectures scheduled", color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                lectures.forEachIndexed { index, lecture ->
                    WeekLectureRow(lecture, date, primaryText, muted, accent, onOpenLecture, mentorName, mentorVenue)
                    if (index < lectures.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun WeekLectureRow(lecture: LectureEntity, date: java.time.LocalDate, primaryText: Color, muted: Color, accent: Color, onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit, mentorName: String = "", mentorVenue: String = "") {
    val pressInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .pressFeedback(pressInteraction, pressedScale = 0.98f)
            .clickable(interactionSource = pressInteraction, indication = LocalIndication.current) { onOpenLecture(lecture, date) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(72.dp)) {
            Text(formatTime(lecture.startMinutes), color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(formatTime(lecture.endMinutes), color = muted, style = MaterialTheme.typography.labelSmall)
        }
        // Divider carries the lecture's type tint, matching the Today tab cards.
        Box(Modifier.width(3.dp).height(34.dp).background(lectureTypeColors(lecture.lectureType).line))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(lecture.subject ?: "Lecture", color = primaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lecture.displayTeacher(mentorName, unavailable = "").ifBlank { lecture.resolvedVenue(mentorVenue) ?: "Details unavailable" }, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Resolves one friend against [dayOfWeek] from the timetable groups this device
 * has already parsed. Nothing is fetched: [com.gndec.timetable.data.db.LectureDao]
 * holds every group in the document, so a friend's week is a local read.
 */
@Composable
private fun rememberFriendDay(dao: com.gndec.timetable.data.db.LectureDao, friend: Friend, dayOfWeek: Int): FriendDay? {
    if (!friend.isUsable) return null
    val week by remember(friend.group) {
        dao.observeForGroup(friend.group).map { list ->
            list.filter { it.dayOfWeek == dayOfWeek } to list.isNotEmpty()
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList<LectureEntity>() to false)
    return remember(week, friend) {
        FriendDay(friend = friend, lectures = week.first, hasData = week.second)
    }
}

@Composable
private fun rememberFriendDays(container: AppContainer, friends: List<Friend>, dayOfWeek: Int): List<FriendDay> {
    val dao = container.db.lectureDao()
    val days = friends.map { friend -> rememberFriendDay(dao, friend, dayOfWeek) }
    return remember(days) { days.filterNotNull() }
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
private fun DaySummary(lectures: Int, freeMinutes: Int, reminders: Int, accent: Color, primaryText: Color, muted: Color, cardColor: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(lectures.toString(), "LECTURES", accent, primaryText, muted, cardColor, Modifier.weight(1f))
        MetricCard(formatDurationShort(freeMinutes), "FREE", primaryText, primaryText, muted, cardColor, Modifier.weight(1f))
        MetricCard(reminders.toString(), "SCHEDULED", primaryText, primaryText, muted, cardColor, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(value: String, label: String, valueColor: Color, primaryText: Color, muted: Color, cardColor: Color, modifier: Modifier) {
    val valueSwapIn = motionTween<Float>(Motion.Normal)
    val valueSwapOut = motionTween<Float>(Motion.Fast)
    Card(modifier.height(102.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(valueSwapIn) togetherWith fadeOut(valueSwapOut) },
                label = "metricValue"
            ) { current ->
                Text(current, color = valueColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = if (label == "LECTURES") primaryText else muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyDayCard(cardColor: Color, primaryText: Color, muted: Color, dayLabel: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("You’re free $dayLabel", color = primaryText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text("No lectures are scheduled for this day.", color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * The whole day rendered as one section so a single rail line runs behind every
 * item — lectures and free periods alike — without breaking across list gaps.
 * Each row reports its node coordinates; the spine and the glow progress stroke
 * are drawn here in one pass, reading state only in the draw phase so progress
 * changes never recompose.
 */
@Composable
private fun TimelineSection(
    timeline: List<TimelineItem>,
    nowMinutes: Int,
    accent: Color,
    railBackground: Color,
    primaryText: Color,
    muted: Color,
    lectureDate: java.time.LocalDate,
    friends: List<FriendDay>,
    mentorName: String,
    mentorVenue: String,
    onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit
) {
    var sectionCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // LayoutCoordinates alone can go stale when cards above expand (friend chips,
    // multi-line subjects). Cache section-relative centres and refresh them on
    // every node/section layout pass so the rail always reaches the last item.
    val nodeCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    val nodeYs = remember { mutableStateMapOf<Int, Float>() }
    val nodeHalfHs = remember { mutableStateMapOf<Int, Float>() }
    var layoutEpoch by remember { mutableStateOf(0) }

    fun refreshNode(index: Int, coords: LayoutCoordinates) {
        nodeCoords[index] = coords
        val section = sectionCoords
        if (section != null && section.isAttached && coords.isAttached) {
            nodeYs[index] = section.localPositionOf(
                coords,
                Offset(coords.size.width / 2f, coords.size.height / 2f)
            ).y
            nodeHalfHs[index] = coords.size.height / 2f
        }
        layoutEpoch++
    }

    fun refreshAllFromSection(section: LayoutCoordinates) {
        sectionCoords = section
        if (!section.isAttached) return
        nodeCoords.forEach { (index, coords) ->
            if (!coords.isAttached) return@forEach
            nodeYs[index] = section.localPositionOf(
                coords,
                Offset(coords.size.width / 2f, coords.size.height / 2f)
            ).y
            nodeHalfHs[index] = coords.size.height / 2f
        }
        layoutEpoch++
    }

    // Drop cached nodes when the timeline shape changes so indices never point
    // at a previous day's leftover coordinates.
    val timelineKey = timeline.joinToString("|") {
        when (it) {
            is TimelineItem.Lecture -> "L${it.lecture.startMinutes}-${it.lecture.endMinutes}-${it.lecture.subject}"
            is TimelineItem.Free -> "F${it.start}-${it.end}"
        }
    }
    androidx.compose.runtime.LaunchedEffect(timelineKey) {
        nodeCoords.clear()
        nodeYs.clear()
        nodeHalfHs.clear()
        layoutEpoch++
    }

    // When every lecture is done, drive the glow to the very last timeline node
    // (including a trailing free period) so the rail looks fully completed.
    val allDone = timeline.isNotEmpty() &&
        timeline.none { it is TimelineItem.Lecture && it.state == LectureState.UPCOMING }
    val activeIndex = if (allDone) {
        timeline.lastIndex
    } else {
        timeline.indexOfLast { it is TimelineItem.Lecture && it.state != LectureState.UPCOMING }
    }
    // Subscribe to layoutEpoch so glow recomputes after late position updates.
    // Keep the last known centre when a node has not reported yet so the
    // animation does not collapse to y=0 mid-layout.
    var lastGlowY by remember { mutableStateOf(0f) }
    layoutEpoch.let {
        nodeYs[activeIndex]?.let { lastGlowY = it }
    }
    val glowEnd by animateFloatAsState(
        targetValue = lastGlowY,
        animationSpec = motionTween(700),
        label = "railGlowEnd"
    )
    Box(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { refreshAllFromSection(it) }
            // Rail is drawn behind the nodes/labels so the timing text stays
            // readable; layoutEpoch forces a redraw when positions refresh.
            .drawBehind {
                val epoch = layoutEpoch
                if (epoch < 0) return@drawBehind
                fun nodeY(index: Int): Float? = nodeYs[index]
                fun nodeGap(index: Int): Float =
                    (nodeHalfHs[index] ?: (RailNodeSize.toPx() / 2f)) + 4.dp.toPx()
                val positioned = (0..timeline.lastIndex).mapNotNull { i -> nodeY(i)?.let { i to it } }
                if (positioned.size < 2) return@drawBehind
                val first = positioned.first().second
                val last = positioned.last().second
                if (last <= first) return@drawBehind
                val x = RailCenterX.toPx()
                val labelBand = 58.dp.toPx()
                for (index in 0 until timeline.lastIndex) {
                    val startY = nodeY(index) ?: continue
                    val endY = nodeY(index + 1) ?: continue
                    val segmentStart = startY + nodeGap(index)
                    val segmentEnd = endY - nodeGap(index + 1)
                    val labelStart = segmentStart
                    val labelEnd = labelStart + if (timeline[index] is TimelineItem.Lecture) labelBand else 0f
                    if (segmentEnd > segmentStart) {
                        if (labelStart > segmentStart) {
                            drawLine(
                                muted.copy(alpha = 0.18f),
                                Offset(x, segmentStart),
                                Offset(x, minOf(segmentEnd, labelStart)),
                                2.dp.toPx(),
                                StrokeCap.Round
                            )
                        }
                        if (segmentEnd > labelEnd) {
                            drawLine(
                                muted.copy(alpha = 0.18f),
                                Offset(x, maxOf(segmentStart, labelEnd)),
                                Offset(x, segmentEnd),
                                2.dp.toPx(),
                                StrokeCap.Round
                            )
                        }
                    }
                }
                val tip = when {
                    activeIndex < 0 -> first
                    allDone -> last
                    glowEnd > first -> glowEnd.coerceIn(first, last)
                    else -> first
                }
                if (tip > first + 2f) {
                    // When the day is fully complete, keep the tip solid so the
                    // rail looks finished instead of fading out at the last node.
                    val core = if (allDone) {
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.98f),
                            0.6f to accent.copy(alpha = 0.90f),
                            1f to accent.copy(alpha = 0.85f),
                            startY = first,
                            endY = tip
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.98f),
                            0.55f to accent.copy(alpha = 0.78f),
                            0.9f to accent.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            startY = first,
                            endY = tip
                        )
                    }
                    val halo = if (allDone) {
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.18f),
                            0.7f to accent.copy(alpha = 0.12f),
                            1f to accent.copy(alpha = 0.08f),
                            startY = first,
                            endY = tip
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.22f),
                            0.7f to accent.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            startY = first,
                            endY = tip
                        )
                    }
                    for (index in 0 until timeline.lastIndex) {
                        val startY = nodeY(index) ?: continue
                        val endY = nodeY(index + 1) ?: continue
                        val segmentStart = startY + nodeGap(index)
                        val segmentEnd = endY - nodeGap(index + 1)
                        val labelStart = segmentStart
                        val labelEnd = labelStart + if (timeline[index] is TimelineItem.Lecture) labelBand else 0f
                        val progressEnd = minOf(segmentEnd, tip)
                        if (progressEnd > segmentStart) {
                            val beforeEnd = minOf(progressEnd, labelStart)
                            if (beforeEnd > segmentStart) {
                                drawLine(halo, Offset(x, segmentStart), Offset(x, beforeEnd), 8.dp.toPx(), StrokeCap.Round)
                                drawLine(core, Offset(x, segmentStart), Offset(x, beforeEnd), 3.dp.toPx(), StrokeCap.Round)
                            }
                            val afterStart = maxOf(segmentStart, labelEnd)
                            if (progressEnd > afterStart) {
                                drawLine(halo, Offset(x, afterStart), Offset(x, progressEnd), 8.dp.toPx(), StrokeCap.Round)
                                drawLine(core, Offset(x, afterStart), Offset(x, progressEnd), 3.dp.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                }
                // Mask the timing-label band so the rail does not cut through text.
                for (index in 0 until timeline.lastIndex) {
                    val startY = nodeY(index) ?: continue
                    if (nodeY(index + 1) == null) continue
                    val labelStart = startY + nodeGap(index)
                    val labelEnd = labelStart + if (timeline[index] is TimelineItem.Lecture) labelBand else 0f
                    if (labelEnd > labelStart) {
                        drawRect(
                            color = railBackground,
                            topLeft = Offset(x - 50.dp.toPx(), labelStart - 4.dp.toPx()),
                            size = Size(100.dp.toPx(), labelEnd - labelStart + 8.dp.toPx())
                        )
                    }
                }
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TimelineItemGap)) {
            timeline.forEachIndexed { index, item ->
                when (item) {
                    is TimelineItem.Lecture -> TimelineLectureCard(
                        lecture = item.lecture,
                        state = item.state,
                        nowMinutes = nowMinutes,
                        nodeIndex = index,
                        sectionCoords = { sectionCoords },
                        registerNode = { i, coords -> refreshNode(i, coords) },
                        accent = accent,
                        primaryText = primaryText,
                        muted = muted,
                        freeFriends = friends.friendsFreeWithin(item.lecture.startMinutes, item.lecture.endMinutes),
                        onClick = { onOpenLecture(item.lecture, lectureDate) },
                        mentorName = mentorName,
                        mentorVenue = mentorVenue
                    )
                    is TimelineItem.Free -> FreePeriod(
                        start = item.start,
                        end = item.end,
                        nodeIndex = index,
                        sectionCoords = { sectionCoords },
                        registerNode = { i, coords -> refreshNode(i, coords) },
                        muted = muted,
                        accent = accent,
                        freeFriends = friends.friendsFreeWithin(item.start, item.end)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineLectureCard(lecture: LectureEntity, state: LectureState, nowMinutes: Int, nodeIndex: Int, sectionCoords: () -> LayoutCoordinates?, registerNode: (Int, LayoutCoordinates) -> Unit, accent: Color, primaryText: Color, muted: Color, freeFriends: List<FriendFreeWindow>, onClick: () -> Unit, mentorName: String = "", mentorVenue: String = "") {
    val live = state == LectureState.HAPPENING
    // Teal for lectures, amber for tutorials, blue for practicals; the live card is
    // the brighter step of whichever tint it is, not a fixed aqua.
    val typeColors = lectureTypeColors(lecture.lectureType)
    // State colors glide between UPCOMING / LIVE / COMPLETED instead of snapping.
    val cardColor by animateColorAsState(
        targetValue = if (live) typeColors.cardLive else typeColors.card,
        animationSpec = motionTween(Motion.Normal),
        label = "timelineCardColor"
    )
    val railColor by animateColorAsState(
        targetValue = if (live) accent else muted.copy(alpha = 0.65f),
        animationSpec = motionTween(Motion.Normal),
        label = "timelineRailColor"
    )
    val statusColor by animateColorAsState(
        targetValue = if (live) accent else muted,
        animationSpec = motionTween(Motion.Normal),
        label = "timelineStatusColor"
    )
    val remaining = (lecture.endMinutes - nowMinutes).coerceAtLeast(0)
    val stateSwapIn = motionTween<Float>(Motion.Fast)
    val stateSwapOut = motionTween<Float>(Motion.Fast)
    // The happening lecture lifts off the page; finished ones rest back, quieter.
    val cardElevation by animateDpAsState(
        targetValue = if (live) 6.dp else 0.dp,
        animationSpec = motionTween(Motion.Normal),
        label = "timelineElevation"
    )
    val contentAlpha = animateFloatAsState(
        targetValue = if (state == LectureState.COMPLETED) 0.72f else 1f,
        animationSpec = motionTween(Motion.Normal),
        label = "timelineContentAlpha"
    )
    val pressInteraction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = TimelineHorizontalPadding), verticalAlignment = Alignment.Top) {
        TimelineRail(lecture, state, nodeIndex, sectionCoords, registerNode, railColor, primaryText, muted)
        Spacer(Modifier.width(TimelineCardGap))
        Card(
            onClick = onClick,
            modifier = Modifier.weight(1f).pressFeedback(pressInteraction),
            interactionSource = pressInteraction,
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
        ) {
            Column(Modifier.heightIn(min = RailMinCardHeight).graphicsLayer { alpha = contentAlpha.value }.padding(horizontal = 18.dp, vertical = 17.dp)) {
                // Status labels must always read in full ("COMPLETED", "UPCOMING").
                // AnimatedContent + weight(1f) + Ellipsis was measuring too narrowly and
                // ellipsized to "COMPL…" even on normal-width phones. Status takes its
                // natural width; a Spacer fills the middle; time stays on the right.
                // Live remaining stays on a second line so it never collides with LIVE NOW.
                if (live) {
                    Column(Modifier.fillMaxWidth()) {
                        AnimatedContent(
                            targetState = state,
                            transitionSpec = { fadeIn(stateSwapIn) togetherWith fadeOut(stateSwapOut) },
                            label = "timelineStatusLabel"
                        ) { current ->
                            Text(
                                when (current) {
                                    LectureState.COMPLETED -> "COMPLETED"
                                    LectureState.HAPPENING -> "LIVE NOW"
                                    LectureState.UPCOMING -> "UPCOMING"
                                },
                                color = statusColor,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatDurationUpper(remaining)} REMAINING",
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedContent(
                            targetState = state,
                            transitionSpec = { fadeIn(stateSwapIn) togetherWith fadeOut(stateSwapOut) },
                            label = "timelineStatusLabel"
                        ) { current ->
                            Text(
                                when (current) {
                                    LectureState.COMPLETED -> "COMPLETED"
                                    LectureState.HAPPENING -> "LIVE NOW"
                                    LectureState.UPCOMING -> "UPCOMING"
                                },
                                color = statusColor,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state == LectureState.UPCOMING) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Reminder",
                                tint = muted,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            formatTime(lecture.startMinutes),
                            color = primaryText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Spacer(Modifier.height(15.dp))
                Text((lecture.subject ?: "Lecture").uppercase(), color = primaryText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(13.dp))
                TimelineMetaRow(Icons.Default.Person, lecture.displayTeacher(mentorName, unavailable = "Teacher unavailable").uppercase(), primaryText, muted, maxLines = 1)
                lecture.resolvedVenue(mentorVenue)?.let { Spacer(Modifier.height(8.dp)); TimelineMetaRow(Icons.Default.LocationOn, it, primaryText, muted) }
                if (freeFriends.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FriendAvailabilityNote(
                        windows = freeFriends,
                        startMinutes = lecture.startMinutes,
                        endMinutes = lecture.endMinutes
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRail(lecture: LectureEntity, state: LectureState, nodeIndex: Int, sectionCoords: () -> LayoutCoordinates?, registerNode: (Int, LayoutCoordinates) -> Unit, railColor: Color, primaryText: Color, muted: Color) {
    val iconSwapIn = motionTween<Float>(Motion.Fast)
    val iconSwapOut = motionTween<Float>(Motion.Fast)
    val live = state == LectureState.HAPPENING
    val timeColor by animateColorAsState(
        targetValue = if (live) railColor else primaryText,
        animationSpec = motionTween(Motion.Normal),
        label = "railTimeColor"
    )
    // The original premium geometry is preserved: node first, timing labels
    // underneath. The rail stretches to exactly the card height so no row is
    // padded with dead space, and the rail renderer masks the label band
    // between both nodes.
    Column(Modifier.width(RailColumnWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.size(RailNodeSize).onGloballyPositioned { coords -> registerNode(nodeIndex, coords) },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(RailNodeSize).background(railColor.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn(iconSwapIn) togetherWith fadeOut(iconSwapOut) },
                    label = "railIcon"
                ) { current ->
                    Icon(
                        when (current) { LectureState.COMPLETED -> Icons.Default.CheckCircle; LectureState.HAPPENING -> Icons.Default.Notifications; LectureState.UPCOMING -> Icons.Default.AccessTime },
                        contentDescription = null,
                        tint = railColor,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(formatTime(lecture.startMinutes), color = timeColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(formatTime(lecture.endMinutes), color = muted, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Compact note above the student's timeline when friends already have a class
 * before the student's first lecture of the day.
 *
 * Examples:
 * - "Vishal has a lecture at 8:30 AM"
 * - "Vishal and Prince have a lecture at 8:30 AM"
 * - "Vishal has a lecture and Prince has a lab at 8:30 AM"
 * Names and activity types are bold.
 */
@Composable
private fun FriendsBeforeDayNote(slots: List<FriendEarlySlot>, accent: Color, muted: Color) {
    if (slots.isEmpty()) return
    // Plain text only — same weight as the "Also free · …" line under free periods,
    // no chip/block so it stays light above the rail.
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = accent)
    val body = SpanStyle(fontWeight = FontWeight.Medium, color = muted)
    val text = buildAnnotatedString {
        withStyle(body) {
            appendFriendsBeforeDay(slots, bold, body)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TimelineHorizontalPadding)
    )
}

/**
 * Builds the spoken clause(s) for [slots], applying [bold] to names and type labels.
 * Groups by start time so mixed types at the same clock can share one "at …".
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendFriendsBeforeDay(
    slots: List<FriendEarlySlot>,
    bold: SpanStyle,
    body: SpanStyle
) {
    val byTime = slots.groupBy { it.startMinutes }.toSortedMap()
    byTime.entries.forEachIndexed { timeIndex, (startMinutes, group) ->
        if (timeIndex > 0) {
            withStyle(body) { append(" · ") }
        }
        val types = group.map { it.typeLabel }.distinct()
        if (types.size == 1) {
            // Same type: "Vishal and Prince have a lecture at 8:30 AM"
            group.forEachIndexed { i, slot ->
                if (i > 0) {
                    withStyle(body) {
                        append(if (i == group.lastIndex) " and " else ", ")
                    }
                }
                withStyle(bold) { append(slot.name) }
            }
            withStyle(body) {
                append(if (group.size == 1) " has a " else " have a ")
            }
            withStyle(bold) { append(types.first()) }
            withStyle(body) {
                append(" at ")
                append(formatTime(startMinutes))
            }
        } else {
            // Mixed types at the same time: "Vishal has a lecture and Prince has a lab at 8:30 AM"
            group.forEachIndexed { i, slot ->
                if (i > 0) withStyle(body) { append(" and ") }
                withStyle(bold) { append(slot.name) }
                withStyle(body) { append(" has a ") }
                withStyle(bold) { append(slot.typeLabel) }
            }
            withStyle(body) {
                append(" at ")
                append(formatTime(startMinutes))
            }
        }
    }
}

/**
 * Compact note BELOW the student's timeline when friends are still in class
 * after the student's last lecture of the day ends — the bottom mirror of
 * [FriendsBeforeDayNote].
 *
 * Examples:
 * - "Vishal has a lecture until 5:30 PM"
 * - "Vishal and Prince have a lecture until 5:30 PM"
 * - "Vishal has a lecture and Prince has a lab until 5:30 PM"
 * Names and activity types are bold; "until" carries the class END time
 * because the student's day is already over at that point.
 */
@Composable
private fun FriendsAfterDayNote(slots: List<FriendLateSlot>, accent: Color, muted: Color) {
    if (slots.isEmpty()) return
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = accent)
    val body = SpanStyle(fontWeight = FontWeight.Medium, color = muted)
    val text = buildAnnotatedString {
        withStyle(body) {
            appendFriendsAfterDay(slots, bold, body)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TimelineHorizontalPadding)
    )
}

/**
 * Builds the spoken clause(s) for [slots], applying [bold] to names and type
 * labels. Groups by END time so friends finishing together share one
 * "until …".
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendFriendsAfterDay(
    slots: List<FriendLateSlot>,
    bold: SpanStyle,
    body: SpanStyle
) {
    val byEnd = slots.groupBy { it.endMinutes }.toSortedMap()
    byEnd.entries.forEachIndexed { timeIndex, (endMinutes, group) ->
        if (timeIndex > 0) {
            withStyle(body) { append(" · ") }
        }
        val types = group.map { it.typeLabel }.distinct()
        if (types.size == 1) {
            // Same type: "Vishal and Prince have a lecture until 5:30 PM"
            group.forEachIndexed { i, slot ->
                if (i > 0) {
                    withStyle(body) {
                        append(if (i == group.lastIndex) " and " else ", ")
                    }
                }
                withStyle(bold) { append(slot.name) }
            }
            withStyle(body) {
                append(if (group.size == 1) " has a " else " have a ")
            }
            withStyle(bold) { append(types.first()) }
            withStyle(body) {
                append(" until ")
                append(formatTime(endMinutes))
            }
        } else {
            // Mixed types at the same end: "Vishal has a lecture and Prince has a lab until 5:30 PM"
            group.forEachIndexed { i, slot ->
                if (i > 0) withStyle(body) { append(" and ") }
                withStyle(bold) { append(slot.name) }
                withStyle(body) { append(" has a ") }
                withStyle(bold) { append(slot.typeLabel) }
            }
            withStyle(body) {
                append(" until ")
                append(formatTime(endMinutes))
            }
        }
    }
}

/**
 * "Aarav and Meera are free at this time slot", shown under the lecture card
 * when a friend happens to be free for the whole class — no time range, the
 * card above already carries the slot. Friends free for only part of it do
 * show their actual stretch ("Raman and Prince are free at
 * 12:30 PM - 1:30 PM"); the clauses come from [friendAvailabilitySentences].
 */
@Composable
private fun FriendAvailabilityNote(windows: List<FriendFreeWindow>, startMinutes: Int, endMinutes: Int) {
    val sentences = friendAvailabilitySentences(windows, startMinutes, endMinutes, ::formatTimeCompact)
    if (sentences.isEmpty()) return
    // Plain text, matching the other friend notes ("Also free · …"): a boxed row
    // under every lecture card read as clutter, so this is just a quiet line.
    Text(
        sentences.joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    )
}

@Composable
private fun TimelineMetaRow(icon: ImageVector, text: String, primaryText: Color, muted: Color, maxLines: Int = 2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = primaryText.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1)
    }
}

@Composable
private fun FreePeriod(start: Int, end: Int, nodeIndex: Int, sectionCoords: () -> LayoutCoordinates?, registerNode: (Int, LayoutCoordinates) -> Unit, muted: Color, accent: Color, freeFriends: List<FriendFreeWindow>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = TimelineHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(RailColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(14.dp).onGloballyPositioned { coords -> registerNode(nodeIndex, coords) }
                    .background(accent.copy(alpha = 0.45f), CircleShape)
            )
        }
        Spacer(Modifier.width(TimelineCardGap))
        Column(Modifier.weight(1f)) {
            Text(
                "${formatDurationUpper(end - start)} FREE PERIOD",
                color = muted,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 1.7.sp,
                fontWeight = FontWeight.Medium
            )
            if (freeFriends.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Also free · ${friendFreeLine(freeFriends, start, end)}",
                    color = accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Names for the "Also free · …" line under a free period. Friends free the
 * whole period stay plain; friends free for only part of it now show their
 * actual stretch — "Aarav (until 1:30 PM)", "Meera (from 1:30 PM)" — so a
 * partially-matching friend is listed instead of silently dropped.
 */
private fun friendFreeLine(windows: List<FriendFreeWindow>, periodStart: Int, periodEnd: Int): String =
    windows.joinToString(", ") { window ->
        when {
            window.startMinutes <= periodStart && window.endMinutes >= periodEnd -> window.name
            window.startMinutes <= periodStart -> "${window.name} (until ${formatTimeCompact(window.endMinutes)})"
            window.endMinutes >= periodEnd -> "${window.name} (from ${formatTimeCompact(window.startMinutes)})"
            else -> "${window.name} (${formatTimeCompact(window.startMinutes)} - ${formatTimeCompact(window.endMinutes)})"
        }
    }

private fun formatTime(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val suffix = if (hour >= 12) "PM" else "AM"
    val displayHour = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

/** Compact 12-hour time for inline friend availability notes. */
private fun formatTimeCompact(minutes: Int): String {
    val hour = (minutes / 60) % 24
    val minute = minutes % 60
    val displayHour = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d".format(displayHour, minute)
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

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionSpring
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
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
    val reduced = LocalReducedMotion.current
    val time = Instant.ofEpochMilli(nowMillis).atZone(Zone)
    val nowMinutes = time.hour * 60 + time.minute
    val todayDow = time.dayOfWeek.value
    val tomorrowDow = if (todayDow == 7) 1 else todayDow + 1
    val background = MaterialTheme.colorScheme.background
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryText = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
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
                                (0L..6L).map { selectedDate.plusDays(it) }.forEach { date ->
                                    WeekDaySection(
                                        date = date,
                                        lectures = lectures.filter { it.dayOfWeek == date.dayOfWeek.value }.sortedBy { it.startMinutes },
                                        primaryText = primaryText,
                                        muted = muted,
                                        accent = accent,
                                        onOpenLecture = onOpenLecture
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val modeLectures = when (viewMode) {
                                    DayViewMode.TODAY -> lectures.filter { it.dayOfWeek == todayDow }
                                    DayViewMode.TOMORROW -> lectures.filter { it.dayOfWeek == tomorrowDow }
                                    DayViewMode.WEEK -> emptyList()
                                }.sortedBy { it.startMinutes }
                                val modeTimeline = buildTimeline(modeLectures, if (viewMode == DayViewMode.TODAY) nowMinutes else -1)
                                val modeFreeMinutes = modeTimeline.filterIsInstance<TimelineItem.Free>().sumOf { it.end - it.start }
                                DaySummary(
                                    lectures = modeLectures.size,
                                    freeMinutes = modeFreeMinutes,
                                    reminders = if (viewMode == DayViewMode.TODAY) scheduledReminders else 0,
                                    accent = accent,
                                    primaryText = primaryText,
                                    muted = muted,
                                    cardColor = MaterialTheme.colorScheme.surface
                                )
                                AnimatedContent(
                                    targetState = viewMode,
                                    transitionSpec = {
                                        if (reduced) {
                                            fadeIn(tween(Motion.Normal, easing = Motion.EasingStandard)) togetherWith
                                                fadeOut(tween(Motion.Fast, easing = Motion.EasingStandard))
                                        } else {
                                            // Segmented-control motion: content slides toward the
                                            // direction of the newly selected tab.
                                            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                            (slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 12 } +
                                                fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                                (slideOutHorizontally(tween(Motion.Normal, easing = Motion.EasingExit)) { -dir * it / 14 } +
                                                    fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)))
                                        }
                                    },
                                    label = "dayTimetableFade"
                                ) { mode ->
                                    val animatedLectures = when (mode) {
                                        DayViewMode.TODAY -> lectures.filter { it.dayOfWeek == todayDow }
                                        DayViewMode.TOMORROW -> lectures.filter { it.dayOfWeek == tomorrowDow }
                                        DayViewMode.WEEK -> emptyList()
                                    }.sortedBy { it.startMinutes }
                                    val animatedTimeline = buildTimeline(animatedLectures, if (mode == DayViewMode.TODAY) nowMinutes else -1)
                                    if (animatedTimeline.isEmpty()) {
                                        EmptyDayCard(
                                            cardColor = MaterialTheme.colorScheme.surface,
                                            primaryText = primaryText,
                                            muted = muted,
                                            dayLabel = if (mode == DayViewMode.TODAY) "today" else "tomorrow"
                                        )
                                    } else {
                                        TimelineSection(
                                            timeline = animatedTimeline,
                                            nowMinutes = if (mode == DayViewMode.TODAY) nowMinutes else -1,
                                            accent = accent,
                                            railBackground = background,
                                            primaryText = primaryText,
                                            muted = muted,
                                            lectureDate = if (mode == DayViewMode.TODAY) time.toLocalDate() else time.toLocalDate().plusDays(1),
                                            onOpenLecture = onOpenLecture
                                        )
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
                        Text("Using saved timetable · reminders work offline", color = muted, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun WeekDaySection(date: java.time.LocalDate, lectures: List<LectureEntity>, primaryText: Color, muted: Color, accent: Color, onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit) {
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
                    WeekLectureRow(lecture, date, primaryText, muted, accent, onOpenLecture)
                    if (index < lectures.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun WeekLectureRow(lecture: LectureEntity, date: java.time.LocalDate, primaryText: Color, muted: Color, accent: Color, onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit) {
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
        Box(Modifier.width(3.dp).height(34.dp).background(accent.copy(alpha = 0.7f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(lecture.subject ?: "Lecture", color = primaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lecture.teacher?.takeIf { it.isNotBlank() } ?: (lecture.venue ?: "Details unavailable"), color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onOpenLecture: (LectureEntity, java.time.LocalDate) -> Unit
) {
    var sectionCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val nodeCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    val activeIndex = timeline.indexOfLast { it is TimelineItem.Lecture && it.state != LectureState.UPCOMING }
    val glowTarget = run {
        val section = sectionCoords
        val node = nodeCoords[activeIndex]
        if (section == null || node == null || !section.isAttached || !node.isAttached) 0f
        else section.localPositionOf(node, Offset(node.size.width / 2f, node.size.height / 2f)).y
    }
    val glowEnd by animateFloatAsState(
        targetValue = glowTarget,
        animationSpec = motionTween(700),
        label = "railGlowEnd"
    )
    Box(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { sectionCoords = it }
            .drawBehind {
                val section = sectionCoords ?: return@drawBehind
                if (!section.isAttached) return@drawBehind
                fun nodeY(index: Int): Float? {
                    val coords = nodeCoords[index] ?: return null
                    if (!coords.isAttached) return null
                    return section.localPositionOf(coords, Offset(coords.size.width / 2f, coords.size.height / 2f)).y
                }
                val first = nodeY(0)
                val last = nodeY(timeline.lastIndex)
                if (first == null || last == null || last <= first) return@drawBehind
                val x = RailCenterX.toPx()
                fun nodeGap(index: Int): Float {
                    val coords = nodeCoords[index]
                    return (coords?.size?.height?.toFloat()?.div(2f) ?: RailNodeSize.toPx() / 2f) + 4.dp.toPx()
                }
                val labelBand = 58.dp.toPx()
                for (index in 0 until timeline.lastIndex) {
                    val startY = nodeY(index) ?: continue
                    val endY = nodeY(index + 1) ?: continue
                    val segmentStart = startY + nodeGap(index)
                    val segmentEnd = endY - nodeGap(index + 1)
                    val labelStart = segmentStart
                    val labelEnd = labelStart + if (timeline[index] is TimelineItem.Lecture) labelBand else 0f
                    if (segmentEnd > segmentStart) {
                        if (labelStart > segmentStart) drawLine(muted.copy(alpha = 0.18f), Offset(x, segmentStart), Offset(x, minOf(segmentEnd, labelStart)), 2.dp.toPx(), StrokeCap.Round)
                        if (segmentEnd > labelEnd) drawLine(muted.copy(alpha = 0.18f), Offset(x, maxOf(segmentStart, labelEnd)), Offset(x, segmentEnd), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
                val tip = glowEnd.coerceIn(first, last)
                if (tip > first + 2f) {
                    val core = Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.98f),
                        0.55f to accent.copy(alpha = 0.78f),
                        0.9f to accent.copy(alpha = 0.45f),
                        1f to Color.Transparent,
                        startY = first,
                        endY = tip
                    )
                    val halo = Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.18f),
                        0.7f to accent.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                        startY = first,
                        endY = tip
                    )
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
                // Draw the masks last so neither the base rail nor the gradient
                // halo can bleed into the timing-label band.
                for (index in 0 until timeline.lastIndex) {
                    val startY = nodeY(index) ?: continue
                    val endY = nodeY(index + 1) ?: continue
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
                        registerNode = { i, coords -> nodeCoords[i] = coords },
                        accent = accent,
                        primaryText = primaryText,
                        muted = muted,
                        onClick = { onOpenLecture(item.lecture, lectureDate) }
                    )
                    is TimelineItem.Free -> FreePeriod(
                        start = item.start,
                        end = item.end,
                        nodeIndex = index,
                        sectionCoords = { sectionCoords },
                        registerNode = { i, coords -> nodeCoords[i] = coords },
                        muted = muted,
                        accent = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineLectureCard(lecture: LectureEntity, state: LectureState, nowMinutes: Int, nodeIndex: Int, sectionCoords: () -> LayoutCoordinates?, registerNode: (Int, LayoutCoordinates) -> Unit, accent: Color, primaryText: Color, muted: Color, onClick: () -> Unit) {
    val live = state == LectureState.HAPPENING
    // State colors glide between UPCOMING / LIVE / COMPLETED instead of snapping.
    val cardColor by animateColorAsState(
        targetValue = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = { fadeIn(stateSwapIn) togetherWith fadeOut(stateSwapOut) },
                        label = "timelineStatusLabel"
                    ) { current ->
                        Text(
                            when (current) { LectureState.COMPLETED -> "COMPLETED"; LectureState.HAPPENING -> "LIVE NOW"; LectureState.UPCOMING -> "UPCOMING" },
                            color = statusColor,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.7.sp
                        )
                    }
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
                TimelineMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() }?.uppercase() ?: "TEACHER UNAVAILABLE", primaryText, muted, maxLines = 1)
                lecture.venue?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); TimelineMetaRow(Icons.Default.LocationOn, it, primaryText, muted) }
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

@Composable
private fun TimelineMetaRow(icon: ImageVector, text: String, primaryText: Color, muted: Color, maxLines: Int = 2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = primaryText.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1)
    }
}

@Composable
private fun FreePeriod(start: Int, end: Int, nodeIndex: Int, sectionCoords: () -> LayoutCoordinates?, registerNode: (Int, LayoutCoordinates) -> Unit, muted: Color, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = TimelineHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(RailColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(14.dp).onGloballyPositioned { coords -> registerNode(nodeIndex, coords) }
                    .background(accent.copy(alpha = 0.45f), CircleShape)
            )
        }
        Spacer(Modifier.width(TimelineCardGap))
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

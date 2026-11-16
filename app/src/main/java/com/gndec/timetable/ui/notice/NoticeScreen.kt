package com.gndec.timetable.ui.notice

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.DeptNoticesState
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.parse.DeptNotice
import com.gndec.timetable.parse.DeptSite
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class NoticeTab(val label: String) { NOTICES("Notices"), HOLIDAYS("Holidays") }

/** Which notice source the Notices tab is showing. */
private enum class NoticeSource(val label: String) {
    ALL("All"),
    HOMEPAGE("GNDEC Home Page"),
    ERP("ERP"),
    /** The student's own department; the label comes from the resolved site. */
    DEPARTMENT("")
}

private fun NoticeSource.labelFor(dept: DeptSite?): String =
    if (this == NoticeSource.DEPARTMENT) dept?.chipLabel.orEmpty() else label

/** Feed membership test for a source chip (feed source strings are case-varying). */
private fun matchesNoticeSource(notice: ErpNotice, source: NoticeSource): Boolean = when (source) {
    NoticeSource.ALL -> true
    NoticeSource.HOMEPAGE -> notice.source.lowercase().contains("homepage")
    NoticeSource.ERP -> notice.source.lowercase().contains("erp")
    NoticeSource.DEPARTMENT -> true
}

private val DeptNoticeDate = DateTimeFormatter.ofPattern("d MMMM yyyy")

private fun formatDeptNoticeDate(date: String): String? =
    if (date.isBlank()) null else runCatching { LocalDate.parse(date).format(DeptNoticeDate) }.getOrNull()

private val HolidayLongDate = DateTimeFormatter.ofPattern("d MMMM yyyy (EEEE)")
private val HolidayMonthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * A holiday resolved against today, so the row knows at a glance whether it is
 * still ahead of us. [date] is null when the feed's `date` is not ISO `yyyy-MM-dd`
 * — such a row is never counted as past and never gets a countdown, but it is
 * still listed rather than dropped.
 */
private data class HolidayRow(
    val holiday: Holiday,
    val date: LocalDate?,
    val daysUntil: Long?
) {
    val isPast: Boolean get() = daysUntil != null && daysUntil < 0L
    val month: YearMonth? get() = date?.let(YearMonth::from)
}

/**
 * The three categories the official feed uses. Matching is substring-based on
 * purpose: the feed has shipped "Public holiday", "Restricted holiday" and
 * "Half-day holiday", but `holidayHomeBannerItems` in PremiumUi already leans on
 * `contains` for the same reason. Half-day is tested first because it is the more
 * specific of the two — a "Half-day holiday" is not a restricted holiday.
 */
private enum class HolidayKind(val label: String) {
    PUBLIC("Public holiday"),
    RESTRICTED("Restricted holiday"),
    HALF_DAY("Half-day holiday"),
    OTHER("")
}

private fun holidayKind(category: String): HolidayKind = when {
    category.contains("half", ignoreCase = true) -> HolidayKind.HALF_DAY
    category.contains("restricted", ignoreCase = true) -> HolidayKind.RESTRICTED
    category.contains("public", ignoreCase = true) -> HolidayKind.PUBLIC
    else -> HolidayKind.OTHER
}

private fun buildHolidayRows(holidays: List<Holiday>, today: LocalDate): List<HolidayRow> =
    holidays
        .map { holiday ->
            val date = runCatching { LocalDate.parse(holiday.date) }.getOrNull()
            HolidayRow(holiday, date, date?.let { ChronoUnit.DAYS.between(today, it) })
        }
        .sortedWith(
            // Unparseable dates sort last so a malformed feed row never leads the list.
            compareBy<HolidayRow>({ it.date == null }, { it.date ?: LocalDate.MAX })
        )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNoticeDetail: (ErpNotice) -> Unit,
    /** Which segment to open with — home holiday banners use HOLIDAYS. */
    initialTab: NoticeTab = NoticeTab.NOTICES
) {
    val notices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val refreshing by container.erpNoticeManager.refreshing.collectAsStateWithLifecycle()
    val lastError by container.erpNoticeManager.lastError.collectAsStateWithLifecycle()
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    val holidaysRefreshing by container.holidayManager.refreshing.collectAsStateWithLifecycle()
    val dept by container.deptNoticesManager.dept.collectAsStateWithLifecycle()
    val deptNotices by container.deptNoticesManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The department whose notices belong on this student's Notice tab is
    // re-resolved here, so a saved branch or year change is picked up on
    // every re-entry into the screen.
    LaunchedEffect(Unit) {
        runCatching { container.deptNoticesManager.ensureLoaded() }
    }
    // Keyed on initialTab so home → Holidays does not restore a prior Notices selection.
    var tabName by rememberSaveable(initialTab.name) { mutableStateOf(initialTab.name) }
    val tab = runCatching { NoticeTab.valueOf(tabName) }.getOrDefault(initialTab)
    val reduced = LocalReducedMotion.current

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // Pull-to-refresh replaces the official-feed header card and its refresh
    // button: one gesture re-fetches whatever the open tab shows — the ERP +
    // homepage feed and the department board together on Notices, the official
    // holiday list on Holidays. The indicator tracks only refreshes the user
    // starts from this screen: the stale-cache background refresh that runs on
    // entry stays indicator-less, exactly like the Home screen's pull.
    val pullState = rememberPullToRefreshState()
    var manualRefreshing by remember { mutableStateOf(false) }

    fun refreshVisibleTab() {
        manualRefreshing = true
        container.appScope.launch {
            try {
                when (tab) {
                    NoticeTab.NOTICES -> coroutineScope {
                        launch { runCatching { container.erpNoticeManager.refresh(forceRefresh = true) } }
                        launch { runCatching { container.deptNoticesManager.refresh(force = true) } }
                    }
                    NoticeTab.HOLIDAYS -> runCatching { container.holidayManager.refresh(forceRefresh = true) }
                }
            } finally {
                manualRefreshing = false
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            PullToRefreshBox(
                isRefreshing = manualRefreshing,
                onRefresh = { refreshVisibleTab() },
                state = pullState,
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp + PremiumBottomBarContentClearance),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        PremiumPageHeader(
                            title = "Notice",
                            subtitle = "Latest GNDEC official updates",
                            onSettings = onOpenSettings
                        )
                    }
                    item {
                        NoticeTabSelector(tab) { tabName = it.name }
                    }
                    item(key = "notice-tab-content") {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                if (reduced) {
                                    fadeIn(tween(Motion.Normal, easing = Motion.EasingStandard)) togetherWith
                                        fadeOut(tween(Motion.Fast, easing = Motion.EasingStandard))
                                } else {
                                    // Segmented-control motion — same as the Today screen's
                                    // today/tomorrow switch: content slides toward the
                                    // direction of the newly selected tab.
                                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                    (slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 12 } +
                                        fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                        (slideOutHorizontally(tween(Motion.Normal, easing = Motion.EasingExit)) { -dir * it / 14 } +
                                            fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)))
                                }
                            },
                            label = "noticeTabContent"
                        ) { current ->
                            when (current) {
                                NoticeTab.NOTICES -> NoticesTab(
                                    notices = notices,
                                    refreshing = refreshing,
                                    lastError = lastError,
                                    dept = dept,
                                    deptState = deptNotices,
                                    onRetry = { refreshVisibleTab() },
                                    onOpenNotice = onOpenNoticeDetail,
                                    onViewAll = { open(ErpNoticeManager.NOTICE_URL) },
                                    onOpenUrl = { open(it) }
                                )
                                NoticeTab.HOLIDAYS -> HolidaysTab(
                                    holidays = holidays,
                                    refreshing = holidaysRefreshing,
                                    onOpen = { open(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Notices tab: the default ERP + homepage feed exactly as before, plus the
 * student's own department section; the source pills narrow the list to one
 * feed when the student only wants one. Refreshes arrive through the
 * screen's pull gesture; [onRetry] only retries a failed department fetch.
 */
@Composable
private fun NoticesTab(
    notices: List<ErpNotice>,
    refreshing: Boolean,
    lastError: String?,
    dept: DeptSite?,
    deptState: DeptNoticesState,
    onRetry: () -> Unit,
    onOpenNotice: (ErpNotice) -> Unit,
    onViewAll: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    // Keyed on the resolved department so a branch switch re-defaults to All.
    var sourceName by rememberSaveable(dept?.key) { mutableStateOf(NoticeSource.ALL.name) }
    var source = runCatching { NoticeSource.valueOf(sourceName) }.getOrDefault(NoticeSource.ALL)
    if (source == NoticeSource.DEPARTMENT && dept == null) source = NoticeSource.ALL
    val visible = notices.filter { matchesNoticeSource(it, source) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (dept != null) {
            NoticeSourceSelector(source = source, dept = dept, onSelect = { sourceName = it.name })
        }
        if (source != NoticeSource.DEPARTMENT) {
            when {
                notices.isEmpty() -> OfficialFeedPhaseCard(refreshing = refreshing, lastError = lastError)
                visible.isEmpty() -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Text(
                        "No notices from this source right now — try another one.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> visible.forEach { notice ->
                    NoticeCard(notice = notice, onClick = { onOpenNotice(notice) })
                }
            }
            // Default view keeps the official feed *along with* the department.
            if (source == NoticeSource.ALL && dept != null) {
                DeptNoticesSection(
                    dept = dept,
                    state = deptState,
                    onRetry = onRetry,
                    onOpen = onOpenUrl
                )
            }
            TealOutlineButton(
                text = "View all previous notices on GNDEC official website",
                icon = Icons.Default.OpenInNew,
                modifier = Modifier.fillMaxWidth(),
                onClick = onViewAll
            )
        } else if (dept != null) {
            // Department-only view: the full board replaces the official feed.
            DeptNoticesSection(
                dept = dept,
                state = deptState,
                onRetry = onRetry,
                onOpen = onOpenUrl,
                standalone = true
            )
        }
    }
}

/** Loading / empty / failed phase of the official feed, exactly as before the split. */
@Composable
private fun OfficialFeedPhaseCard(refreshing: Boolean, lastError: String?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        val phase = if (refreshing) "__loading" else (lastError ?: "__empty")
        val phaseSwapIn = motionTween<Float>(Motion.Normal)
        val phaseSwapOut = motionTween<Float>(Motion.Fast)
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(phaseSwapIn) togetherWith fadeOut(phaseSwapOut) },
            label = "noticePhase"
        ) { current ->
            Text(
                when {
                    current == "__loading" -> "Loading official notices…"
                    current == "__empty" -> "No cached notices available yet."
                    else -> current
                },
                modifier = Modifier.padding(18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Holidays tab: the next holiday up front, then the full official list by month. */
@Composable
private fun HolidaysTab(holidays: List<Holiday>, refreshing: Boolean, onOpen: (String) -> Unit) {
    val today = remember(holidays) { LocalDate.now() }
    val rows = remember(holidays, today) { buildHolidayRows(holidays, today) }
    val next = remember(rows) { rows.firstOrNull { it.daysUntil != null && it.daysUntil >= 0L } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NextHolidayCard(next = next, hasAny = rows.isNotEmpty(), refreshing = refreshing)
        HolidaysPanel(
            rows = rows,
            nextId = next?.holiday?.id,
            today = today,
            refreshing = refreshing,
            onOpen = onOpen
        )
    }
}

@Composable
private fun NextHolidayCard(next: HolidayRow?, hasAny: Boolean, refreshing: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val days = next?.daysUntil
                Text(
                    when {
                        next != null && days == 0L -> "HOLIDAY · TODAY"
                        next != null -> "NEXT HOLIDAY"
                        hasAny -> "NO UPCOMING HOLIDAYS"
                        refreshing -> "LOADING HOLIDAYS"
                        else -> "HOLIDAYS"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    next?.holiday?.name ?: if (hasAny) "That’s everything for now" else "Official list unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (next != null && days != null) {
                    Spacer(Modifier.height(3.dp))
                    if (days == 0L) {
                        Text(
                            "Enjoy the break",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // "in 5 days" with the count picked out in the accent colour.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("in ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "$days",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (days == 1L) " day" else " days",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val date = next.date
                    if (date != null) {
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                date.format(HolidayLongDate),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            HolidayGlyph()
        }
    }
}

/** Decorative calendar mark standing in for the artwork in the design reference. */
@Composable
private fun HolidayGlyph() {
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape))
        listOf(
            Alignment.TopStart to 6.dp,
            Alignment.BottomEnd to 4.dp,
            Alignment.CenterEnd to 0.dp
        ).forEach { (alignment, inset) ->
            Box(
                Modifier
                    .align(alignment)
                    .padding(inset)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
            )
        }
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun HolidaysPanel(
    rows: List<HolidayRow>,
    nextId: String?,
    today: LocalDate,
    refreshing: Boolean,
    onOpen: (String) -> Unit
) {
    // Months still to come read top-down in calendar order; months already gone
    // follow, most recent first, so the tab opens on what still matters.
    val months = remember(rows, today) {
        val byMonth = rows
            .mapNotNull { row -> row.month?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val (past, future) = byMonth.entries.partition { (month, _) -> month.atEndOfMonth().isBefore(today) }
        future.sortedBy { it.key } + past.sortedByDescending { it.key }
    }
    val undated = remember(rows) { rows.filter { it.month == null } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ALL HOLIDAYS",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                when {
                    rows.isEmpty() && refreshing -> "Loading…"
                    rows.isEmpty() -> "Unavailable"
                    else -> "Total ${rows.size} holidays"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (rows.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
                Text(
                    if (refreshing) "Loading official holiday list…" else "Official list unavailable",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            months.forEach { (month, monthRows) ->
                HolidayMonthHeader(month, monthRows.size)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    monthRows.forEach { row ->
                        HolidayRowCard(row, isNext = row.holiday.id == nextId, onOpen = onOpen)
                    }
                }
            }
            if (undated.isNotEmpty()) {
                HolidayMonthHeader(null, undated.size)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    undated.forEach { row -> HolidayRowCard(row, isNext = false, onOpen = onOpen) }
                }
            }
        }
    }
}

@Composable
private fun HolidayMonthHeader(month: YearMonth?, count: Int) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (month?.format(HolidayMonthFormat) ?: "DATE NOT AVAILABLE").uppercase(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Amber for restricted holidays. The theme's vivid [androidx.compose.material3.ColorScheme.tertiary]
 * measures about 2.3:1 against a white surface — fine as a fill, unreadable as
 * text — so light mode steps down to a darker orange that still reads as orange
 * but clears AA for body text. Dark mode keeps the theme tertiary, which has
 * plenty of contrast on the dark surface.
 */
@Composable
private fun restrictedAmber(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFC2410C)
    else MaterialTheme.colorScheme.tertiary

@Composable
private fun HolidayRowCard(row: HolidayRow, isNext: Boolean, onOpen: (String) -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    val url = remember(row.holiday.source) { row.holiday.source.takeIf { it.startsWith("http") } }
    val kind = remember(row.holiday.category) { holidayKind(row.holiday.category) }
    // The rule and the label carry the kind: amber for restricted, aqua for a
    // half day, teal for a public holiday.
    val typeColor = when (kind) {
        HolidayKind.RESTRICTED -> restrictedAmber()
        HolidayKind.HALF_DAY -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val lineColor = if (row.isPast) MaterialTheme.colorScheme.onSurfaceVariant else typeColor
    val badge = when (kind) {
        // A category the feed invents later still gets shown rather than dropped.
        HolidayKind.OTHER -> row.holiday.category.trim().takeIf { it.isNotBlank() }
        else -> kind.label
    }
    Card(
        onClick = { if (url != null) onOpen(url) },
        enabled = url != null,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.isPast) Modifier.alpha(0.42f) else Modifier)
            .pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    row.date?.let { it.dayOfMonth.toString() } ?: "—",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    row.date?.month?.getDisplayName(TextStyle.SHORT, Locale.getDefault())?.uppercase()
                        ?: row.holiday.displayDate.take(3).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(3.dp).height(if (badge != null) 50.dp else 42.dp).background(lineColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.holiday.name,
                    // Restricted holidays are the ones worth flagging loudly — they
                    // still need the college open, so they get the amber type.
                    color = if (kind == HolidayKind.RESTRICTED) typeColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.holiday.weekday,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (badge != null) {
                    Text(
                        badge.uppercase(),
                        color = typeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isNext && row.daysUntil != null) {
                Spacer(Modifier.width(8.dp))
                CountdownPill(row.daysUntil)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = "Open holiday notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CountdownPill(days: Long) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = when {
                days <= 0L -> "Today"
                days == 1L -> "In 1 day"
                else -> "In $days days"
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun NoticeTabSelector(tab: NoticeTab, onSelect: (NoticeTab) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NoticeTab.values().forEach { item ->
            val active = item == tab
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabBorder"
            )
            val contentColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabContent"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = {
                    if (item != tab) view.hapticTick()
                    onSelect(item)
                },
                modifier = Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (item == NoticeTab.NOTICES) Icons.Default.Campaign else Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: ErpNotice, onClick: () -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(4.dp))
                Text(notice.displayDate, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                val sourceLabel = notice.author.ifBlank { notice.source }
                if (sourceLabel.isNotBlank()) Text(sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Source pills: All · GNDEC Home Page · ERP · the student's own department. */
@Composable
private fun NoticeSourceSelector(source: NoticeSource, dept: DeptSite, onSelect: (NoticeSource) -> Unit) {
    val view = LocalView.current
    val options = listOf(NoticeSource.ALL, NoticeSource.HOMEPAGE, NoticeSource.ERP, NoticeSource.DEPARTMENT)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            val active = option == source
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeSourceContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeSourceBorder"
            )
            val contentColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeSourceContent"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = {
                    if (option != source) view.hapticTick()
                    onSelect(option)
                },
                modifier = Modifier.pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(50),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Text(
                    option.labelFor(dept),
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** The department's own notices: header card first, then every board row. */
@Composable
private fun DeptNoticesSection(
    dept: DeptSite,
    state: DeptNoticesState,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    standalone: Boolean = false
) {
    when (val current = state) {
        is DeptNoticesState.Ready -> {
            if (current.deptKey != dept.key) {
                // State still belongs to a previously resolved department.
                DeptNoticesLoadingCard(dept)
                return
            }
            DeptNoticeHeaderCard(dept = dept, onOpen = onOpen)
            current.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (current.notices.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Text(
                        "Nothing on ${dept.label}'s board right now.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                current.notices.forEach { notice ->
                    DeptNoticeCard(notice = notice, onOpen = onOpen)
                }
            }
            if (standalone) {
                TealOutlineButton(
                    text = "Open ${dept.label} website",
                    icon = Icons.Default.OpenInNew,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpen(dept.url) }
                )
            }
        }
        is DeptNoticesState.Error -> {
            if (current.deptKey == dept.key) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Domain, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${dept.label} notices unavailable", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry department notices", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                DeptNoticesLoadingCard(dept)
            }
        }
        is DeptNoticesState.Loading -> DeptNoticesLoadingCard(dept)
    }
}

@Composable
private fun DeptNoticesLoadingCard(dept: DeptSite) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Domain, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(11.dp))
            Text("Loading ${dept.label} notices…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "IT DEPARTMENT NOTICES" style header; the screen's pull gesture refreshes it. */
@Composable
private fun DeptNoticeHeaderCard(
    dept: DeptSite,
    onOpen: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Domain, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("${dept.chipLabel.uppercase()} DEPARTMENT NOTICES", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text("From ${dept.host} · newest first", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onOpen(dept.url) }) {
                Icon(Icons.Default.OpenInNew, contentDescription = "Open department site", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DeptNoticeCard(notice: DeptNotice, onOpen: (String) -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = { onOpen(notice.url) },
        modifier = Modifier.fillMaxWidth().pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (notice.isNew) {
                        Spacer(Modifier.width(8.dp))
                        NewNoticePill()
                    }
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    formatDeptNoticeDate(notice.date) ?: "Department notice board",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open department notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewNoticePill() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            "NEW",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

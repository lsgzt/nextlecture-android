package com.gndec.timetable.ui.attendance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.net.AttendanceResponse
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.theme.GndecTeal
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * How far back the overall figure reaches. Matches the range the Attendance
 * screen loads, so the ring on Home agrees with the number shown there.
 */
private const val OVERALL_LOOKBACK_DAYS = 365L

private const val WEEK_DAYS = 7

sealed interface AttendanceHomeState {
    object Loading : AttendanceHomeState
    data class Ready(val response: AttendanceResponse, val target: Float) : AttendanceHomeState
    object Unavailable : AttendanceHomeState
}

/**
 * Loads the attendance summary once per Home composition.
 *
 * Kept out of the LazyColumn item on purpose: `produceState` is keyed on
 * [container] alone, so hoisting it above the list means scrolling the card out
 * of view does not cancel and re-issue the request. Calling it inside the item
 * would refetch every time the student scrolled back up.
 */
@Composable
fun rememberAttendanceHomeState(container: AppContainer): AttendanceHomeState {
    val today = remember { LocalDate.now() }
    val state by produceState<AttendanceHomeState>(AttendanceHomeState.Loading, container) {
        value = runCatching {
            val target = container.settings.flow.first().attendanceTarget
            AttendanceHomeState.Ready(
                response = container.attendanceManager.load(
                    today.minusDays(OVERALL_LOOKBACK_DAYS),
                    today,
                    target.toDouble()
                ),
                target = target
            )
        }.getOrElse { AttendanceHomeState.Unavailable }
    }
    return state
}

/**
 * Home card summarising attendance: overall percentage as a ring, and how many
 * lectures were attended on each of the last seven days as bars.
 *
 * Read-only — it reuses the existing `api/attendance` endpoint through
 * [com.gndec.timetable.domain.AttendanceManager.load] and touches no schema.
 * Renders nothing at all when the request fails, so a Home screen with no
 * network still looks exactly as it did before attendance existed.
 */
@Composable
fun AttendanceHomeSummary(state: AttendanceHomeState, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    when (state) {
        AttendanceHomeState.Loading -> AttendanceSummaryCard(modifier = modifier, onClick = onClick) {
            Text(
                "Loading your attendance…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        is AttendanceHomeState.Ready -> AttendanceSummaryCard(modifier = modifier, onClick = onClick) {
            AttendanceSummaryContent(state.response, state.target)
        }
        AttendanceHomeState.Unavailable -> Unit
    }
}

/**
 * Attendance for one subject, and whether the student can absorb skipping the
 * lecture in front of them.
 */
data class SubjectAttendance(
    val subject: String,
    val present: Int,
    val markedTotal: Int,
    val percentage: Double?,
    /** The student's own attendance target, so the tone can be judged here. */
    val target: Double,
    /** How many more lectures may be missed before the target is breached. */
    val affordableMisses: Int?
) {
    /**
     * At or above target plus this margin is doing comfortably well, which is
     * worth saying plainly rather than leaving the student to do the arithmetic.
     */
    val isComfortable: Boolean
        get() = percentage != null && affordableMisses != null &&
            percentage >= target + COMFORT_MARGIN

    /**
     * True only when the arithmetic says at least one more absence still leaves
     * the subject at or above [target]. Merely being slightly above target is
     * not enough — missing the next class can still break the bar.
     */
    val canAffordToMiss: Boolean
        get() = affordableMisses != null && affordableMisses >= 1

    /**
     * Attendance % if this next class is marked absent (present unchanged,
     * marked total + 1). Null when there is no marked history to project from.
     */
    val percentageIfMissed: Double?
        get() = if (markedTotal <= 0 && present <= 0) null
            else present.toDouble() * 100.0 / (markedTotal + 1).toDouble()
}

/** Percentage points clear of the target before the tone turns congratulatory. */
private const val COMFORT_MARGIN = 10.0

/**
 * Pull one subject out of an already-loaded response. Nothing extra is fetched —
 * the hero card and the summary section share a single `api/attendance` call.
 *
 * Matching is exact on a normalised key. AttendanceManager stores the timetable's
 * own subject string on each record, so the two are normally identical; the
 * normalisation only absorbs spelling drift such as "Maths-I" vs "Maths I".
 * A looser prefix match was tried and rejected — it pairs "Maths" with
 * "Maths-II" and would report another subject's attendance as this one's. When
 * a subject genuinely does not match, the strip simply does not appear, which
 * is a far better failure than confident wrong advice.
 */
fun subjectAttendanceFor(response: AttendanceResponse, subject: String?, target: Double): SubjectAttendance? {
    val wanted = subject?.let(::normalizeSubjectKey).orEmpty()
    if (wanted.isBlank()) return null

    val records = response.records.filter { normalizeSubjectKey(it.subject) == wanted }
    if (records.isEmpty()) return null

    val summary = calculateSummary(records, target)
    return SubjectAttendance(
        subject = subject.orEmpty(),
        present = summary.present,
        markedTotal = summary.markedTotal,
        percentage = summary.percentage,
        target = target,
        affordableMisses = summary.affordableMisses
    )
}

/** Letters and digits only, so "Maths-I", "Maths I" and "MATHS  I" all agree. */
private fun normalizeSubjectKey(raw: String?): String =
    raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/** Subject attendance for [subject], or null when attendance has not loaded. */
fun AttendanceHomeState.subjectAttendance(subject: String?): SubjectAttendance? {
    val ready = this as? AttendanceHomeState.Ready ?: return null
    return subjectAttendanceFor(ready.response, subject, ready.target.toDouble())
}

@Composable
private fun AttendanceSummaryCard(modifier: Modifier = Modifier, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "ATTENDANCE",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            content()
        }
    }
}

@Composable
private fun AttendanceSummaryContent(response: AttendanceResponse, target: Float) {
    val today = remember { LocalDate.now() }
    // Overall across everything the server returned, not just this week.
    val summary = remember(response, target) { calculateSummary(response.records, target.toDouble()) }
    val percent = summary.percentage

    // Attended lectures per day, oldest first.
    val week = remember(today) { (0 until WEEK_DAYS).map { today.minusDays((WEEK_DAYS - 1 - it).toLong()) } }
    val attendedByDay = remember(response) {
        response.records
            .filter { it.status == "present" }
            .mapNotNull { record -> runCatching { LocalDate.parse(record.attendanceDate) }.getOrNull() }
            .groupingBy { it }
            .eachCount()
    }
    val weekTotal = remember(attendedByDay, week) { week.sumOf { attendedByDay[it] ?: 0 } }
    val tone = attendanceTone(percent, target)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AttendanceRing(
            percent = percent,
            tone = tone,
            modifier = Modifier.size(104.dp)
        )
        Spacer(Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Overall",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            if (percent == null) {
                Text(
                    "Mark a lecture to start tracking",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "${summary.present} of ${summary.markedTotal} lectures",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (percent >= target) {
                        "At or above your ${target.toInt()}% target"
                    } else {
                        "${"%.1f".format(target - percent)}% below your ${target.toInt()}% target"
                    },
                    color = tone,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "LAST 7 DAYS",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$weekTotal attended",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
    Spacer(Modifier.height(6.dp))
    WeeklyBars(
        week = week,
        attendedByDay = attendedByDay,
        today = today
    )
}

/**
 * How many percentage points below target still count as "shaving it close"
 * rather than genuinely falling behind. Inside this band the ring goes amber;
 * outside it, red.
 */
private const val NEAR_TARGET_MARGIN = 5.0

/**
 * The colour the whole summary speaks in: the ring, and the line under it.
 *
 * Three tiers rather than two — teal once the target is met, amber while the
 * student is only a little under it, red once the gap is real.
 */
@Composable
private fun attendanceTone(percent: Double?, target: Float): Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent >= target -> GndecTeal
    percent >= target - NEAR_TARGET_MARGIN -> warningAmber()
    else -> MaterialTheme.colorScheme.error
}

/**
 * Amber for the "just missed it" tier. The theme's vivid tertiary is only about
 * 2.3:1 on a white surface — fine as a fill, unreadable as the 12sp line under
 * the ring — so light mode steps down to a darker orange. Dark mode keeps the
 * theme tertiary, which has ample contrast there.
 */
@Composable
private fun warningAmber(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFB45309)
    else MaterialTheme.colorScheme.tertiary

/**
 * Ring showing overall attendance. The arc sweeps clockwise from twelve o'clock
 * and is tinted by [tone], so the fill and the summary line always agree.
 */
@Composable
private fun AttendanceRing(percent: Double?, tone: Color, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val sweep by animateFloatAsState(
        targetValue = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat() * 360f,
        animationSpec = motionTween(Motion.Emphasized),
        label = "attendanceRing"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            if (sweep > 0f) {
                drawArc(
                    color = tone,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                percent?.let { "${"%.0f".format(it)}%" } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "overall",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Lectures attended per day over the last seven days. Each bar is measured
 * against the busiest day in the window, so a quiet week does not render as a
 * full-height chart. The faint full-height track is what makes a day with no
 * recorded lecture read as zero rather than as a missing value.
 */
@Composable
private fun WeeklyBars(
    week: List<LocalDate>,
    attendedByDay: Map<LocalDate, Int>,
    today: LocalDate,
    modifier: Modifier = Modifier
) {
    val peak = remember(attendedByDay, week) {
        week.maxOf { attendedByDay[it] ?: 0 }.coerceAtLeast(1)
    }
    val barColor = GndecTeal
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        week.forEach { date ->
            val count = attendedByDay[date] ?: 0
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$count",
                    color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(64.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .background(trackColor)
                    )
                    val fraction = count.toFloat() / peak
                    if (fraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceAtMost(1f))
                                .clip(RoundedCornerShape(7.dp))
                                .background(barColor)
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "${date.dayOfMonth}",
                    color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
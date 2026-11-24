@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gndec.timetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.Exam
import com.gndec.timetable.domain.ExamState
import com.gndec.timetable.domain.ExamTimeline
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.util.Formatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ExamLongDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val ExamShortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/** "Today", "Tomorrow", otherwise "Wed 25 Nov". */
internal fun examDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> ExamShortDateFormat.format(date)
}

/**
 * Where the next exam is, for a view that has none of its own: "Next exam:
 * Physics · Tomorrow · 12:45 PM – 2:15 PM", or that the schedule has ended.
 */
internal fun nextExamLine(date: LocalDate, exams: List<Exam>, today: LocalDate, nowMinutes: Int): String {
    val next = exams
        // Not started yet: one running now is not "the next exam".
        .filter { it.date.isAfter(date) || (it.date == date && it.startMinutes > nowMinutes) }
        .minWithOrNull(compareBy<Exam> { it.date }.thenBy { it.startMinutes })
        ?: return "No more exams in this schedule."
    val day = examDayLabel(next.date, today).let { label ->
        if (label == "Today" || label == "Tomorrow") label else "on $label"
    }
    return "Next exam: ${next.subject} · $day · ${Formatters.range(next.startMinutes, next.endMinutes)}"
}

/** The warm wash every exam-mode surface wears, so it never reads as an ordinary lecture card. */
@Immutable
private data class ExamColors(val brush: Brush, val accent: Color, val chip: Color)

@Composable
private fun examColors(): ExamColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        ExamColors(
            brush = Brush.linearGradient(listOf(Color(0xFF3B2222), Color(0xFF2E1A1A))),
            accent = Color(0xFFFFB4A0),
            chip = Color(0xFF5A2F2A)
        )
    } else {
        ExamColors(
            brush = Brush.linearGradient(listOf(Color(0xFFFFEDE4), Color(0xFFFFDAC6))),
            accent = Color(0xFFB3401F),
            chip = Color(0xFFFFD3BE)
        )
    }
}

/**
 * The big card on Home while a date sheet is on, in place of the next-lecture
 * card: the exam that is running now, else the next one today, else the next
 * one on a later day (see [ExamTimeline]). Until the student says which group
 * they sit with, the card asks instead.
 */
@Composable
fun PremiumExamHeroCard(
    state: ExamState,
    view: ExamTimeline.View,
    today: LocalDate,
    onOpenSchedule: () -> Unit,
    onPickGroup: (String) -> Unit,
    onChangeGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = examColors()
    val press = remember { MutableInteractionSource() }
    Card(
        onClick = onOpenSchedule,
        modifier = modifier.pressFeedback(press).background(colors.brush, RoundedCornerShape(24.dp)),
        interactionSource = press,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            val featured = view.featured
            when {
                state.needsGroupPick -> GroupPick(state, colors, onPickGroup)
                !state.readable -> {
                    Text("Exams are ongoing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The date sheet couldn’t be read, so it is shown as a notice below instead.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                featured == null -> {
                    Text("All exams are over", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nothing is left in this schedule. The timetable and reminders come back once the college switches this notice off.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    val exam = featured.exam
                    val ongoing = featured.phase == ExamTimeline.Phase.ONGOING
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = colors.accent.copy(alpha = if (ongoing) 1f else 0.14f),
                            contentColor = if (ongoing) MaterialTheme.colorScheme.surface else colors.accent,
                            shape = CircleShape
                        ) {
                            Text(
                                when {
                                    ongoing -> "NOW"
                                    featured.phase == ExamTimeline.Phase.TODAY -> "TODAY"
                                    featured.daysAhead == 1 -> "TOMORROW"
                                    else -> "UPCOMING"
                                },
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ExamLongDateFormat.format(exam.date),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = "Open exam schedule", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(
                        exam.subject,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Formatters.range(exam.startMinutes, exam.endMinutes),
                        color = colors.accent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ongoing) "ENDS IN ${Formatters.longCountdown(featured.minutes)}" else "STARTS IN ${Formatters.longCountdown(featured.minutes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                    if (exam.sections.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "For ${exam.sections.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (state.readable && state.partial) {
                Spacer(Modifier.height(12.dp))
                UnreadableLinesNote(count = state.schedule.unreadable, where = "in the notice below", accent = colors.accent)
            }
            if (state.readable && !state.needsGroupPick) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${view.finished} of ${view.total} done",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    state.group?.let { group ->
                        Icon(Icons.Default.School, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(group, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (state.schedule.groups.size > 1) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Change",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(onClick = onChangeGroup)
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupPick(state: ExamState, colors: ExamColors, onPick: (String) -> Unit) {
    Text("Which group are you in?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "This date sheet is published separately for each group. Pick yours once — you will be asked again when the next sheet is published.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(14.dp))
    ExamGroupChips(groups = state.schedule.groups, selected = null, chipColor = colors.chip, onPick = onPick)
}

/**
 * The exams still to come, under the big card. Exams that are already over are
 * left out; the one in the big card is not repeated here.
 */
@Composable
fun PremiumUpcomingExamsCard(
    upcoming: List<Exam>,
    today: LocalDate,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Upcoming exams", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (upcoming.isNotEmpty()) {
                    Text(
                        "${upcoming.size} more",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            if (upcoming.isEmpty()) {
                Text("Nothing is left in this schedule.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                upcoming.forEachIndexed { index, exam ->
                    ExamRow(exam = exam, leading = examDayLabel(exam.date, today))
                    if (index != upcoming.lastIndex) Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@Composable
private fun ExamRow(exam: Exam, leading: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(84.dp)) {
            Text(leading, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            // Under "Today" / "Tomorrow" the date itself is still worth saying.
            if (leading == "Today" || leading == "Tomorrow") {
                Text(ExamShortDateFormat.format(exam.date), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                exam.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                Formatters.range(exam.startMinutes, exam.endMinutes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (exam.sections.isNotEmpty()) {
                Text(
                    exam.sections.joinToString(", "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExamGroupChips(groups: List<String>, selected: String?, chipColor: Color, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { group ->
            val chosen = group == selected
            Surface(
                onClick = { onPick(group) },
                shape = CircleShape,
                color = if (chosen) MaterialTheme.colorScheme.primary else chipColor,
                contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                border = if (chosen) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    group,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Asked the first time each newly published date sheet needs to know the
 * student's group. "Ask me later" only closes it: the big card keeps the same
 * choices inline, and the next launch asks again until it is answered.
 */
@Composable
fun ExamGroupPickerDialog(groups: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which group are you in?") },
        text = {
            Column {
                Text("The exam schedule is published group by group. Pick yours to see your exams. This is asked again whenever a new date sheet is published.")
                Spacer(Modifier.height(8.dp))
                groups.forEach { group ->
                    Text(
                        group,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(group) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Ask me later") }
        }
    )
}

/**
 * The notice that stands in for the timetable on the Today tab: lectures and
 * reminders are paused for as long as the date sheet is on.
 */
@Composable
fun PremiumExamModeCard(state: ExamState, onPickGroup: (String) -> Unit, onChangeGroup: () -> Unit, modifier: Modifier = Modifier) {
    val colors = examColors()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(colors.accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.School, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Your exams are ongoing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Lectures and reminders are paused until the exam schedule ends.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (state.needsGroupPick) {
                Spacer(Modifier.height(16.dp))
                Text("Pick your group to see your exams:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                ExamGroupChips(groups = state.schedule.groups, selected = null, chipColor = colors.chip, onPick = onPickGroup)
            } else if (state.group != null && state.schedule.groups.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.group} · Change group",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onChangeGroup)
                            .padding(vertical = 4.dp)
                    )
                }
            }
            if (state.readable && state.partial) {
                Spacer(Modifier.height(12.dp))
                UnreadableLinesNote(count = state.schedule.unreadable, where = "in the notice on the Home tab", accent = colors.accent)
            }
        }
    }
}

/**
 * Said on every exam surface when lines of the sheet were lost, with [where] the
 * place its full text can still be read.
 */
@Composable
private fun UnreadableLinesNote(count: Int, where: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "$count line${if (count == 1) "" else "s"} of this date sheet could not be read. The full sheet is $where.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Whether a listed exam is already over, running right now, or still to come. */
internal enum class ExamStatus { DONE, RUNNING, UPCOMING }

/** The clock for a day that has already passed: every exam on it reads as over. */
internal const val EXAM_DAY_OVER = 24 * 60

/** The clock for a day still to come: nothing on it has started. */
internal const val EXAM_DAY_AHEAD = -1

/**
 * The clock a day's exam rows are read against: the current time for today,
 * the end of the day for one already past, and before the first exam for one
 * still to come. A week opened on Tuesday must show Monday's exam as over.
 */
internal fun examDayClock(date: LocalDate, today: LocalDate, nowMinutes: Int): Int = when {
    date == today -> nowMinutes
    date.isBefore(today) -> EXAM_DAY_OVER
    else -> EXAM_DAY_AHEAD
}

/** How [exam] stands at [nowMinutes], a day's clock from [examDayClock]. */
internal fun examStatus(exam: Exam, nowMinutes: Int): ExamStatus = when {
    nowMinutes < 0 -> ExamStatus.UPCOMING
    exam.endMinutes <= nowMinutes -> ExamStatus.DONE
    exam.startMinutes <= nowMinutes -> ExamStatus.RUNNING
    else -> ExamStatus.UPCOMING
}

/**
 * One day's exams, earliest first, each marked done, running now or still to
 * come. [nowMinutes] is that day's clock, from [examDayClock].
 */
@Composable
fun PremiumExamDayCard(
    exams: List<Exam>,
    title: String,
    nowMinutes: Int,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    val colors = examColors()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(13.dp))
            if (exams.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                exams.forEachIndexed { index, exam ->
                    ExamDayRow(exam = exam, status = examStatus(exam, nowMinutes), colors = colors)
                    if (index != exams.lastIndex) Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@Composable
private fun ExamDayRow(exam: Exam, status: ExamStatus, colors: ExamColors) {
    val running = status == ExamStatus.RUNNING
    val done = status == ExamStatus.DONE
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (running) colors.chip else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(76.dp)) {
            Text(
                Formatters.hm(exam.startMinutes),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(Formatters.hm(exam.endMinutes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.width(3.dp).height(34.dp).background(if (running) colors.accent else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                exam.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (exam.sections.isNotEmpty()) {
                Text(
                    exam.sections.joinToString(", "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (status) {
                ExamStatus.DONE -> "Done"
                ExamStatus.RUNNING -> "Now"
                ExamStatus.UPCOMING -> "Upcoming"
            },
            color = if (running) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

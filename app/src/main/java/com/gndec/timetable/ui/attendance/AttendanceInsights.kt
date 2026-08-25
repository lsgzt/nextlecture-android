package com.gndec.timetable.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.db.TimetableSnapshotEntity
import com.gndec.timetable.net.AttendanceRecord
import com.gndec.timetable.net.AttendanceSummary
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecTeal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun FullCalendarDialog(
    month: YearMonth,
    today: LocalDate,
    records: List<AttendanceRecord>,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val recordsByDate = records.groupBy { it.attendanceDate }
    val firstDayOffset = month.atDay(1).dayOfWeek.value - 1
    val dates = buildList<LocalDate?> {
        repeat(firstDayOffset) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
    }
    val oldestAllowed = today.minusDays(365)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onMonthChange(month.minusMonths(1)) },
                        enabled = month.atEndOfMonth().isAfter(oldestAllowed)
                    ) { Icon(Icons.Default.ArrowBack, contentDescription = "Previous month") }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = { onMonthChange(month.plusMonths(1)) },
                        enabled = month.atDay(1).isBefore(today)
                    ) { Icon(Icons.Default.ArrowForward, contentDescription = "Next month") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
                dates.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { date ->
                            Box(Modifier.weight(1f).height(48.dp)) {
                                if (date == null) {
                                    Spacer(Modifier.fillMaxSize())
                                } else {
                                    val dateRecords = recordsByDate[date.toString()].orEmpty()
                                    CalendarDayCell(
                                        date = date,
                                        present = dateRecords.count { it.status == "present" },
                                        absent = dateRecords.count { it.status == "absent" },
                                        enabled = !date.isAfter(today) && !date.isBefore(oldestAllowed),
                                        onClick = { onDateSelected(date) }
                                    )
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f).height(48.dp)) }
                    }
                }
                Text("Tap any available date to view and mark its retained timetable lectures. History is kept for the last year.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(date: LocalDate, present: Int, absent: Int, enabled: Boolean, onClick: () -> Unit) {
    val background = when {
        absent > 0 -> MaterialTheme.colorScheme.errorContainer
        present > 0 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (present > 0 || absent > 0) {
                Text("${present}P ${absent}A", style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (absent > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SubjectSummaryCard(records: List<AttendanceRecord>, target: Float) {
    val subjectGroups = records.filter { it.status == "present" || it.status == "absent" }
        .groupBy { it.subject.trim().ifBlank { "Unnamed subject" } }
        .toList()
        .sortedBy { it.first.lowercase() }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SUBJECT-WISE ATTENDANCE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            if (subjectGroups.isEmpty()) {
                Text("Mark lectures to see each subject’s percentage.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                subjectGroups.forEach { (subject, subjectRecords) ->
                    val summary = calculateSummary(subjectRecords, target.toDouble())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${summary.present} present · ${summary.absent} absent", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(summary.percentage?.let { "${"%.1f".format(it)}%" } ?: "—", color = if ((summary.percentage ?: 100.0) < target) MaterialTheme.colorScheme.error else GndecTeal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

internal fun TimetableSnapshotEntity.toLectureEntity() = LectureEntity(
    groupName = groupName,
    dayOfWeek = dayOfWeek,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    subject = subject,
    teacher = teacher,
    venue = venue,
    lectureType = lectureType,
    rawText = rawText,
    fetchId = fetchId
)

internal fun AttendanceRecord.toLectureEntity(groupName: String) = LectureEntity(
    groupName = groupName,
    dayOfWeek = 1,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    subject = subject,
    teacher = teacher,
    venue = venue,
    lectureType = null,
    rawText = subject,
    fetchId = 0L
)

internal fun calculateSummary(records: List<AttendanceRecord>, target: Double): AttendanceSummary {
    val present = records.count { it.status == "present" }
    val absent = records.count { it.status == "absent" }
    val marked = present + absent
    val percentage = if (marked == 0) null else present.toDouble() * 100.0 / marked
    val ratio = target / 100.0
    val affordable = when {
        ratio <= 0.0 -> 0
        ratio >= 1.0 -> 0
        else -> kotlin.math.max(0, kotlin.math.floor(present / ratio - marked + 1e-9).toInt())
    }
    val toAttend = if (ratio in 0.0..1.0 && ratio < 1.0 && marked > 0 && percentage != null && percentage < target) {
        kotlin.math.ceil((ratio * marked - present) / (1.0 - ratio)).toInt()
    } else null
    return AttendanceSummary(
        present = present,
        absent = absent,
        markedTotal = marked,
        percentage = percentage,
        target = target,
        affordableMisses = affordable,
        lecturesToAttend = toAttend
    )
}

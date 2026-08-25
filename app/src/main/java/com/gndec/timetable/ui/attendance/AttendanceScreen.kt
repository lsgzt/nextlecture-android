package com.gndec.timetable.ui.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.db.TimetableSnapshotEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.AttendanceManager
import com.gndec.timetable.net.AttendanceRecord
import com.gndec.timetable.net.AttendanceResponse
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.util.Formatters
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val attendanceDateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val attendanceShortFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun AttendanceScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var selectedDate by remember { mutableStateOf(today) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var fullCalendarOpen by remember { mutableStateOf(false) }
    var target by remember { mutableFloatStateOf(75f) }
    var response by remember { mutableStateOf<AttendanceResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var savingKey by remember { mutableStateOf<String?>(null) }
    var activeGroup by remember { mutableStateOf("") }
    var lectures by remember { mutableStateOf<List<LectureEntity>>(emptyList()) }
    var snapshotLectures by remember { mutableStateOf<List<TimetableSnapshotEntity>>(emptyList()) }
    val historyDates = remember(today) { (0L..13L).map { today.minusDays(it) }.reversed() }

    suspend fun reloadNow() {
        loading = true
        error = null
        runCatching {
            val cfg = container.settings.flow.first()
            activeGroup = cfg.studentSubsection.ifBlank { cfg.group.orEmpty() }
            lectures = if (activeGroup.isBlank()) emptyList() else container.db.lectureDao().getForGroup(activeGroup)
            container.refreshManager.ensureCurrentWeekSnapshots(activeGroup)
            container.attendanceManager.load(today.minusDays(365), today, target.toDouble())
        }.onSuccess { loaded -> response = loaded }
            .onFailure { error = it.message ?: "Could not load attendance from the server" }
        loading = false
    }

    fun reload() {
        scope.launch { reloadNow() }
    }

    LaunchedEffect(Unit) {
        target = container.settings.flow.first().attendanceTarget
        reloadNow()
    }

    LaunchedEffect(activeGroup, selectedDate) {
        snapshotLectures = if (activeGroup.isBlank()) {
            emptyList()
        } else {
            container.db.timetableSnapshotDao().getForGroupAndDate(activeGroup, selectedDate.toString())
        }
    }

    val recordByKey = response?.records.orEmpty().associateBy { it.lectureKey }
    val selectedDateRecords = response?.records.orEmpty().filter { it.attendanceDate == selectedDate.toString() }
    val snapshotLectureEntities = snapshotLectures.map { it.toLectureEntity() }
    val markedLectureEntities = selectedDateRecords.map { it.toLectureEntity(activeGroup) }
    val dayLectures = (snapshotLectureEntities + markedLectureEntities)
        .ifEmpty { if (selectedDate == today) lectures.filter { it.dayOfWeek == selectedDate.dayOfWeek.value } else emptyList() }
        .distinctBy { AttendanceManager.lectureKey(selectedDate, it) }
        .sortedBy { it.startMinutes }
    val selectedDateStatus = selectedDateRecords.groupingBy { it.status }.eachCount()

    PremiumScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { PremiumPageHeader("Attendance", "Your server-synced lecture record", onBack = onBack) }
            item {
                AttendanceSummaryCard(
                    response = response,
                    target = target,
                    onTargetChange = { value ->
                        target = value
                        scope.launch { container.settings.setAttendanceTarget(value) }
                    }
                )
            }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Choose a date", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(historyDates) { date ->
                                val dateRecords = response?.records.orEmpty().filter { it.attendanceDate == date.toString() }
                                val present = dateRecords.count { it.status == "present" }
                                val absent = dateRecords.count { it.status == "absent" }
                                val selected = date == selectedDate
                                val chipColor by animateColorAsState(
                                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    animationSpec = motionTween(Motion.Normal),
                                    label = "dateChip"
                                )
                                val pressInteraction = remember { MutableInteractionSource() }
                                Card(
                                    onClick = { selectedDate = date },
                                    modifier = Modifier.pressFeedback(pressInteraction, pressedScale = 0.94f),
                                    interactionSource = pressInteraction,
                                    colors = CardDefaults.cardColors(containerColor = chipColor),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(date.dayOfWeek.name.take(3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (present > 0) Text("$present P", color = GndecTeal, style = MaterialTheme.typography.labelSmall)
                                            if (absent > 0) Text("$absent A", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                            if (present == 0 && absent == 0) Text("—", color = GndecMuted, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) { Icon(Icons.Default.ArrowBack, "Previous date") }
                            Text(selectedDate.format(attendanceDateFormatter), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { if (selectedDate.isBefore(today)) selectedDate = selectedDate.plusDays(1) }, enabled = selectedDate.isBefore(today)) { Icon(Icons.Default.ArrowForward, "Next date") }
                        }
                        Text("${selectedDateStatus["present"] ?: 0} present · ${selectedDateStatus["absent"] ?: 0} absent", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { calendarMonth = YearMonth.from(selectedDate); fullCalendarOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View full calendar")
                        }
                    }
                }
            }
            if (error != null) {
                item(key = "error") { Box(Modifier.animateItem()) { AttendanceErrorCard(error!!, onRetry = ::reload) } }
            }
            if (activeGroup.isBlank()) {
                item(key = "no-group") {
                    Box(Modifier.animateItem()) {
                        AttendanceInfoCard("Complete your profile first", "Attendance uses your saved subsection to match lectures, for example ITB2 rather than the mentoring group.")
                    }
                }
            } else if (loading && response == null) {
                item(key = "loading") { Box(Modifier.animateItem()) { LoadingCard() } }
            } else if (dayLectures.isEmpty()) {
                item(key = "empty-day") {
                    Box(Modifier.animateItem()) {
                        AttendanceInfoCard("No lectures for this date", "The saved timetable has no lecture for ${selectedDate.format(attendanceShortFormatter)}. You can choose another date above.")
                    }
                }
            } else {
                item(key = "lectures-label") {
                    Text("Lectures on ${selectedDate.format(attendanceShortFormatter)}", Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(dayLectures, key = { "${selectedDate}_${AttendanceManager.lectureKey(selectedDate, it)}" }) { lecture ->
                    Box(Modifier.animateItem()) {
                        val key = AttendanceManager.lectureKey(selectedDate, lecture)
                        AttendanceLectureCard(
                        lecture = lecture,
                        status = recordByKey[key]?.status,
                        saving = savingKey == key,
                        onStatus = { status ->
                            savingKey = key
                            scope.launch {
                                runCatching { container.attendanceManager.mark(selectedDate, lecture, status) }
                                    .onSuccess { reload() }
                                    .onFailure { error = it.message ?: "Could not save attendance" }
                                savingKey = null
                            }
                        },
                        onUnmark = {
                            savingKey = key
                            scope.launch {
                                runCatching { container.attendanceManager.unmark(selectedDate, lecture) }
                                    .onSuccess { reload() }
                                    .onFailure { error = it.message ?: "Could not remove attendance" }
                                savingKey = null
                            }
                        }
                        )
                    }
                }
            }
            item { SubjectSummaryCard(response?.records.orEmpty(), target) }
            item { AttendanceInfoCard("How the percentage works", "Only lectures you mark as present or absent are counted. Unmarked lectures stay neutral. You can update a mark later if you made a mistake.") }
        }
        if (fullCalendarOpen) {
            FullCalendarDialog(
                month = calendarMonth,
                today = today,
                records = response?.records.orEmpty(),
                onMonthChange = { calendarMonth = it },
                onDateSelected = { date -> selectedDate = date; fullCalendarOpen = false },
                onDismiss = { fullCalendarOpen = false }
            )
        }
    }
}

@Composable
private fun AttendanceSummaryCard(response: AttendanceResponse?, target: Float, onTargetChange: (Float) -> Unit) {
    val summary = response?.let { calculateSummary(it.records, target.toDouble()) }
    val percentage = summary?.percentage
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CURRENT ATTENDANCE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text(if (percentage == null) "—" else "${"%.1f".format(percentage)}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("${summary?.present ?: 0} present · ${summary?.absent ?: 0} absent", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Default.EventAvailable, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("Target: ${target.toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Slider(value = target, onValueChange = onTargetChange, valueRange = 50f..100f, steps = 9)
            val misses = summary?.affordableMisses
            val attend = summary?.lecturesToAttend
            Text(
                when {
                    summary == null -> "Loading your server record…"
                    summary.markedTotal == 0 -> "Mark lectures to see how many classes you can afford to miss."
                    misses != null && attend == null -> "You can afford to miss $misses more ${if (misses == 1) "lecture" else "lectures"} and remain at or above ${target.toInt()}%."
                    attend != null -> "Attend the next $attend ${if (attend == 1) "lecture" else "lectures"} without another absence to recover to ${target.toInt()}%."
                    else -> "Your attendance target is ${target.toInt()}%."
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AttendanceLectureCard(
    lecture: LectureEntity,
    status: String?,
    saving: Boolean,
    onStatus: (String) -> Unit,
    onUnmark: () -> Unit
) {
    val iconSwapIn = motionTween<Float>(Motion.Fast)
    val iconSwapOut = motionTween<Float>(Motion.Fast)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lecture.subject?.takeIf { it.isNotBlank() } ?: "Lecture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formatters.range(lecture.startMinutes, lecture.endMinutes), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(listOf(lecture.teacher, lecture.venue).filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { "Details unavailable" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn(iconSwapIn) togetherWith fadeOut(iconSwapOut) },
                    label = "attendanceStatusIcon"
                ) { current ->
                    when (current) {
                        "present" -> Icon(Icons.Default.CheckCircle, "Present", tint = GndecTeal, modifier = Modifier.size(28.dp))
                        "absent" -> Icon(Icons.Default.EventBusy, "Absent", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        else -> Icon(Icons.Default.RemoveCircleOutline, "Unmarked", tint = GndecMuted, modifier = Modifier.size(28.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val swapIn = motionTween<Float>(Motion.Fast)
            val swapOut = motionTween<Float>(Motion.Fast)
            AnimatedContent(
                targetState = saving,
                transitionSpec = { fadeIn(swapIn) togetherWith fadeOut(swapOut) },
                label = "attendanceSaving"
            ) { isSaving ->
                if (isSaving) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presentPress = remember { MutableInteractionSource() }
                        Button(
                            onClick = { onStatus("present") },
                            modifier = Modifier.weight(1f).pressFeedback(presentPress, pressedScale = 0.96f),
                            interactionSource = presentPress,
                            colors = ButtonDefaults.buttonColors(containerColor = GndecTeal, contentColor = Color.White)
                        ) { Text("Present") }
                        val absentPress = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { onStatus("absent") },
                            modifier = Modifier.weight(1f).pressFeedback(absentPress, pressedScale = 0.96f),
                            interactionSource = absentPress
                        ) { Text("Absent", color = MaterialTheme.colorScheme.error) }
                        if (status != null) TextButton(onClick = onUnmark) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceInfoCard(title: String, message: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column { Text(title, fontWeight = FontWeight.Bold); Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AttendanceErrorCard(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    }
}

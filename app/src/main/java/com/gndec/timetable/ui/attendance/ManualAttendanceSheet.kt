package com.gndec.timetable.ui.attendance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.ui.theme.lectureTypeColors
import com.gndec.timetable.util.Formatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ManualDateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

private data class LectureTypeOption(
    val key: String,
    val label: String,
    val icon: ImageVector
)

private val LectureTypeOptions = listOf(
    LectureTypeOption("lecture", "Lecture", Icons.AutoMirrored.Filled.MenuBook),
    LectureTypeOption("tutorial", "Tutorial", Icons.Default.Groups),
    LectureTypeOption("practical", "Lab", Icons.Default.Science)
)

private val DurationPresets = listOf(50 to "50 min", 60 to "1 hr", 120 to "2 hr")

@Composable
fun ManualAttendancePromptCard(onClick: () -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Mark attendance for a different subject?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Teacher swap or make-up class — pick subject, type, and time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAttendanceSheet(
    subjects: List<String>,
    defaultDate: LocalDate,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (subject: String, lectureType: String, date: LocalDate, startMinutes: Int, endMinutes: Int, status: String) -> Unit
) {
    var subjectQuery by remember { mutableStateOf("") }
    var selectedSubject by remember { mutableStateOf<String?>(null) }
    var subjectMenuOpen by remember { mutableStateOf(false) }
    var lectureType by remember { mutableStateOf("lecture") }
    var date by remember { mutableStateOf(defaultDate) }
    var startMinutes by remember { mutableIntStateOf(9 * 60) }
    var endMinutes by remember { mutableIntStateOf(10 * 60) }
    var editingStart by remember { mutableStateOf(true) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val view = LocalView.current
    val today = remember { LocalDate.now() }

    val filteredSubjects = remember(subjects, subjectQuery) {
        val q = subjectQuery.trim()
        if (q.isEmpty()) subjects
        else subjects.filter { it.contains(q, ignoreCase = true) }
    }

    val currentSubjectValue = selectedSubject ?: subjectQuery
    val durationMinutes = endMinutes - startMinutes

    fun submitWithStatus(status: String) {
        val subject = currentSubjectValue.trim()
        when {
            subject.isBlank() -> formError = "Pick or type a subject name first"
            endMinutes <= startMinutes -> formError = "End time must be after start time"
            else -> {
                formError = null
                onSubmit(subject, lectureType, date, startMinutes, endMinutes, status)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .widthIn(max = 460.dp)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Manual attendance",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "For swaps and make-up lectures",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(
                        onClick = { if (!saving) onDismiss() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                // Scrollable Form Body (hugs content on normal screens, scrolls on compact screens)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Subject
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionEyebrow("Subject")
                        ExposedDropdownMenuBox(
                            expanded = subjectMenuOpen,
                            onExpandedChange = { subjectMenuOpen = it }
                        ) {
                            OutlinedTextField(
                                value = currentSubjectValue,
                                onValueChange = {
                                    selectedSubject = null
                                    subjectQuery = it
                                    subjectMenuOpen = true
                                    if (formError != null) formError = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                                placeholder = {
                                    Text(
                                        "Search or type a subject",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (currentSubjectValue.isNotEmpty()) {
                                            IconButton(
                                                onClick = {
                                                    selectedSubject = null
                                                    subjectQuery = ""
                                                    subjectMenuOpen = true
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Clear subject",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = subjectMenuOpen)
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                )
                            )
                            val trimmedQuery = subjectQuery.trim()
                            val hasCustomOption = trimmedQuery.isNotEmpty() &&
                                filteredSubjects.none { it.equals(trimmedQuery, ignoreCase = true) }
                            ExposedDropdownMenu(
                                expanded = subjectMenuOpen && (filteredSubjects.isNotEmpty() || hasCustomOption),
                                onDismissRequest = { subjectMenuOpen = false }
                            ) {
                                if (hasCustomOption) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Use \"$trimmedQuery\"",
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            selectedSubject = trimmedQuery
                                            subjectQuery = trimmedQuery
                                            subjectMenuOpen = false
                                            formError = null
                                        }
                                    )
                                }
                                filteredSubjects.take(12).forEach { name ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                name,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            selectedSubject = name
                                            subjectQuery = name
                                            subjectMenuOpen = false
                                            formError = null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 2. Lecture Type (3-pill selector instead of dropdown)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionEyebrow("Class type")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LectureTypeOptions.forEach { option ->
                                val selected = lectureType == option.key
                                val palette = lectureTypeColors(option.key)
                                val bg by animateColorAsState(
                                    targetValue = if (selected) {
                                        if (option.key == "lecture") MaterialTheme.colorScheme.primaryContainer
                                        else palette.cardLive
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    animationSpec = motionTween(Motion.Fast),
                                    label = "typeBg"
                                )
                                val borderColor by animateColorAsState(
                                    targetValue = if (selected) {
                                        palette.line
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                    },
                                    animationSpec = motionTween(Motion.Fast),
                                    label = "typeBorder"
                                )
                                val contentColor by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    animationSpec = motionTween(Motion.Fast),
                                    label = "typeFg"
                                )
                                val press = remember { MutableInteractionSource() }
                                Surface(
                                    onClick = {
                                        view.hapticTick()
                                        lectureType = option.key
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .pressFeedback(press, pressedScale = 0.96f),
                                    interactionSource = press,
                                    shape = RoundedCornerShape(14.dp),
                                    color = bg,
                                    border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            option.icon,
                                            contentDescription = null,
                                            tint = if (selected) palette.line else contentColor,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            option.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = contentColor,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Date Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SectionEyebrow("Date")
                            if (date != today) {
                                Surface(
                                    onClick = {
                                        view.hapticTick()
                                        date = today
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        "Jump to today",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CalendarToday,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        date.format(ManualDateFormatter),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val relative = when (date) {
                                        today -> "Today"
                                        today.minusDays(1) -> "Yesterday"
                                        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                                    }
                                    Text(
                                        relative,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        view.hapticTick()
                                        date = date.minusDays(1)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous day"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (date.isBefore(today)) {
                                            view.hapticTick()
                                            date = date.plusDays(1)
                                        }
                                    },
                                    enabled = date.isBefore(today),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next day"
                                    )
                                }
                            }
                        }
                    }

                    // 4. Time Range + Quick Duration Chips
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SectionEyebrow("Time")
                            val validRange = durationMinutes > 0
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (validRange) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            ) {
                                Text(
                                    text = if (validRange) Formatters.countdown(durationMinutes) else "Invalid range",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (validRange) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TimeSlotCard(
                                label = "From",
                                minutes = startMinutes,
                                active = timePickerOpen && editingStart,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    editingStart = true
                                    timePickerOpen = true
                                }
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            TimeSlotCard(
                                label = "To",
                                minutes = endMinutes,
                                active = timePickerOpen && !editingStart,
                                isInvalid = durationMinutes <= 0,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    editingStart = false
                                    timePickerOpen = true
                                }
                            )
                        }

                        // Quick duration chips
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DurationPresets.forEach { (mins, label) ->
                                val active = durationMinutes == mins
                                val bg by animateColorAsState(
                                    targetValue = if (active) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    animationSpec = motionTween(Motion.Fast),
                                    label = "durationBg"
                                )
                                Surface(
                                    onClick = {
                                        view.hapticTick()
                                        endMinutes = (startMinutes + mins).coerceAtMost(23 * 60 + 59)
                                        formError = null
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = bg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = formError != null,
                        enter = expandVertically(motionTween(Motion.Fast)) + fadeIn(motionTween(Motion.Fast)),
                        exit = shrinkVertically(motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formError.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                // Footer Actions: Present / Absent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val presentPress = remember { MutableInteractionSource() }
                    Button(
                        onClick = { submitWithStatus("present") },
                        enabled = !saving,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .pressFeedback(presentPress, pressedScale = 0.96f),
                        interactionSource = presentPress,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GndecTeal,
                            contentColor = Color.White
                        )
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Present", fontWeight = FontWeight.Bold)
                        }
                    }

                    val absentPress = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { submitWithStatus("absent") },
                        enabled = !saving,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .pressFeedback(absentPress, pressedScale = 0.96f),
                        interactionSource = absentPress,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.EventBusy, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Absent", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (timePickerOpen) {
        PremiumTimeWheelDialog(
            initialMinutes = if (editingStart) startMinutes else endMinutes,
            title = if (editingStart) "Start time" else "End time",
            onDismiss = { timePickerOpen = false },
            onConfirm = { mins ->
                if (editingStart) {
                    val prevDuration = (endMinutes - startMinutes).takeIf { it in 15..240 } ?: 60
                    startMinutes = mins
                    endMinutes = (mins + prevDuration).coerceAtMost(23 * 60 + 59)
                } else {
                    endMinutes = mins
                }
                formError = null
                timePickerOpen = false
            }
        )
    }
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun TimeSlotCard(
    label: String,
    minutes: Int,
    active: Boolean,
    isInvalid: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val press = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = when {
            isInvalid -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        },
        animationSpec = motionTween(Motion.Fast),
        label = "timeSlotBg"
    )
    val border by animateColorAsState(
        targetValue = when {
            isInvalid -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        },
        animationSpec = motionTween(Motion.Fast),
        label = "timeSlotBorder"
    )
    Surface(
        onClick = onClick,
        modifier = modifier.pressFeedback(press, pressedScale = 0.97f),
        interactionSource = press,
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(if (active || isInvalid) 1.5.dp else 1.dp, border)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = if (isInvalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Icon(
                    Icons.Default.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Formatters.hm(minutes),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.Bold,
                color = if (isInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

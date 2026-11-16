package com.gndec.timetable.ui.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.util.Formatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val ManualDateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val LectureTypes = listOf("lecture", "tutorial", "practical")

@Composable
fun ManualAttendancePromptCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
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
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Mark attendance for a different subject?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Teacher swap or make-up class — pick the subject, type, and time yourself.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
    var typeMenuOpen by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(defaultDate) }
    var startMinutes by remember { mutableIntStateOf(9 * 60) }
    var endMinutes by remember { mutableIntStateOf(10 * 60) }
    var editingStart by remember { mutableStateOf(true) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val filteredSubjects = remember(subjects, subjectQuery) {
        val q = subjectQuery.trim()
        if (q.isEmpty()) subjects
        else subjects.filter { it.contains(q, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!saving) onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
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
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Subject
                    Text("Subject", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ExposedDropdownMenuBox(
                        expanded = subjectMenuOpen,
                        onExpandedChange = { subjectMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSubject ?: subjectQuery,
                            onValueChange = {
                                selectedSubject = null
                                subjectQuery = it
                                subjectMenuOpen = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            placeholder = { Text("Search or type a subject") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = subjectMenuOpen) }
                        )
                        ExposedDropdownMenu(
                            expanded = subjectMenuOpen && filteredSubjects.isNotEmpty(),
                            onDismissRequest = { subjectMenuOpen = false }
                        ) {
                            filteredSubjects.take(12).forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        selectedSubject = name
                                        subjectQuery = name
                                        subjectMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    // Type
                    Text("Lecture type", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ExposedDropdownMenuBox(
                        expanded = typeMenuOpen,
                        onExpandedChange = { typeMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = lectureType.replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                            shape = RoundedCornerShape(16.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = typeMenuOpen,
                            onDismissRequest = { typeMenuOpen = false }
                        ) {
                            LectureTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (type) {
                                                "practical" -> "Lab / Practical"
                                                "tutorial" -> "Tutorial"
                                                else -> "Lecture"
                                            }
                                        )
                                    },
                                    onClick = {
                                        lectureType = type
                                        typeMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    // Day
                    Text("Day", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    date.format(ManualDateFormatter),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(
                                onClick = { date = date.minusDays(1) },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text("−1d") }
                            TextButton(
                                onClick = {
                                    if (date.isBefore(LocalDate.now())) date = date.plusDays(1)
                                },
                                enabled = date.isBefore(LocalDate.now()),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text("+1d") }
                        }
                    }

                    // Time range
                    Text("Time", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TimeChip(
                            label = "From",
                            minutes = startMinutes,
                            selected = editingStart,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                editingStart = true
                                timePickerOpen = true
                            }
                        )
                        TimeChip(
                            label = "To",
                            minutes = endMinutes,
                            selected = !editingStart,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                editingStart = false
                                timePickerOpen = true
                            }
                        )
                    }

                    formError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Present / Absent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val subject = (selectedSubject ?: subjectQuery).trim()
                            when {
                                subject.isBlank() -> formError = "Pick or type a subject"
                                endMinutes <= startMinutes -> formError = "End time must be after start time"
                                else -> {
                                    formError = null
                                    onSubmit(subject, lectureType, date, startMinutes, endMinutes, "present")
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GndecTeal)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Present", fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val subject = (selectedSubject ?: subjectQuery).trim()
                            when {
                                subject.isBlank() -> formError = "Pick or type a subject"
                                endMinutes <= startMinutes -> formError = "End time must be after start time"
                                else -> {
                                    formError = null
                                    onSubmit(subject, lectureType, date, startMinutes, endMinutes, "absent")
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            Icons.Default.EventBusy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Absent", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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
                if (editingStart) startMinutes = mins else endMinutes = mins
                timePickerOpen = false
            }
        )
    }
}

@Composable
private fun TimeChip(
    label: String,
    minutes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                Formatters.hm(minutes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Alarm-app style wheel: AM/PM · hour · minute, with a center highlight band.
 */
@Composable
fun PremiumTimeWheelDialog(
    initialMinutes: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val clamped = initialMinutes.coerceIn(0, 23 * 60 + 59)
    var isAm by remember { mutableStateOf(clamped < 12 * 60) }
    var hour12 by remember {
        mutableIntStateOf(
            ((clamped / 60) % 12).let { if (it == 0) 12 else it }
        )
    }
    var minute by remember { mutableIntStateOf(clamped % 60) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                Modifier
                    .width(320.dp)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    Modifier.padding(top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 12.dp)
                ) {
                    // Center selection band
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    )
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WheelColumn(
                            items = listOf("AM", "PM"),
                            selectedIndex = if (isAm) 0 else 1,
                            modifier = Modifier.weight(1f),
                            onSelected = { isAm = it == 0 }
                        )
                        WheelColumn(
                            items = (1..12).map { "%02d".format(it) },
                            selectedIndex = hour12 - 1,
                            modifier = Modifier.weight(1f),
                            onSelected = { hour12 = it + 1 }
                        )
                        WheelColumn(
                            items = (0..59).map { "%02d".format(it) },
                            selectedIndex = minute,
                            modifier = Modifier.weight(1f),
                            onSelected = { minute = it }
                        )
                    }
                    // Top/bottom fade
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(MaterialTheme.colorScheme.surface, Color.Transparent)
                                )
                            )
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val hour24 = when {
                                isAm && hour12 == 12 -> 0
                                !isAm && hour12 == 12 -> 12
                                isAm -> hour12
                                else -> hour12 + 12
                            }
                            onConfirm(hour24 * 60 + minute)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Set") }
                }
            }
        }
    }
}

@Composable
private fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemHeight = 44.dp

    LaunchedEffect(selectedIndex, items.size) {
        val target = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        listState.scrollToItem(target)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect {
                // Prefer the item closest to center after scroll settles is handled via click;
                // also update when user flings near an index.
                val idx = listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
                if (idx != selectedIndex) onSelected(idx)
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(items.size) { index ->
            val selected = index == selectedIndex
            Text(
                text = items[index],
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onSelected(index)
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                },
                fontSize = if (selected) 22.sp else 18.sp
            )
        }
    }
}

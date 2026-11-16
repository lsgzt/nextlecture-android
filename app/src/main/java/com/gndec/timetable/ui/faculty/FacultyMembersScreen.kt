package com.gndec.timetable.ui.faculty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ExtraTeacherIdPrefix
import com.gndec.timetable.domain.FacultyDirectoryManager
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.motion.hapticTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Faculty directory browser — opened from the "Faculty members" card in the
 * More tab. Lists EVERY teacher in the bundled college directory (including
 * departments that publish no teacher timetable — their detail page answers
 * with contact details plus the honest "timetable not available" note).
 *
 * A department picker of chips narrows the list to one department; a name
 * filter narrows it further. Tapping a teacher opens the full profile:
 * contact details, live free/busy status and the weekly timetable.
 */
@Composable
fun FacultyMembersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenFaculty: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // The teacher whose photo the user tapped open in the zoomable viewer.
    var photoViewerFor by remember { mutableStateOf<FacultyRecord?>(null) }

    // Prefer the process-wide snapshot (instant). Never parse the bundled
    // directory on the main thread — that froze the UI for hundreds of ms.
    var faculty by remember {
        mutableStateOf(container.facultyTimetables.combinedFacultyFast().orEmpty())
    }
    var listLoading by remember { mutableStateOf(faculty.isEmpty()) }
    // Survives navigation to a profile and back (this composable is disposed
    // while the detail route is on top of the stack).
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(Unit) {
        // 1) Directory only — enough for a smooth first paint of ~300 teachers.
        if (faculty.isEmpty()) {
            val base = withContext(Dispatchers.Default) {
                container.facultyTimetables.combinedFaculty()
            }
            faculty = base
            listLoading = false
        }
        // 2) Optional: warm dept docs in the background to discover timetable-
        // only teachers. Must not block opening the list.
        withContext(Dispatchers.IO) {
            runCatching { container.facultyTimetables.ensureAllDocs() }
        }
        val richer = withContext(Dispatchers.Default) {
            container.facultyTimetables.combinedFaculty()
        }
        if (richer.size > faculty.size) faculty = richer
        listLoading = false
    }

    var filterText by rememberSaveable { mutableStateOf("") }
    var selectedDept by rememberSaveable { mutableStateOf<String?>(null) }

    val departments = remember(faculty) {
        FacultyDirectoryManager.orderedDepartments(
            faculty.map { it.department }.filter { it.isNotBlank() }.distinct()
        )
    }
    val counts = remember(faculty) { faculty.groupingBy { it.department }.eachCount() }

    val visible = remember(faculty, filterText, selectedDept) {
        val needle = filterText.trim().lowercase()
        faculty.filter { record ->
            (selectedDept == null || record.department == selectedDept) &&
                (needle.isBlank() ||
                    record.name.lowercase().contains(needle) ||
                    // a teacher can carry several addresses and two numbers -
                    // the needle may be any one of them
                    record.emails.any { it.lowercase().contains(needle) } ||
                    record.mobileNumbers.any { it.contains(needle) } ||
                    record.officeNumbers.any { it.contains(needle) })
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 26.dp + PremiumBottomBarContentClearance
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "header") {
                    Header(
                        title = "Faculty members",
                        subtitle = if (faculty.isEmpty()) null
                        else "${faculty.size} teachers · ${departments.size} departments",
                        onBack = onBack,
                        onSettings = onOpenSettings
                    )
                }

                if (faculty.isEmpty()) {
                    item(key = "loading") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Loading the college directory…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else {
                    item(key = "filter-field") {
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            placeholder = { Text("Filter by name or email") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (filterText.isNotEmpty()) {
                                    IconButton(onClick = { filterText = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                        )
                    }

                    // Department picker — "All" plus one chip per department.
                    item(key = "dept-chips") {
                        Column {
                            Text(
                                "DEPARTMENT",
                                modifier = Modifier.padding(start = 22.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item(key = "dept-all") {
                                    DeptChip(
                                        label = "All",
                                        count = faculty.size,
                                        selected = selectedDept == null,
                                        onClick = { selectedDept = null }
                                    )
                                }
                                items(departments, key = { it }) { dept ->
                                    DeptChip(
                                        label = FacultyDirectoryManager.shortDepartment(dept),
                                        count = counts[dept] ?: 0,
                                        selected = selectedDept == dept,
                                        onClick = {
                                            selectedDept = if (selectedDept == dept) null else dept
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Text(
                                        "No teachers match",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Try a shorter name, an email, or pick a different department.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    } else if (selectedDept != null) {
                        // One department picked: a flat alphabetical list, the
                        // chip already says which department we are in.
                        items(visible, key = { "faculty-${it.profileId}" }) { record ->
                            FacultyRow(
                                record = record,
                                store = container.facultyPhotos,
                                onClick = { onOpenFaculty(record.profileId) },
                                onPhotoClick = { photoViewerFor = record }
                            )
                        }
                    } else {
                        // "All": grouped by department, canonical order.
                        val grouped = visible.groupBy { it.department }
                        departments.forEach { dept ->
                            val records = grouped[dept].orEmpty()
                            if (records.isNotEmpty()) {
                                item(key = "group-$dept") {
                                    Text(
                                        "${dept.uppercase()} · ${records.size} TEACHER" + if (records.size == 1) "" else "S",
                                        modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp
                                    )
                                }
                                items(records, key = { "faculty-${it.profileId}" }) { record ->
                                    FacultyRow(
                                        record = record,
                                        store = container.facultyPhotos,
                                        onClick = { onOpenFaculty(record.profileId) },
                                        onPhotoClick = { photoViewerFor = record }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Zoomable photo viewer — opened by tapping a teacher's avatar.
    photoViewerFor?.let { record ->
        FacultyPhotoViewer(
            store = container.facultyPhotos,
            url = record.imageUrl,
            name = prettyName(record.name),
            onDismiss = { photoViewerFor = null }
        )
    }
}

/** One teacher of the directory — the group header or chip carries the
 * department, so the subtitle pairs the designation with the email. The
 * avatar shows the teacher's website photo when one exists; tapping the
 * PHOTO (not the row) opens the zoomable viewer. */
@Composable
private fun FacultyRow(
    record: FacultyRecord,
    store: com.gndec.timetable.domain.FacultyPhotoStore,
    onClick: () -> Unit,
    onPhotoClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FacultyAvatar(
                store = store,
                url = record.imageUrl,
                name = record.name,
                size = 38.dp,
                onPhotoClick = if (record.imageUrl.isNotBlank()) onPhotoClick else null
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    prettyName(record.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        record.designation.takeIf { it.isNotBlank() },
                        record.emails.firstOrNull()
                    ).joinToString(" · ").ifBlank {
                        if (record.profileId.startsWith(ExtraTeacherIdPrefix)) "From the department timetable"
                        else "Faculty"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open faculty details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One department chip of the picker, with its teacher count. Ticks with
 * the app-wide haptic so picking a department feels like the other controls. */
@Composable
private fun DeptChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.clickable(onClick = { view.hapticTick(); onClick() })
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}


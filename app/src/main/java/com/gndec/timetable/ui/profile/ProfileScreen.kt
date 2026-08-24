package com.gndec.timetable.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.gndec.timetable.data.prefs.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.StudentDirectoryManager
import com.gndec.timetable.domain.StudentDirectoryRecord
import com.gndec.timetable.domain.StudentDirectoryResult
import com.gndec.timetable.domain.matchingStudents
import com.gndec.timetable.domain.studentDisplayName
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(container: AppContainer, onBack: () -> Unit, onOpenAttendance: () -> Unit) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedBranch = settings.branch.trim().uppercase()
    val hasSavedProfile = settings.studentName.isNotBlank() || settings.registrationNumber.isNotBlank() || settings.rollNumber.isNotBlank()
    var branch by remember(savedBranch) { mutableStateOf(savedBranch) }
    var directory by remember(savedBranch) { mutableStateOf<List<StudentDirectoryRecord>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lookupOpen by remember(hasSavedProfile) { mutableStateOf(!hasSavedProfile) }
    var manualMode by remember { mutableStateOf(false) }
    var manualName by remember(settings.studentName) { mutableStateOf(settings.studentName) }
    var manualCrn by remember(settings.rollNumber) { mutableStateOf(settings.rollNumber) }
    var manualRegistration by remember(settings.registrationNumber) { mutableStateOf(settings.registrationNumber) }
    var manualFather by remember(settings.fatherName) { mutableStateOf(settings.fatherName) }
    var manualMother by remember(settings.motherName) { mutableStateOf(settings.motherName) }
    var manualMentor by remember(settings.mentorName) { mutableStateOf(settings.mentorName) }
    var manualSection by remember(settings.studentSection) { mutableStateOf(settings.studentSection) }
    var manualSubsection by remember(settings.studentSubsection) { mutableStateOf(settings.studentSubsection) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadBranch(force: Boolean) {
        if (branch.isBlank()) return
        loading = true
        error = null
        when (val result = container.studentDirectoryManager.load(branch, force)) {
            is StudentDirectoryResult.Ready -> {
                directory = result.records
                loading = false
            }
            is StudentDirectoryResult.Failed -> {
                directory = result.cached
                loading = false
                error = result.reason
            }
        }
    }

    LaunchedEffect(branch, lookupOpen) {
        if (lookupOpen && branch.isNotBlank()) loadBranch(force = false)
    }

    fun choose(record: StudentDirectoryRecord) {
        scope.launch {
            container.keys.removeAttendanceSession()
            container.settings.saveStudentProfile(
                record.candidateName,
                record.crn,
                record.branch,
                record.registrationNumber,
                record.fatherName,
                record.motherName,
                record.mentorName,
                record.section,
                record.subsection,
                record.group,
                record.mentorMobile,
                record.venue,
                "gndec_permanent_pdf"
            )
            runCatching { container.refreshManager.changeGroup(record.subsection) }
            savedMessage = "Official details saved on this device"
            manualMode = false
            lookupOpen = false
        }
    }

    fun saveManual() {
        scope.launch {
            container.keys.removeAttendanceSession()
            container.settings.saveStudentProfile(manualName, manualCrn, branch, manualRegistration, manualFather, manualMother, manualMentor, manualSection, manualSubsection, manualSubsection, "", "", "manual")
            runCatching { if (manualSubsection.isNotBlank()) container.refreshManager.changeGroup(manualSubsection) }
            savedMessage = "Manual profile saved on this device"
            manualMode = false
            lookupOpen = false
        }
    }

    val matches = matchingStudents(directory, query)
    val displayName = settings.studentName.ifBlank { "Your profile" }
    val initials = displayName.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "S" }

    PremiumScreenBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { PremiumPageHeader("Profile", "Student identity", onBack = onBack) }
            item { ProfileHero(initials, displayName, settings.studentSubsection.ifBlank { settings.branch.ifBlank { "Add your branch" } }, settings.rollNumber.ifBlank { "CRN not added" }) }
            item { SourceStatus(settings.profileSource, loading, savedMessage) }

            if (hasSavedProfile && !lookupOpen) {
                item {
                    AttendanceProfileAction(onClick = onOpenAttendance)
                }
                item {
                    SavedProfileCard(
                        name = settings.studentName,
                        branch = settings.branch,
                        crn = settings.rollNumber,
                        registration = settings.registrationNumber,
                        section = settings.studentSection,
                        subsection = settings.studentSubsection,
                        studentGroup = settings.studentGroup,
                        father = settings.fatherName,
                        mother = settings.motherName,
                        mentor = settings.mentorName,
                        mentorMobile = settings.mentorMobile,
                        mentorVenue = settings.mentorVenue,
                        onCopyCrn = {
                            if (settings.rollNumber.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("CRN (Class Roll Number)", settings.rollNumber))
                                savedMessage = "CRN copied"
                            }
                        },
                        onCopyRegistration = {
                            if (settings.registrationNumber.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Registration number", settings.registrationNumber))
                                savedMessage = "Registration number copied"
                            }
                        },
                        onCopyMentorMobile = {
                            if (settings.mentorMobile.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Mentor mobile", settings.mentorMobile))
                                savedMessage = "Mentor mobile copied"
                            }
                        }
                    )
                }
                item {
                    ProfileActionRow(
                        text = "Want to search your name on the official record again?",
                        onClick = { lookupOpen = true; query = ""; error = null }
                    )
                }
            } else {
                item {
                    SectionTitle("Connect your official record", "Choose your branch, then search the bundled GNDEC 2026 permanent-section PDF.")
                }
                item {
                    BranchSelector(
                        branch,
                        onBranch = { branch = it; directory = emptyList(); query = ""; error = null; manualMode = false },
                        onRefresh = { scope.launch { loadBranch(force = true) } },
                        loading = loading
                    )
                }
                if (branch.isNotBlank()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            OutlinedTextField(query, { query = it; manualMode = false }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search your name") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, enabled = !loading)
                            Spacer(Modifier.height(8.dp))
                            if (matches.isNotEmpty() && !manualMode && query.trim().length >= 2) {
                                matches.take(12).forEach { record -> CandidateRow(record, matches, onClick = { choose(record) }) }
                            } else if (directory.isEmpty() && !loading) {
                                Text("Tap refresh to read the bundled permanent student list for $branch.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let { message -> item { ErrorCard(message, hasCache = directory.isNotEmpty()) } }
                item {
                    ProfileActionRow(text = "Enter your profile manually instead", onClick = { manualMode = true })
                }
            }

            if (manualMode) {
                item {
                    ManualFields(
                        name = manualName,
                        crn = manualCrn,
                        registration = manualRegistration,
                        father = manualFather,
                        mother = manualMother,
                        mentor = manualMentor,
                        section = manualSection,
                        subsection = manualSubsection,
                        onName = { manualName = it },
                        onCrn = { manualCrn = it },
                        onRegistration = { manualRegistration = it },
                        onFather = { manualFather = it },
                        onMother = { manualMother = it },
                        onMentor = { manualMentor = it },
                        onSection = { manualSection = it },
                        onSubsection = { manualSubsection = it },
                        onSave = ::saveManual
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedProfileCard(name: String, branch: String, crn: String, registration: String, section: String, subsection: String, studentGroup: String, father: String, mother: String, mentor: String, mentorMobile: String, mentorVenue: String, onCopyCrn: () -> Unit, onCopyRegistration: () -> Unit, onCopyMentorMobile: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("SAVED STUDENT DETAILS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(name.ifBlank { "Name not added" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Detail("Branch", branch.ifBlank { "Not added" })
            CopyableDetail("CRN (Class Roll Number)", crn.ifBlank { "Not added" }, onCopyCrn)
            if (registration.isNotBlank()) CopyableDetail("Registration number", registration, onCopyRegistration)
            Detail("Permanent section", listOf(section, subsection).filter { it.isNotBlank() }.joinToString("  · ").ifBlank { "Not added" })
            Detail("Mentoring group", studentGroup.ifBlank { "Not added" })
            Detail("Father name", father.ifBlank { "Not added" })
            Detail("Mother name", mother.ifBlank { "Not added" })
            Detail("Mentor", mentor.ifBlank { "Not added" })
            if (mentorMobile.isNotBlank()) CopyableDetail("Mentor mobile", mentorMobile, onCopyMentorMobile)
            if (mentorVenue.isNotBlank()) Detail("Mentor venue", mentorVenue)
        }
    }
}

@Composable
private fun ProfileActionRow(text: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ArrowForward, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ProfileHero(initials: String, name: String, group: String, registration: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(initials, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                Text(registration, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SourceStatus(source: String, loading: Boolean, saved: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(when {
            loading -> "Reading the official branch PDF…"
            saved != null -> saved
            source == "gndec_permanent_pdf" -> "Linked to GNDEC’s bundled permanent 2026 list"
            else -> "Manual profile details"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BranchSelector(branch: String, onBranch: (String) -> Unit, onRefresh: () -> Unit, loading: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StudentDirectoryManager.BRANCHES.take(4).forEach { value -> BranchChip(value, value == branch, onClick = { onBranch(value) }) }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StudentDirectoryManager.BRANCHES.drop(4).forEach { value -> BranchChip(value, value == branch, onClick = { onBranch(value) }) }
            OutlinedButton(onClick = onRefresh, enabled = branch.isNotBlank() && !loading, modifier = Modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 10.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Read list")
            }
        }
    }
}

@Composable
private fun BranchChip(text: String, selected: Boolean, onClick: () -> Unit) {
    SurfaceChip(text, selected, onClick)
}

@Composable
private fun SurfaceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(text, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun CandidateRow(record: StudentDirectoryRecord, matches: List<StudentDirectoryRecord>, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(studentDisplayName(record, matches), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${record.subsection}  ·  ${record.group}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OfficialRecordCard(record: StudentDirectoryRecord) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OFFICIAL RECORD", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(record.candidateName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Detail("CRN (Class Roll Number)", record.crn)
            if (record.registrationNumber.isNotBlank()) Detail("Registration number", record.registrationNumber)
            Detail("Permanent section", "${record.section}  ·  ${record.subsection}")
            Detail("Mentoring group", record.group)
            Detail("Father name", record.fatherName)
            Detail("Mother name", record.motherName)
            Detail("Mentor", record.mentorName)
            if (record.mentorMobile.isNotBlank()) Detail("Mentor mobile", record.mentorMobile)
            if (record.venue.isNotBlank()) Detail("Mentor venue", record.venue)
        }
    }
}

@Composable
private fun ErrorCard(message: String, hasCache: Boolean) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(if (hasCache) "Using saved branch list" else "Could not load branch list", fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManualFields(name: String, crn: String, registration: String, father: String, mother: String, mentor: String, section: String, subsection: String, onName: (String) -> Unit, onCrn: (String) -> Unit, onRegistration: (String) -> Unit, onFather: (String) -> Unit, onMother: (String) -> Unit, onMentor: (String) -> Unit, onSection: (String) -> Unit, onSubsection: (String) -> Unit, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manual details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Input("Full name", name, onName)
            Input("CRN (Class Roll Number)", crn, onCrn)
            Input("Registration number", registration, onRegistration)
            Input("Father name", father, onFather)
            Input("Mother name", mother, onMother)
            Input("Mentor name", mentor, onMentor)
            Input("Permanent section", section, onSection)
        Input("Permanent subsection / timetable group", subsection, onSubsection)
            Button(onClick = onSave, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Save manual profile", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Input(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) })
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CopyableDetail(label: String, value: String, onCopy: () -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (value != "Not added") {
                IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}


@Composable
private fun AttendanceProfileAction(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("View attendance", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Mark lectures and track your percentage", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Open attendance", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

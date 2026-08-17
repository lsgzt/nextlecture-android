package com.gndec.timetable.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.domain.StudentDirectoryRecord
import com.gndec.timetable.domain.StudentDirectoryResult
import com.gndec.timetable.domain.matchingStudents
import com.gndec.timetable.domain.studentDisplayName
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var branch by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<List<StudentDirectoryRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<StudentDirectoryRecord?>(null) }
    var nameQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualMode by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualRoll by remember { mutableStateOf("") }
    var manualRegistration by remember { mutableStateOf("") }
    var manualMentor by remember { mutableStateOf("") }
    var manualSection by remember { mutableStateOf("") }
    var manualSubsection by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun finishProfile(record: StudentDirectoryRecord?, source: String) {
        scope.launch {
            loading = true
            val name = record?.candidateName ?: manualName
            val roll = record?.srNo ?: manualRoll
            val registration = record?.registrationNumber ?: manualRegistration
            val mentor = record?.mentorName ?: manualMentor
            val section = record?.temporarySection ?: manualSection
            val subsection = record?.temporarySubsection ?: manualSubsection
            container.settings.saveStudentProfile(name, roll, branch, registration, mentor, section, subsection, source)
            if (subsection.isNotBlank()) {
                runCatching { container.refreshManager.changeGroup(subsection) }
            }
            loading = false
            step = 4
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    fun requestNotificationsAndFinish() {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 28.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                OnboardingTop(step)
            }
            when (step) {
                0 -> item {
                    IntroStep(onContinue = { step = 1 })
                }
                1 -> {
                    item {
                        BranchStep(
                            selectedBranch = branch,
                            onBranch = { branch = it; error = null },
                            loading = loading,
                            error = error,
                            onContinue = {
                                if (branch.isBlank()) return@BranchStep
                                scope.launch {
                                    loading = true
                                    error = null
                                    runCatching { container.refreshManager.refresh(force = true) }
                                    when (val result = container.studentDirectoryManager.load(branch)) {
                                        is StudentDirectoryResult.Ready -> {
                                            directory = result.records
                                            loading = false
                                            step = 2
                                        }
                                        is StudentDirectoryResult.Failed -> {
                                            directory = result.cached
                                            loading = false
                                            if (result.cached.isNotEmpty()) step = 2 else error = result.reason
                                        }
                                    }
                                }
                            },
                            onManual = { manualMode = true; step = 3 }
                        )
                    }
                }
                2 -> {
                    item {
                        NameLookupStep(
                            branch = branch,
                            query = nameQuery,
                            onQuery = { nameQuery = it },
                            records = directory,
                            onSelect = { selected = it; step = 3 },
                            onManual = { manualMode = true; step = 3 },
                            onBack = { step = 1 }
                        )
                    }
                }
                3 -> {
                    item {
                        if (manualMode) {
                            ManualProfileStep(
                                branch = branch,
                                name = manualName,
                                roll = manualRoll,
                                registration = manualRegistration,
                                mentor = manualMentor,
                                section = manualSection,
                                subsection = manualSubsection,
                                onName = { manualName = it },
                                onRoll = { manualRoll = it },
                                onRegistration = { manualRegistration = it },
                                onMentor = { manualMentor = it },
                                onSection = { manualSection = it },
                                onSubsection = { manualSubsection = it },
                                onSave = { finishProfile(null, "manual") },
                                onBack = { manualMode = false; step = 2 }
                            )
                        } else {
                            ConfirmProfileStep(selected, onConfirm = { finishProfile(selected, "gndec_pdf") }, onBack = { step = 2 })
                        }
                    }
                }
                4 -> item {
                    NotificationStep(onEnable = { requestNotificationsAndFinish() }, onSkip = onDone)
                }
            }
        }
    }
}

@Composable
private fun OnboardingTop(step: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text("NextLecture", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("GNDEC student setup", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { index ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

@Composable
private fun IntroStep(onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Default.CalendarMonth, 78.dp)
        Spacer(Modifier.height(20.dp))
        Text("Your timetable,\nproperly personalized", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Choose your branch and we’ll match your name with GNDEC’s official 2026 temporary-section list. No typing roll numbers by hand unless you need the fallback.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(26.dp))
        PrimaryAction("Get started", Icons.Default.ArrowForward, onClick = onContinue)
    }
}

@Composable
private fun BranchStep(selectedBranch: String, onBranch: (String) -> Unit, loading: Boolean, error: String?, onContinue: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Which branch are you in?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("We’ll download only that branch’s 2026 student PDF and keep a private copy on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        com.gndec.timetable.domain.StudentDirectoryManager.BRANCHES.forEach { branch ->
            Card(
                onClick = { onBranch(branch) },
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (branch == selectedBranch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (branch == selectedBranch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = if (branch == selectedBranch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(branch, style = MaterialTheme.typography.titleMedium, fontWeight = if (branch == selectedBranch) FontWeight.Bold else FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (branch == selectedBranch) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Fetching timetable and student list…")
            }
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction("Load ${selectedBranch.ifBlank { "branch" }} students", Icons.Default.CloudDone, enabled = selectedBranch.isNotBlank() && !loading, onClick = onContinue)
        TextButton(onClick = onManual) { Text("Enter profile manually instead") }
    }
}

@Composable
private fun NameLookupStep(branch: String, query: String, onQuery: (String) -> Unit, records: List<StudentDirectoryRecord>, onSelect: (StudentDirectoryRecord) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val matches = matchingStudents(records, query)
    Column(Modifier.fillMaxWidth()) {
        Text("Find your name", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("${records.size} students loaded for $branch. Start typing; duplicate names show their registration number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(13.dp))
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your full name") }, leadingIcon = { Icon(Icons.Default.PersonSearch, contentDescription = null) })
        Spacer(Modifier.height(9.dp))
        if (query.trim().length < 2) {
            HintCard("Type at least two letters to search the official list.")
        } else if (matches.isEmpty()) {
            HintCard("No matching name found. Check spelling or use manual entry below.")
        } else {
            matches.forEach { record ->
                Card(onClick = { onSelect(record) }, Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(studentDisplayName(record, matches), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${record.temporarySubsection}  ·  Sr. No. ${record.srNo}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onManual) { Text("Enter manually") }
        }
    }
}

@Composable
private fun ConfirmProfileStep(record: StudentDirectoryRecord?, onConfirm: () -> Unit, onBack: () -> Unit) {
    if (record == null) return
    Column(Modifier.fillMaxWidth()) {
        Text("Is this you?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("We’ll save these details locally and use ${record.temporarySubsection} as your timetable group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.candidateName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                DetailLine("Registration", record.registrationNumber)
                DetailLine("Roll number", record.srNo)
                DetailLine("Temporary section", "${record.temporarySection}  ·  ${record.temporarySubsection}")
                DetailLine("Mentor", record.mentorName)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryAction("Use these details", Icons.Default.ArrowForward, onClick = onConfirm)
        TextButton(onClick = onBack) { Text("Search again") }
    }
}

@Composable
private fun ManualProfileStep(branch: String, name: String, roll: String, registration: String, mentor: String, section: String, subsection: String, onName: (String) -> Unit, onRoll: (String) -> Unit, onRegistration: (String) -> Unit, onMentor: (String) -> Unit, onSection: (String) -> Unit, onSubsection: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Enter your details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Manual entry stays available if GNDEC’s PDF cannot be reached or your record needs correction.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ProfileInput("Full name", name, onName)
        ProfileInput("Roll number / Sr. No.", roll, onRoll)
        ProfileInput("Registration number", registration, onRegistration)
        ProfileInput("Mentor name", mentor, onMentor)
        ProfileInput("Temporary section", section, onSection)
        ProfileInput("Temporary subsection / timetable group", subsection, onSubsection)
        Spacer(Modifier.height(7.dp))
        PrimaryAction("Save profile", Icons.Default.ArrowForward, enabled = name.isNotBlank(), onClick = onSave)
        TextButton(onClick = onBack) { Text("Back to official search") }
    }
}

@Composable
private fun NotificationStep(onEnable: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Default.CloudDone, 72.dp)
        Spacer(Modifier.height(20.dp))
        Text("You’re ready", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Enable notifications so lecture reminders can reach you on time, even when the timetable app is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryAction("Enable notifications", Icons.Default.Refresh, onClick = onEnable)
        TextButton(onClick = onSkip) { Text("Skip for now") }
    }
}

@Composable
private fun ProfileInput(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true)
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HintCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), elevation = CardDefaults.cardElevation(0.dp)) {
        Text(text, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun PrimaryAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}


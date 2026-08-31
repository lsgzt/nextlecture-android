package com.gndec.timetable.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ViewWeek
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.GroupTimetableManager
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.domain.StudentDirectoryRecord
import com.gndec.timetable.domain.StudentDirectoryResult
import com.gndec.timetable.domain.matchingStudents
import com.gndec.timetable.domain.studentDisplayName
import com.gndec.timetable.parse.GroupMatcher
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.launch

/** Onboarding step indices — the flow branches on the chosen academic year. */
private const val STEP_INTRO = 0
private const val STEP_YEAR = 1
private const val STEP_BRANCH = 2
private const val STEP_LOOKUP = 3          // 1st year: official directory search / senior: manual details
private const val STEP_PROFILE = 4         // 1st year: confirm / senior: section + group picker
private const val STEP_NOTIFICATIONS = 5

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    var step by remember { mutableStateOf(STEP_INTRO) }
    var academicYear by remember { mutableStateOf(0) }
    var branch by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<List<StudentDirectoryRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<StudentDirectoryRecord?>(null) }
    var nameQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualMode by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualCrn by remember { mutableStateOf("") }
    var manualRegistration by remember { mutableStateOf("") }
    var manualFather by remember { mutableStateOf("") }
    var manualMother by remember { mutableStateOf("") }
    var manualMentor by remember { mutableStateOf("") }
    var manualSection by remember { mutableStateOf("") }
    var manualSubsection by remember { mutableStateOf("") }
    // Senior (2nd–4th year) group-picker state.
    var catalogGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogFromCache by remember { mutableStateOf(false) }
    var catalogLoading by remember { mutableStateOf(false) }
    var pickedSection by remember { mutableStateOf<String?>(null) }
    var pickedGroup by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun finishFirstYearProfile(record: StudentDirectoryRecord?, source: String) {
        scope.launch {
            loading = true
            val name = record?.candidateName ?: manualName
            val crn = record?.crn ?: manualCrn
            val registration = record?.registrationNumber ?: manualRegistration
            val father = record?.fatherName.orEmpty().ifBlank { manualFather }
            val mother = record?.motherName.orEmpty().ifBlank { manualMother }
            val mentor = record?.mentorName ?: manualMentor
            val section = record?.section ?: manualSection
            val subsection = record?.subsection ?: manualSubsection
            val studentGroup = record?.group ?: subsection
            val timetableGroup = subsection
            val mentorMobile = record?.mentorMobile.orEmpty()
            val mentorVenue = record?.venue.orEmpty()
            container.keys.removeAttendanceSession()
            container.settings.setAcademicYear(if (academicYear == 0) 1 else academicYear)
            container.settings.saveStudentProfile(name, crn, branch, registration, father, mother, mentor, section, subsection, studentGroup, mentorMobile, mentorVenue, source)
            if (timetableGroup.isNotBlank()) {
                runCatching { container.refreshManager.changeGroup(timetableGroup) }
            }
            loading = false
            step = STEP_NOTIFICATIONS
        }
    }

    /**
     * 2nd/3rd/4th year: manual identity + FET group chosen from the live
     * departmental timetable. The departmental document is fetched and stored
     * BEFORE the group switch so changeGroup finds cached lectures.
     *
     * Correctness rule: the app must NEVER enter the notifications step while
     * the displayed timetable still belongs to the 1st-year (appsc) source.
     * When the refresh fails we stay here with an honest error — the only
     * exception is when the picked group's lectures are ALREADY cached from an
     * earlier successful departmental refresh (offline tolerance with the
     * RIGHT data).
     */
    fun finishSeniorProfile() {
        scope.launch {
            loading = true
            error = null
            val group = pickedGroup.orEmpty().ifBlank { manualSubsection.trim() }
            if (group.isBlank()) {
                loading = false
                error = "Pick your section (and practical group if one is shown) before saving."
                return@launch
            }
            container.keys.removeAttendanceSession()
            container.settings.setAcademicYear(academicYear)
            container.settings.setBranch(branch)
            container.settings.saveStudentProfile(
                manualName, manualCrn, branch, manualRegistration,
                "", "", "",
                pickedSection.orEmpty(), group, "",
                "", "",
                "manual_departmental"
            )
            // Validate the PICKED group against the official document, not the
            // previously saved (possibly 1st-year) group.
            val refreshFailure = when (val result = container.refreshManager.refresh(force = true, expectedGroup = group)) {
                is RefreshResult.Failed -> result
                else -> null
            }
            // Link the picked group. After a failed refresh this only succeeds
            // when the group's lectures are already cached from an earlier
            // departmental refresh — never silently keep 1st-year data.
            val linked = runCatching { container.refreshManager.changeGroup(group) }.getOrDefault(false)
            when {
                linked -> {
                    loading = false
                    step = STEP_NOTIFICATIONS
                }
                refreshFailure != null -> {
                    loading = false
                    error = if (refreshFailure.hadCachedTimetable) {
                        "Saved your profile, but the official ${branch.uppercase()} timetable could not be " +
                            "downloaded just now, so your year's timetable is not on this device yet. " +
                            "Check your internet connection and tap Save profile again."
                    } else {
                        "${refreshFailure.reason} Your profile is saved — tap Save profile again once you are online."
                    }
                }
                else -> {
                    loading = false
                    error = "Saved your profile, but \"$group\" was not found in the downloaded official " +
                            "timetable. Tap Refresh to load the latest sections, pick your group again and save."
                }
            }
        }
    }

    /** Loads the department's live group catalog for the senior section picker. */
    fun loadCatalog(force: Boolean) {
        scope.launch {
            catalogLoading = true
            error = null
            when (val result = container.groupTimetableManager.load(branch, academicYear, force)) {
                is GroupTimetableManager.CatalogResult.Ready -> {
                    catalogGroups = result.groups
                    catalogFromCache = result.fromCache
                    catalogLoading = false
                }
                is GroupTimetableManager.CatalogResult.Failed -> {
                    catalogGroups = result.cached
                    catalogLoading = false
                    error = result.reason
                }
            }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch {
            container.settings.setNotificationPermissionPrompted(true)
            onDone()
        }
    }

    fun finishNotificationStep() {
        scope.launch {
            container.settings.setNotificationPermissionPrompted(true)
            onDone()
        }
    }

    fun requestNotificationsAndFinish() {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else finishNotificationStep()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 28.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "top") {
                OnboardingTop(step)
            }
            item(key = "step") {
                val stepIn = motionTween<Float>(Motion.Emphasized)
                val stepOut = motionTween<Float>(Motion.Normal)
                val slideSpec = motionTween<IntOffset>(Motion.Emphasized, Motion.EasingEnter)
                val slideOutSpec = motionTween<IntOffset>(Motion.Normal, Motion.EasingExit)
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        val forward = targetState >= initialState
                        if (forward) {
                            (slideInVertically(slideSpec) { it / 12 } + fadeIn(stepIn)) togetherWith
                                (fadeOut(stepOut) + slideOutVertically(slideOutSpec) { -it / 16 })
                        } else {
                            (slideInVertically(slideSpec) { -it / 12 } + fadeIn(stepIn)) togetherWith
                                (fadeOut(stepOut) + slideOutVertically(slideOutSpec) { it / 16 })
                        }
                    },
                    label = "onboardingStep"
                ) { currentStep ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (currentStep) {
                            STEP_INTRO -> IntroStep(onContinue = { step = STEP_YEAR })
                            STEP_YEAR -> YearStep(
                                selectedYear = academicYear,
                                onYear = { year ->
                                    academicYear = year
                                    scope.launch { container.settings.setAcademicYear(year) }
                                },
                                onContinue = { if (academicYear in 1..4) step = STEP_BRANCH }
                            )
                            STEP_BRANCH -> BranchStep(
                                selectedBranch = branch,
                                academicYear = academicYear,
                                selectedBranchForYear = academicYear in 1..4,
                                onBranch = { branch = it; error = null },
                                loading = loading || catalogLoading,
                                error = error,
                                onContinue = {
                                    if (branch.isBlank()) return@BranchStep
                                    if (academicYear <= 1) {
                                        scope.launch {
                                            loading = true
                                            error = null
                                            runCatching { container.refreshManager.refresh(force = true) }
                                            when (val result = container.studentDirectoryManager.load(branch)) {
                                                is StudentDirectoryResult.Ready -> {
                                                    directory = result.records
                                                    loading = false
                                                    step = STEP_LOOKUP
                                                }
                                                is StudentDirectoryResult.Failed -> {
                                                    directory = result.cached
                                                    loading = false
                                                    if (result.cached.isNotEmpty()) step = STEP_LOOKUP else error = result.reason
                                                }
                                            }
                                        }
                                    } else {
                                        // Advance immediately: the senior details step comes
                                        // first, and the group catalog loads in the background
                                        // while the user types their name. Without this the
                                        // loading spinner finished and the flow stayed stuck.
                                        loadCatalog(force = false)
                                        step = STEP_LOOKUP
                                    }
                                },
                                onManual = {
                                    if (academicYear <= 1) {
                                        manualMode = true; step = STEP_LOOKUP
                                    } else {
                                        loadCatalog(force = false); step = STEP_PROFILE
                                    }
                                }
                            )
                            STEP_LOOKUP -> {
                                if (academicYear >= 2) {
                                    SeniorManualStep(
                                        branch = branch,
                                        academicYear = academicYear,
                                        name = manualName,
                                        crn = manualCrn,
                                        registration = manualRegistration,
                                        onName = { manualName = it },
                                        onCrn = { manualCrn = it },
                                        onRegistration = { manualRegistration = it },
                                        onContinue = { step = STEP_PROFILE },
                                        onBack = { step = STEP_BRANCH }
                                    )
                                } else {
                                    NameLookupStep(
                                        branch = branch,
                                        query = nameQuery,
                                        onQuery = { nameQuery = it },
                                        records = directory,
                                        onSelect = { selected = it; step = STEP_PROFILE },
                                        onManual = { manualMode = true; step = STEP_PROFILE },
                                        onBack = { step = STEP_BRANCH }
                                    )
                                }
                            }
                            STEP_PROFILE -> {
                                if (academicYear >= 2) {
                                    GroupPickerStep(
                                        branch = branch,
                                        academicYear = academicYear,
                                        groups = catalogGroups,
                                        fromCache = catalogFromCache,
                                        loading = catalogLoading,
                                        error = error,
                                        pickedSection = pickedSection,
                                        pickedGroup = pickedGroup,
                                        onSection = { section ->
                                            pickedSection = section
                                            val options = GroupMatcher.groupsForSection(catalogGroups, branch, academicYear, section)
                                            pickedGroup = options.singleOrNull()
                                        },
                                        onGroup = { pickedGroup = it },
                                        onReload = { loadCatalog(force = true) },
                                        onManualGroup = { manualSubsection = it },
                                        manualGroupValue = manualSubsection,
                                        onSave = { finishSeniorProfile() },
                                        onBack = { step = STEP_LOOKUP }
                                    )
                                } else {
                                    val modeSwap = motionTween<Float>(Motion.Fast)
                                    AnimatedContent(
                                        targetState = manualMode,
                                        transitionSpec = { fadeIn(modeSwap) togetherWith fadeOut(modeSwap) },
                                        label = "profileMode"
                                    ) { manual ->
                                        if (manual) {
                                            ManualProfileStep(
                                                branch = branch,
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
                                                onSave = { finishFirstYearProfile(null, "manual") },
                                                onBack = { manualMode = false; step = STEP_LOOKUP }
                                            )
                                        } else {
                                            ConfirmProfileStep(selected, onConfirm = { finishFirstYearProfile(selected, "gndec_permanent_pdf") }, onBack = { step = STEP_LOOKUP })
                                        }
                                    }
                                }
                            }
                            STEP_NOTIFICATIONS -> NotificationStep(onEnable = { requestNotificationsAndFinish() }, onSkip = { finishNotificationStep() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionOnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch {
            container.settings.setNotificationPermissionPrompted(true)
            onDone()
        }
    }

    fun finish() {
        scope.launch {
            container.settings.setNotificationPermissionPrompted(true)
            onDone()
        }
    }

    NotificationStep(
        onEnable = {
            if (Build.VERSION.SDK_INT >= 33 && !com.gndec.timetable.domain.NotificationHelper.notificationsEnabled(container.context)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                finish()
            }
        },
        onSkip = { finish() }
    )
}

@Composable
private fun OnboardingTop(step: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text("NextLecture", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("GNDEC student setup", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { index ->
                val fill by animateColorAsState(
                    targetValue = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    animationSpec = motionTween(Motion.Normal),
                    label = "onboardingProgress"
                )
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(fill))
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
        Text("Tell us your year and branch. 1st years are matched with GNDEC's official permanent-section list; 2nd–4th years pick their section from their department's official timetable.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(26.dp))
        PrimaryAction("Get started", Icons.Default.ArrowForward, onClick = onContinue)
    }
}

@Composable
private fun YearStep(selectedYear: Int, onYear: (Int) -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Which year are you in?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Your year decides where your timetable comes from.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val descriptions = mapOf(
            1 to "Official permanent-section directory lookup",
            2 to "Departmental timetable · D2 groups",
            3 to "Departmental timetable · D3 groups",
            4 to "Departmental timetable · D4 groups"
        )
        (1..4).forEach { year ->
            val isSelected = year == selectedYear
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "yearContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "yearBorder"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = { onYear(year) },
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .pressFeedback(pressInteraction, pressedScale = 0.98f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("${year}${yearSuffix(year)} Year", style = MaterialTheme.typography.titleMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        Text(descriptions.getValue(year), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    if (isSelected) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryAction("Continue", Icons.Default.ArrowForward, enabled = selectedYear in 1..4, onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

private fun yearSuffix(year: Int): String = when (year) {
    1 -> "st"
    2 -> "nd"
    3 -> "rd"
    else -> "th"
}

@Composable
private fun BranchStep(
    selectedBranch: String,
    academicYear: Int,
    selectedBranchForYear: Boolean,
    onBranch: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onContinue: () -> Unit,
    onManual: () -> Unit
) {
    val isFirstYear = academicYear <= 1
    Column(Modifier.fillMaxWidth()) {
        Text("Which branch are you in?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            if (isFirstYear) {
                "We'll read that branch's official 2026 permanent student PDF bundled inside the app. Nothing is downloaded."
            } else {
                "We'll load your department's official current timetable and show the sections that actually exist."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        com.gndec.timetable.domain.StudentDirectoryManager.BRANCHES.forEach { branchName ->
            val isSelected = branchName == selectedBranch
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "branchContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "branchBorder"
            )
            val iconTint by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "branchIcon"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = { onBranch(branchName) },
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .pressFeedback(pressInteraction, pressedScale = 0.98f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(branchName, style = MaterialTheme.typography.titleMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (isSelected) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        AnimatedVisibility(
            visible = loading,
            enter = expandVertically(motionTween(Motion.Normal)) + fadeIn(motionTween(Motion.Normal)),
            exit = shrinkVertically(motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (isFirstYear) "Reading bundled permanent student list…" else "Loading the official $selectedBranch timetable…")
            }
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction(
            if (isFirstYear) "Read ${selectedBranch.ifBlank { "branch" }} students" else "Load ${selectedBranch.ifBlank { "branch" }} timetable",
            Icons.Default.CloudDone,
            enabled = selectedBranch.isNotBlank() && !loading,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onManual) {
            Text(if (isFirstYear) "Enter profile manually instead" else "Skip to section picker")
        }
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
                val pressInteraction = remember { MutableInteractionSource() }
                Card(
                    onClick = { onSelect(record) },
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).pressFeedback(pressInteraction, pressedScale = 0.98f),
                    interactionSource = pressInteraction,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text(studentDisplayName(record, matches), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${record.subsection}  ·  ${record.group}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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

/** 2nd/3rd/4th year: identity fields that actually matter — no first-year-only fields. */
@Composable
private fun SeniorManualStep(
    branch: String,
    academicYear: Int,
    name: String,
    crn: String,
    registration: String,
    onName: (String) -> Unit,
    onCrn: (String) -> Unit,
    onRegistration: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Your details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("${academicYear}${yearSuffix(academicYear)} year · $branch. Next you'll pick your section from the official departmental timetable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ProfileInput("Full name", name, onName)
        ProfileInput("CRN / roll number", crn, onCrn)
        ProfileInput("Registration number", registration, onRegistration)
        Spacer(Modifier.height(7.dp))
        PrimaryAction("Continue to section picker", Icons.Default.ArrowForward, enabled = name.isNotBlank(), onClick = onContinue, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * 2nd/3rd/4th year group selection. Shows the sections that ACTUALLY exist in
 * the current official timetable; practical subgroups are exposed only when a
 * section maps to more than one FET group (e.g. ECE D4 → D4ECA1/A2/A3).
 */
@Composable
private fun GroupPickerStep(
    branch: String,
    academicYear: Int,
    groups: List<String>,
    fromCache: Boolean,
    loading: Boolean,
    error: String?,
    pickedSection: String?,
    pickedGroup: String?,
    onSection: (String) -> Unit,
    onGroup: (String) -> Unit,
    onReload: () -> Unit,
    onManualGroup: (String) -> Unit,
    manualGroupValue: String,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Which section are you in?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            when {
                loading -> "Loading the official ${academicYear}${yearSuffix(academicYear)}-year $branch groups…"
                groups.isEmpty() -> "The official group list could not be loaded."
                else -> "Live groups for ${academicYear}${yearSuffix(academicYear)}-year $branch, straight from the official departmental timetable."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (fromCache && groups.isNotEmpty()) {
            Text("Showing the last saved list (offline). Refresh when you are back online.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Fetching official timetable groups…")
                }
            }
            groups.isEmpty() -> {
                HintCard("We could not reach the departmental timetable. Retry, or type your timetable group exactly as printed (for example D2 CS A).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(manualGroupValue, onManualGroup, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Timetable group (e.g. D2 CS A)") })
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) { Text("Back") }
                    // Name is optional for seniors — never block saving the group on it.
                    PrimaryAction("Save profile", Icons.Default.ArrowForward, enabled = manualGroupValue.isNotBlank(), onClick = onSave, modifier = Modifier.weight(1f))
                }
            }
            else -> {
                val sections = GroupMatcher.sectionsForYear(groups, branch, academicYear)
                if (sections.isEmpty()) {
                    HintCard("No $branch groups for year $academicYear were found in the current official timetable. If the department has not published it yet, check back later.")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onBack) { Text("Back") }
                        TextButton(onClick = onReload) { Text("Retry") }
                    }
                } else {
                    ChipGrid(sections.map { GroupMatcher.sectionLabel(it) }, pickedSection?.let { GroupMatcher.sectionLabel(it) }) { label ->
                        sections.firstOrNull { GroupMatcher.sectionLabel(it) == label }?.let(onSection)
                    }
                    val sectionGroups = pickedSection?.let { GroupMatcher.groupsForSection(groups, branch, academicYear, it) }.orEmpty()
                    if (sectionGroups.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text("Which practical group?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        ChipGrid(sectionGroups, pickedGroup) { onGroup(it) }
                        if (pickedSection != null && pickedGroup == null) {
                            Text("Pick your practical group to finish saving.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    pickedGroup?.let { group ->
                        Spacer(Modifier.height(10.dp))
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ViewWeek, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Your timetable group", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                                    Text(group, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("Back") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onReload, enabled = !loading) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Refresh")
                            }
                            // Name is optional for seniors — the group is what drives the
                            // timetable, reminders and vacant-room-free correctness.
                            PrimaryAction("Save profile", Icons.Default.ArrowForward, enabled = pickedGroup != null, onClick = onSave)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmProfileStep(record: StudentDirectoryRecord?, onConfirm: () -> Unit, onBack: () -> Unit) {
    if (record == null) return
    Column(Modifier.fillMaxWidth()) {
        Text("Is this you?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("We'll save these permanent details locally and use ${record.subsection} as your timetable group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.candidateName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                DetailLine("CRN (Class Roll Number)", record.crn)
                if (record.registrationNumber.isNotBlank()) DetailLine("Registration number", record.registrationNumber)
                DetailLine("Permanent section", "${record.section}  ·  ${record.subsection}")
                DetailLine("Mentoring group", record.group)
                DetailLine("Father name", record.fatherName)
                DetailLine("Mother name", record.motherName)
                DetailLine("Mentor", record.mentorName)
                if (record.mentorMobile.isNotBlank()) DetailLine("Mentor mobile", record.mentorMobile)
                if (record.venue.isNotBlank()) DetailLine("Mentor venue", record.venue)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryAction("Use these details", Icons.Default.ArrowForward, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onBack) { Text("Search again") }
    }
}

@Composable
private fun ManualProfileStep(branch: String, name: String, crn: String, registration: String, father: String, mother: String, mentor: String, section: String, subsection: String, onName: (String) -> Unit, onCrn: (String) -> Unit, onRegistration: (String) -> Unit, onFather: (String) -> Unit, onMother: (String) -> Unit, onMentor: (String) -> Unit, onSection: (String) -> Unit, onSubsection: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Enter your details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Manual entry stays available if your record needs correction.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ProfileInput("Full name", name, onName)
        ProfileInput("CRN (Class Roll Number)", crn, onCrn)
        ProfileInput("Registration number", registration, onRegistration)
        ProfileInput("Father name", father, onFather)
        ProfileInput("Mother name", mother, onMother)
        ProfileInput("Mentor name", mentor, onMentor)
        ProfileInput("Permanent section", section, onSection)
        ProfileInput("Permanent subsection / timetable group", subsection, onSubsection)
        Spacer(Modifier.height(7.dp))
        PrimaryAction("Save profile", Icons.Default.ArrowForward, enabled = name.isNotBlank(), onClick = onSave, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onBack) { Text("Back to official search") }
    }
}

@Composable
private fun NotificationStep(onEnable: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Default.Notifications, 72.dp)
        Spacer(Modifier.height(18.dp))
        Text("Never miss a lecture", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Get timely notifications about your lectures so you never miss them, even when the timetable app is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(22.dp))
        PrimaryAction("Enable notifications", Icons.Default.Notifications, onClick = onEnable)
        TextButton(onClick = onSkip) { Text("Skip for now") }
    }
}

@Composable
private fun ChipGrid(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(4).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    val isSelected = option == selected
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        animationSpec = motionTween(Motion.Normal),
                        label = "chipContainer"
                    )
                    val pressInteraction = remember { MutableInteractionSource() }
                    Card(
                        onClick = { onSelect(option) },
                        Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                        interactionSource = pressInteraction,
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = containerColor),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Text(
                            option,
                            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            textAlign = TextAlign.Center,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
                repeat(4 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
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
private fun PrimaryAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.widthIn(min = 248.dp)
) {
    val pressInteraction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp).pressFeedback(pressInteraction, pressedScale = 0.97f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A6A66), contentColor = Color.White, disabledContainerColor = Color(0xFF244746), disabledContentColor = Color.White.copy(alpha = 0.72f))
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(9.dp))
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

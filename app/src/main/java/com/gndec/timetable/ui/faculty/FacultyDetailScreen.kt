package com.gndec.timetable.ui.faculty

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ExtraTeacherIdPrefix
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.domain.FacultySchedule
import com.gndec.timetable.domain.FacultyStatus
import com.gndec.timetable.domain.FacultyTimetableManager
import com.gndec.timetable.domain.FacultyTimetableResult
import com.gndec.timetable.domain.isIntercom
import com.gndec.timetable.parse.FacultySlot
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.PillTone
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.StatusPill
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** Which half of the schedule the detail page shows. Order matters: it drives the slide direction. */
private enum class ScheduleTab(val title: String) { TODAY("Today"), WEEK("Full week") }

/**
 * Faculty profile + live weekly schedule.
 *
 * Contact details (name, designation, email, phones, department) come from the
 * bundled directory and are ALWAYS shown, including for teachers whose
 * department publishes no timetable — those get the honest "This teacher's
 * timetable is not available in our database" note instead of a wrong schedule.
 * Teachers that appear ONLY inside a department timetable ("tt-…" profile ids)
 * have no directory record to draw from: the page shows their schedule plus
 * "This Teacher's information is not available in our database".
 * The timetable itself is discovered live from the department website (never
 * hardcoded), so weekly revisions appear without an app update. The schedule
 * area is a Today / Full week segmented control that slides directionally —
 * the same motion as the Today screen's tab switches.
 */
@Composable
fun FacultyDetailScreen(
    container: AppContainer,
    profileId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    // Prefer an already-warmed directory hit (no parse). Never call byProfileId
    // on first composition — that blocked the enter animation while JSON/disk
    // work ran on Main.
    var faculty by remember(profileId) {
        mutableStateOf(container.facultyDirectory.byProfileIdIfLoaded(profileId))
    }
    var resolvingFaculty by remember(profileId) { mutableStateOf(faculty == null) }
    val isExtraTeacher = profileId.startsWith(ExtraTeacherIdPrefix)

    LaunchedEffect(profileId) {
        if (faculty != null) {
            resolvingFaculty = false
        } else {
            resolvingFaculty = true
            faculty = withContext(Dispatchers.Default) {
                container.facultyDirectory.byProfileId(profileId)
                    ?: if (isExtraTeacher) {
                        container.facultyTimetables.extraTeacherById(profileId)?.record
                    } else null
            }
            resolvingFaculty = false
        }
    }

    // Non-null while the zoomable photo viewer is open: the teacher's image
    // URL paired with their display name.
    var photoViewerFor by remember(profileId) { mutableStateOf<Pair<String, String>?>(null) }

    var result by remember(profileId) {
        mutableStateOf<FacultyTimetableResult?>(container.facultyTimetables.cachedResult(profileId))
    }
    // Cached Ready schedules paint on the first frame; otherwise the schedule
    // area shows a loading card while Main stays free for the enter transition.
    var loading by remember(profileId) { mutableStateOf(result == null) }
    var refreshTick by remember(profileId) { mutableStateOf(0) }
    var tabName by rememberSaveable(profileId) { mutableStateOf(ScheduleTab.TODAY.name) }
    val tab = ScheduleTab.valueOf(tabName)
    val reduced = LocalReducedMotion.current

    LaunchedEffect(faculty, refreshTick) {
        val teacher = faculty ?: return@LaunchedEffect
        // Keep a cached Ready schedule on screen while a forced refresh runs;
        // only show the loading card when we have nothing to display yet.
        if (result == null || refreshTick > 0) {
            if (result == null) loading = true
        }
        result = runCatching { container.facultyTimetables.load(teacher, forceRefresh = refreshTick > 0) }
            .getOrElse { FacultyTimetableResult.Unavailable(it.message ?: "Something went wrong") }
        loading = false
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            if (faculty == null) {
                ScreenSurface {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(bottom = 22.dp + PremiumBottomBarContentClearance)
                    ) {
                        item { Header(title = "Faculty details", onBack = onBack, onSettings = onOpenSettings) }
                        item {
                            if (resolvingFaculty || isExtraTeacher) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            if (isExtraTeacher) "Looking up this teacher in the department timetable…"
                                            else "Loading faculty profile…"
                                        )
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Column(Modifier.padding(22.dp)) {
                                        Text("Teacher not found", style = MaterialTheme.typography.titleLarge)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "This teacher is no longer in the college directory.",
                                            color = GndecMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                return@PremiumScreenBackground
            }
            val record = faculty ?: return@PremiumScreenBackground

            val schedule = (result as? FacultyTimetableResult.Ready)?.schedule
            val today = remember { LocalDate.now() }
            val nowMinutes = remember { LocalTime.now().let { it.hour * 60 + it.minute } }
            val status = remember(schedule, today, nowMinutes) {
                schedule?.let { FacultyTimetableManager.statusAt(it, today, nowMinutes) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 26.dp + PremiumBottomBarContentClearance
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Header(title = "Faculty details", onBack = onBack, onSettings = onOpenSettings, modifier = Modifier.padding(top = 8.dp))
                }

                // Identity — the teacher's website photo when the directory
                // carries one (tap to open the zoomable viewer); the plain
                // person badge for everyone else.
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val photoRecord = record
                        if (photoRecord.imageUrl.isNotBlank()) {
                            FacultyAvatar(
                                store = container.facultyPhotos,
                                url = photoRecord.imageUrl,
                                name = photoRecord.name,
                                size = 72.dp,
                                onPhotoClick = { photoViewerFor = photoRecord.imageUrl to prettyName(photoRecord.name) }
                            )
                        } else {
                            IconBadge(
                                Icons.Default.Person,
                                containerColor = MaterialTheme.colorScheme.primary,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                size = 72.dp
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                prettyName(record.name),
                                style = MaterialTheme.typography.headlineSmall,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2
                            )
                            if (record.designation.isNotBlank()) {
                                Text(record.designation, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                record.department,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Contact details — always available, straight from the directory.
                // Timetable-only teachers have no directory record: say so plainly
                // instead of an empty card.
                if (isExtraTeacher) {
                    item { TeacherInfoUnavailableCard() }
                } else if (record.contactable || record.profileUrl.isNotBlank()) {
                    item { ContactCard(record) }
                }

                // RAI note — shown ONLY for ME department teachers, whose
                // department timetable also carries the RAI faculty
                if (record.department == "Mechanical Engineering") {
                    item { RaiNoteCard() }
                }

                // Timetable
                item {
                    when {
                        loading -> TimetableLoadingCard(record, fromCollegeSite = isExtraTeacher)
                        result is FacultyTimetableResult.NotPublished -> TimetableUnavailableCard(
                            if (isExtraTeacher) "This teacher's timetable is no longer in the published file"
                            else "This teacher's timetable is not available in our database",
                            record.department,
                            contactDetailsAbove = !isExtraTeacher
                        )
                        result is FacultyTimetableResult.Unavailable -> TimetableErrorCard(
                            (result as FacultyTimetableResult.Unavailable).reason
                        ) { refreshTick++ }
                        schedule != null -> ScheduleHeaderCard(schedule, status)
                        else -> TimetableUnavailableCard(
                            "This teacher's timetable is not available in our database",
                            record.department,
                            contactDetailsAbove = !isExtraTeacher
                        )
                    }
                }

                // Today's classes / full week behind one segmented control.
                if (schedule != null && !loading) {
                    val todayDow = today.dayOfWeek.value - 1
                    item(key = "schedule-tab-selector") {
                        ScheduleTabSelector(tab) { tabName = it.name }
                    }
                    item(key = "schedule-tab-content") {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                if (reduced) {
                                    fadeIn(tween(Motion.Normal, easing = Motion.EasingStandard)) togetherWith
                                        fadeOut(tween(Motion.Fast, easing = Motion.EasingStandard))
                                } else {
                                    // Segmented-control motion — same as the Today screen's
                                    // today/tomorrow switch: content slides toward the
                                    // direction of the newly selected tab.
                                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                    (slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 12 } +
                                        fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                        (slideOutHorizontally(tween(Motion.Normal, easing = Motion.EasingExit)) { -dir * it / 14 } +
                                            fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)))
                                }
                            },
                            label = "scheduleTabContent"
                        ) { current ->
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (current) {
                                    ScheduleTab.TODAY -> {
                                        Text(
                                            "TODAY · ${Formatters.dayName(today.dayOfWeek.value).uppercase()}",
                                            modifier = Modifier.padding(start = 24.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        )
                                        val todaySlots = schedule.slotsForDay(todayDow)
                                        if (todaySlots.isEmpty()) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Text(
                                                    "No classes listed for today",
                                                    modifier = Modifier.padding(16.dp),
                                                    color = GndecMuted
                                                )
                                            }
                                        } else {
                                            val liveKey = (status as? FacultyStatus.Busy)?.slot?.let { "${it.day}-${it.startMinutes}" }
                                            todaySlots.forEach { slot ->
                                                SlotCard(
                                                    slot = slot,
                                                    live = liveKey == "${slot.day}-${slot.startMinutes}"
                                                )
                                            }
                                        }
                                    }
                                    ScheduleTab.WEEK -> {
                                        Text(
                                            "FULL WEEK",
                                            modifier = Modifier.padding(start = 24.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        )
                                        val weekDays = (0..6).filter { d -> schedule.slotsForDay(d).isNotEmpty() }
                                        if (weekDays.isEmpty()) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                            ) {
                                                Text(
                                                    "No classes listed in this teacher's week",
                                                    modifier = Modifier.padding(16.dp),
                                                    color = GndecMuted
                                                )
                                            }
                                        }
                                        weekDays.forEach { day ->
                                            WeekDayCard(day, schedule.slotsForDay(day), todayDow)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Zoomable photo viewer — opened by tapping the teacher's avatar above.
    photoViewerFor?.let { (url, name) ->
        FacultyPhotoViewer(
            store = container.facultyPhotos,
            url = url,
            name = name,
            onDismiss = { photoViewerFor = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/**
 * Two-option segmented control for the schedule area, styled like the Today
 * screen's mode selector. Selecting the active tab again does nothing; the
 * content switch itself animates in the [FacultyDetailScreen] body.
 */
@Composable
private fun ScheduleTabSelector(tab: ScheduleTab, onSelect: (ScheduleTab) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        ScheduleTab.entries.forEach { item ->
            val active = item == tab
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "scheduleTabContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "scheduleTabBorder"
            )
            val textColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "scheduleTabText"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = { view.hapticTick(); onSelect(item) },
                modifier = Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Text(
                    item.title,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 11.dp),
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ContactCard(faculty: FacultyRecord) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            // The college publishes several addresses for some teachers, and
            // a few teachers carry two numbers - one row per published value.
            faculty.emails.forEachIndexed { index, address ->
                ContactRow(
                    Icons.Default.Email, address,
                    if (index == 0) "Email" else "Other email",
                    copyValue = address
                ) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))
                    }
                }
            }
            faculty.officeNumbers.forEachIndexed { index, number ->
                if (number.isIntercom) {
                    // An intercom is a desk on the college's own exchange: it
                    // only works through the switchboard, so there is nothing
                    // to dial - the row is for reading and copying.
                    val extension = number.filter { it.isDigit() }
                    ContactRow(
                        Icons.Default.Phone, "Extension $extension",
                        "Via the college switchboard", copyValue = "Extension $extension"
                    )
                } else {
                    ContactRow(
                        Icons.Default.Phone, number,
                        if (index == 0) "Office phone" else "Other office phone",
                        copyValue = number
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}"))
                            )
                        }
                    }
                }
            }
            faculty.mobileNumbers.forEachIndexed { index, number ->
                ContactRow(
                    Icons.Default.Phone, number,
                    if (index == 0) "Mobile" else "Other mobile",
                    copyValue = number
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}"))
                        )
                    }
                }
            }
            if (faculty.profileUrl.isNotBlank()) {
                // No copy control — the useful action is opening the page.
                ContactRow(Icons.Default.School, "Faculty profile", "College website", copyValue = null) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(faculty.profileUrl))) }
                }
            }
        }
    }
}

/**
 * One contact line. The row itself opens the natural action (mail / dial /
 * browse); a dedicated copy button beside the value copies the raw text with
 * the app's haptic tick and flips to a check mark for ~1.2s as feedback.
 */
@Composable
private fun ContactRow(
    icon: ImageVector,
    text: String,
    label: String,
    copyValue: String? = null,
    onClick: (() -> Unit)? = null
) {
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Column(
        // A row without an action (an intercom cannot be dialled) is not
        // clickable - the copy button beside it still is.
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = GndecMuted)
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            if (copyValue != null) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy $label",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            view.hapticTick()
                            clipboard.setText(AnnotatedString(copyValue))
                            copied = true
                        }
                        .padding(5.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun RaiNoteCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "RAI department teachers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The RAI department does not publish its own teacher timetable — RAI faculty schedules are published under the ME department's timetable, which also serves this teacher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * The identity note for a teacher that appears only in the department
 * timetables: the college website's faculty directory (the source of the
 * app's bundled directory) lists no details for them, so the page answers
 * with the schedule plus this honest line.
 */
@Composable
private fun TeacherInfoUnavailableCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "This Teacher's information is not available in our database",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "We checked the college website's faculty directory, but no details are published for this teacher. Their weekly timetable below is the only information available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun TimetableLoadingCard(faculty: FacultyRecord, fromCollegeSite: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                if (fromCollegeSite) "Fetching ${prettyName(faculty.name)}'s timetable from the college website…"
                else "Fetching ${prettyName(faculty.name)}'s timetable from the ${faculty.department} website…"
            )
        }
    }
}

@Composable
private fun TimetableUnavailableCard(message: String, department: String, contactDetailsAbove: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Schedule, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (contactDetailsAbove) {
                    "The contact details above are still current. Some departments do not publish a weekly teacher timetable, and teachers visiting from other departments may not be listed in the $department file."
                } else {
                    "No directory entry and no published table were found for this teacher in the $department file. Try the refresh button on the source card once the college republishes its timetables."
                },
                color = GndecMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TimetableErrorCard(reason: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Refresh, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("Couldn't load the timetable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(reason, color = GndecMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            TealOutlineButton(text = "Try again", icon = Icons.Default.Refresh, onClick = onRetry)
        }
    }
}

@Composable
private fun ScheduleHeaderCard(schedule: FacultySchedule, status: FacultyStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            when (status) {
                is FacultyStatus.Busy -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("BUSY RIGHT NOW", tone = PillTone.ORANGE)
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoLine(Icons.Default.LocationOn, status.slot.room ?: "Room not listed")
                    InfoLine(Icons.Default.School, status.slot.subject ?: "Class")
                    status.slot.studentsSet?.let { InfoLine(Icons.Default.Groups, it) }
                    InfoLine(Icons.Default.AccessTime, "Until ${Formatters.hm(status.untilMinutes)}")
                }
                is FacultyStatus.FreeWithNextClass -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("FREE RIGHT NOW", tone = PillTone.GREEN)
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoLine(Icons.Default.EventAvailable, "Next class at ${Formatters.hm(status.slot.startMinutes)}")
                    status.slot.room?.let { InfoLine(Icons.Default.LocationOn, it) }
                    status.slot.subject?.let { InfoLine(Icons.Default.School, it) }
                }
                FacultyStatus.FreeRestOfDay -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("FREE FOR THE REST OF TODAY", tone = PillTone.GREEN)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("No more classes listed for today.", color = GndecMuted, style = MaterialTheme.typography.bodySmall)
                }
                FacultyStatus.NoClassesToday -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("NO CLASSES TODAY", tone = PillTone.TEAL)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing is listed for today in this teacher's week.", color = GndecMuted, style = MaterialTheme.typography.bodySmall)
                }
                FacultyStatus.Weekend -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("IT'S THE WEEKEND", tone = PillTone.GRAY)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("The weekly timetable below shows this teacher's regular schedule.", color = GndecMuted, style = MaterialTheme.typography.bodySmall)
                }
                null -> Unit
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            val generated = schedule.generatedAtMillis
            Text(
                buildString {
                    append("Live from the ${schedule.sourceRootId.uppercase()} department website")
                    if (generated != null) append(" · timetable generated ${Formatters.dateTime(generated)}")
                },
                color = GndecMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SlotCard(slot: FacultySlot, live: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(86.dp)) {
                Text(
                    Formatters.hm(slot.startMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    Formatters.hm(slot.endMinutes),
                    color = GndecMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    slot.subject ?: "Class",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                slot.room?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = GndecMuted)
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = GndecMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                slot.studentsSet?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(14.dp), tint = GndecMuted)
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = GndecMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (live) {
                    Text(
                        "IN PROGRESS",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekDayCard(day: Int, slots: List<FacultySlot>, todayDow: Int) {
    val isToday = day == todayDow
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Work, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    dayLabel(day),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isToday) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill("TODAY", tone = PillTone.TEAL)
                }
            }
            Spacer(Modifier.height(10.dp))
            slots.forEachIndexed { index, slot ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Formatters.hm(slot.startMinutes)}–${Formatters.hm(slot.endMinutes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(120.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            slot.subject ?: "Class",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val details = listOfNotNull(slot.room, slot.studentsSet).joinToString(" · ")
                        if (details.isNotEmpty()) {
                            Text(details, color = GndecMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (index < slots.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun dayLabel(day: Int): String = when (day) {
    0 -> "Monday"; 1 -> "Tuesday"; 2 -> "Wednesday"; 3 -> "Thursday"
    4 -> "Friday"; 5 -> "Saturday"; 6 -> "Sunday"; else -> "Day $day"
}

/** "DR. HARPREET KAUR GREWAL" → "Dr. Harpreet Kaur Grewal". */
internal fun prettyName(name: String): String =
    name.lowercase().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            val cleaned = word.trim('.', ',')
            when {
                cleaned.equals("dr", true) -> "Dr."
                cleaned.equals("er", true) -> "Er."
                cleaned.equals("prof", true) || cleaned.equals("professor", true) -> "Prof."
                cleaned.equals("pf", true) -> "Pf."
                cleaned.equals("ms", true) -> "Ms."
                cleaned.equals("mrs", true) -> "Mrs."
                cleaned.equals("mr", true) -> "Mr."
                cleaned.length <= 2 -> cleaned.uppercase()
                else -> cleaned.replaceFirstChar { it.uppercase() }
            }
        }

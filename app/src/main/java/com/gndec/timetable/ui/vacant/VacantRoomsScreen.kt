package com.gndec.timetable.ui.vacant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.VacantRoomsManager
import com.gndec.timetable.domain.RoomMerger
import com.gndec.timetable.domain.VacantRoomsState
import com.gndec.timetable.parse.MergedRoom
import com.gndec.timetable.parse.RoomCell
import com.gndec.timetable.parse.RoomNameNormalizer
import com.gndec.timetable.ui.theme.GndecGreen
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.premiumAquaBrush
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * "Find vacant rooms" — live room availability from the college's weekly
 * room time table. Shows which rooms are free right now and lets the user
 * explore any weekday / teaching slot combination.
 */
@Composable
fun VacantRoomsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm = remember { VacantRoomsViewModel(container) }
    LaunchedEffect(Unit) { vm.open() }
    val state by vm.state.collectAsStateWithLifecycle()

    // Selection + filter state survives configuration changes and process restore.
    var dayIndex by rememberSaveable { mutableIntStateOf(-1) }
    var slotIndex by rememberSaveable { mutableIntStateOf(-1) }
    var vacantOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedRoom by remember { mutableStateOf<String?>(null) }

    // Gentle ticker so "RIGHT NOW" styling and counts stay truthful while open.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
            is VacantRoomsState.Loading -> VacantLoading()
            is VacantRoomsState.Error -> VacantLoadError(message = current.message, onRetry = vm::forceRefresh)
            is VacantRoomsState.Ready -> {
                val data = current.data

                // First data arrival: seed the day/slot selection with "now".
                LaunchedEffect(data.fetchedAtMillis) {
                    if (dayIndex < 0) dayIndex = VacantRoomsViewModel.defaultDay()
                    if (slotIndex < 0) slotIndex = VacantRoomsViewModel.defaultSlot(data)
                }

                val safeDay = dayIndex.coerceIn(0, (data.days.size - 1).coerceAtLeast(0))
                val safeSlot = slotIndex.coerceIn(0, (data.slotStarts.size - 1).coerceAtLeast(0))
                val zone = ZoneId.systemDefault()
                val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
                val nowMinutes = now.hour * 60 + now.minute
                val todayIsTeachingDay = VacantRoomsManager.isToday(safeDay, now.toLocalDate())
                val selectedSlotStart = data.slotStarts.getOrElse(safeSlot) { 0 }
                val selectedSlotIsNow = todayIsTeachingDay &&
                    VacantRoomsManager.isCurrentSlot(selectedSlotStart, nowMinutes)

                // null cell = no department publishes this room for this slot —
                // it must never be shown as vacant.
                val cellOf = { room: MergedRoom ->
                    room.occupancy.getOrNull(safeDay)?.getOrNull(safeSlot)
                }
                val freeCount = data.rooms.count { cellOf(it)?.isFree == true }
                val noDataCount = data.rooms.count { cellOf(it) == null }

                val trimmed = query.trim()
                val filteredRooms = data.rooms
                    .filter { trimmed.isEmpty() || RoomNameNormalizer.matches(it.name, trimmed) }
                    .filter { !vacantOnly || cellOf(it)?.isFree == true }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "header") {
                        VacantHeader(
                            refreshing = current.refreshing,
                            onBack = onBack,
                            onRefresh = vm::forceRefresh,
                            modifier = Modifier.itemEntrance(0)
                        )
                    }
                    item(key = "hero") {
                        VacantHeroCard(
                            freeCount = freeCount,
                            totalRooms = data.rooms.size,
                            noDataCount = noDataCount,
                            dayLabel = data.days.getOrElse(safeDay) { "" },
                            timeRange = Formatters.range(
                                selectedSlotStart,
                                VacantRoomsManager.slotEndMinutes(selectedSlotStart)
                            ),
                            isNow = selectedSlotIsNow,
                            weekendNote = !todayIsTeachingDay,
                            modifier = Modifier.itemEntrance(1).padding(horizontal = 20.dp)
                        )
                    }
                    item(key = "day-chips") {
                        Column(Modifier.itemEntrance(2)) {
                            SectionLabel("DAY")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(data.days.size) { i ->
                                    VacantChip(
                                        label = data.days[i],
                                        selected = i == safeDay,
                                        onClick = {
                                            expandedRoom = null
                                            dayIndex = i
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item(key = "slot-chips") {
                        Column(Modifier.itemEntrance(3)) {
                            SectionLabel("TIME")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(data.slotStarts.size) { i ->
                                    val slot = data.slotStarts[i]
                                    VacantChip(
                                        label = Formatters.range(
                                            slot,
                                            VacantRoomsManager.slotEndMinutes(slot)
                                        ),
                                        selected = i == safeSlot,
                                        badgeNow = todayIsTeachingDay &&
                                            VacantRoomsManager.isCurrentSlot(slot, nowMinutes),
                                        onClick = {
                                            expandedRoom = null
                                            slotIndex = i
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item(key = "filters") {
                        Column(Modifier.itemEntrance(4)) {
                            VacantFilterToggle(
                                vacantOnly = vacantOnly,
                                onChange = { vacantOnly = it; expandedRoom = null }
                            )
                            Spacer(Modifier.height(10.dp))
                            VacantSearchField(
                                query = query,
                                onQueryChange = { query = it }
                            )
                        }
                    }
                    item(key = "refresh-error") {
                        AnimatedVisibility(
                            visible = current.error != null,
                            enter = expandVertically(motionTween(Motion.Emphasized)) + fadeIn(motionTween(Motion.Emphasized)),
                            exit = shrinkVertically(motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
                        ) {
                            VacantErrorBanner(
                                text = "Couldn’t refresh — showing saved data · ${Formatters.freshnessText(data.fetchedAtMillis, nowMillis)}",
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                    item(key = "incomplete-warning") {
                        AnimatedVisibility(
                            visible = data.incompleteRoots.isNotEmpty(),
                            enter = expandVertically(motionTween(Motion.Emphasized)) + fadeIn(motionTween(Motion.Emphasized)),
                            exit = shrinkVertically(motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
                        ) {
                            VacantErrorBanner(
                                text = "Not checked: " +
                                    data.incompleteRoots.joinToString(
                                        ", ",
                                        transform = { RoomMerger.rootLabel(it) }
                                    ) +
                                    " — the list may be incomplete",
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                    item(key = "rooms-label") {
                        Text(
                            "${filteredRooms.size} of ${data.rooms.size} rooms",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.itemEntrance(5).padding(horizontal = 20.dp)
                        )
                    }
                    items(filteredRooms, key = { it.key }) { room ->
                        RoomRow(
                            room = room,
                            cell = cellOf(room),
                            expanded = expandedRoom == room.key,
                            onToggle = {
                                expandedRoom = if (expandedRoom == room.key) null else room.key
                            },
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .animateItem()
                        )
                    }
                    item(key = "footer") {
                        Text(
                            "From ${data.sources.size} official GNDEC timetables · " +
                                Formatters.freshnessText(data.fetchedAtMillis, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 20.dp, end = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VacantHeader(refreshing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text("Vacant rooms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Live from GNDEC department room timetables", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun VacantHeroCard(
    freeCount: Int,
    totalRooms: Int,
    noDataCount: Int,
    dayLabel: String,
    timeRange: String,
    isNow: Boolean,
    weekendNote: Boolean,
    modifier: Modifier = Modifier
) {
    val countIn = motionTween<Float>(Motion.Emphasized)
    val countSlide = motionTween<IntOffset>(Motion.Emphasized, Motion.EasingEnter)
    Card(
        modifier = modifier.fillMaxWidth().background(premiumAquaBrush(), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Text(
                        if (isNow) "RIGHT NOW" else "AT A GLANCE",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.MeetingRoom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            val countIn = motionTween<Float>(Motion.Emphasized)
            val countOut = motionTween<Float>(Motion.Fast)
            AnimatedContent(
                targetState = freeCount,
                transitionSpec = {
                    (fadeIn(countIn) + slideInVertically(countSlide) { it / 6 }) togetherWith fadeOut(countOut)
                },
                label = "vacantHeroCount"
            ) { count ->
                Text(
                    "$count room${if (count == 1) "" else "s"} free",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Out of $totalRooms rooms · $dayLabel $timeRange")
                    if (noDataCount > 0) append(" · $noDataCount without published data")
                    if (weekendNote) append(" · Weekend — showing the next teaching day")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 7.dp)
    )
}

@Composable
private fun VacantChip(label: String, selected: Boolean, badgeNow: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        animationSpec = motionTween(Motion.Normal),
        label = "vacantChipContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = motionTween(Motion.Normal),
        label = "vacantChipBorder"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = motionTween(Motion.Normal),
        label = "vacantChipContent"
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .pressFeedback(interaction, pressedScale = 0.95f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                view.hapticTick()
                onClick()
            }
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = contentColor, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, softWrap = false)
            if (badgeNow) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

@Composable
private fun VacantFilterToggle(vacantOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(4.dp)
    ) {
        VacantFilterOption("All rooms", selected = !vacantOnly, modifier = Modifier.weight(1f)) { onChange(false) }
        VacantFilterOption("Free only", selected = vacantOnly, modifier = Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable
private fun VacantFilterOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = motionTween(Motion.Normal),
        label = "vacantFilterContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionTween(Motion.Normal),
        label = "vacantFilterContent"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .pressFeedback(interaction, pressedScale = 0.97f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = contentColor, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun VacantSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        placeholder = { Text("Search rooms…", maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(motionTween(Motion.Fast)) + expandVertically(motionTween(Motion.Fast)),
                exit = fadeOut(motionTween(Motion.Fast)) + shrinkVertically(motionTween(Motion.Fast))
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun VacantErrorBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoomRow(
    room: MergedRoom,
    cell: RoomCell?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasDetails = cell?.busy == true &&
        (cell.teacher != null || cell.subject != null || cell.studentsSet != null)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motionTween(Motion.Normal),
        label = "roomRowChevron"
    )
    if (hasDetails) {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onToggle,
            modifier = modifier.fillMaxWidth().pressFeedback(interaction, pressedScale = 0.98f),
            interactionSource = interaction,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            RoomRowContent(room, cell, expanded, chevronRotation)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            RoomRowContent(room, cell, expanded = false, chevronRotation = 0f)
        }
    }
}

@Composable
private fun RoomRowContent(room: MergedRoom, cell: RoomCell?, expanded: Boolean, chevronRotation: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pillIn = motionTween<Float>(Motion.Normal)
            val pillOut = motionTween<Float>(Motion.Fast)
            AnimatedContent(
                targetState = if (cell == null) -1 else if (cell.busy) 1 else 0,
                transitionSpec = { fadeIn(pillIn) togetherWith fadeOut(pillOut) },
                label = "roomStatusPill"
            ) { stateFlag ->
                Surface(
                    shape = CircleShape,
                    color = when (stateFlag) {
                        1 -> GndecOrange.copy(alpha = 0.15f)
                        0 -> GndecGreen.copy(alpha = 0.14f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when (stateFlag) {
                        1 -> GndecOrange
                        0 -> GndecGreen
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        when (stateFlag) {
                            1 -> "BUSY"
                            0 -> "FREE"
                            else -> "NO DATA"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(room.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = when {
                        cell == null -> "Not published for this slot by any department"
                        !cell.busy -> "No class scheduled"
                        cell.subject != null -> cell.subject
                        cell.teacher != null -> cell.teacher
                        else -> "Class in session"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (cell?.busy == true) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse details" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation }
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && cell?.busy == true,
            enter = expandVertically(motionTween(Motion.Emphasized)) + fadeIn(motionTween(Motion.Emphasized)),
            exit = shrinkVertically(motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
        ) {
            Column(Modifier.padding(start = 2.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (cell != null) {
                    cell.teacher?.let { DetailRow(Icons.Default.Person, it) }
                    cell.studentsSet?.let { DetailRow(Icons.Default.Groups, it) }
                    cell.subject?.let { DetailRow(Icons.Default.MenuBook, it) }
                    cell.activity?.let { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                when (tag) {
                                    "L" -> "Lecture"
                                    "P" -> "Practical"
                                    "T" -> "Tutorial"
                                    else -> tag
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VacantLoading() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Loading room timetable…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VacantLoadError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("Couldn’t load vacant rooms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Try again")
            }
        }
    }
}

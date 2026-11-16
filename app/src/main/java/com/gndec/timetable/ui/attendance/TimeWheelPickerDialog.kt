package com.gndec.timetable.ui.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.theme.GndecTeal
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

private val WheelItemHeight = 44.dp
private val WheelViewportHeight = 220.dp
private val WheelVerticalPadding = (WheelViewportHeight - WheelItemHeight) / 2 // 88.dp
private const val VirtualLoopMultiplier = 1000
private val QuickMinutePresets = listOf(0, 15, 30, 45)

/**
 * Redesigned time picker dialog featuring:
 * - Tabular live digital readout + one-tap AM/PM segmented toggle
 * - Physics-based snapping circular Hour (01..12) and Minute (00..59) wheels with alpha edge mask
 * - Quick minute jump chips (:00, :15, :30, :45)
 * - Optional Material 3 keyboard TimeInput toggle for direct digit entry
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        mutableIntStateOf(((clamped / 60) % 12).let { if (it == 0) 12 else it })
    }
    var minute by remember { mutableIntStateOf(clamped % 60) }
    var useKeyboardInput by remember { mutableStateOf(false) }

    // Used when user switches to keyboard TimeInput mode
    val initialHour24 = toHour24(isAm, hour12)
    var keyboardState by remember {
        mutableStateOf<androidx.compose.material3.TimePickerState?>(null)
    }

    val view = LocalView.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 380.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: Title + Keyboard/Wheel toggle
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (useKeyboardInput) "Enter exact time" else "Scroll or tap to adjust",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            view.hapticTick()
                            if (useKeyboardInput) {
                                // Sync back from keyboard state to wheel state
                                keyboardState?.let { st ->
                                    isAm = st.hour < 12
                                    hour12 = (st.hour % 12).let { if (it == 0) 12 else it }
                                    minute = st.minute
                                }
                            }
                            useKeyboardInput = !useKeyboardInput
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = if (useKeyboardInput) Icons.Default.AccessTime else Icons.Default.Keyboard,
                            contentDescription = if (useKeyboardInput) "Switch to wheel picker" else "Switch to keyboard input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                val modeSwapSpec = motionTween<Float>(Motion.Fast)
                AnimatedContent(
                    targetState = useKeyboardInput,
                    transitionSpec = {
                        fadeIn(modeSwapSpec) togetherWith fadeOut(modeSwapSpec)
                    },
                    label = "timePickerMode"
                ) { keyboard ->
                    if (keyboard) {
                        val pickerState = rememberTimePickerState(
                            initialHour = toHour24(isAm, hour12),
                            initialMinute = minute,
                            is24Hour = false
                        )
                        LaunchedEffect(pickerState) {
                            keyboardState = pickerState
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            TimeInput(
                                state = pickerState,
                                colors = TimePickerDefaults.colors(
                                    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    periodSelectorSelectedContainerColor = GndecTeal,
                                    periodSelectorSelectedContentColor = Color.White
                                )
                            )
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Live readout + crisp AM/PM segmented switch
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                        RoundedCornerShape(18.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "%02d : %02d".format(hour12, minute),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                AmPmSegmentedToggle(
                                    isAm = isAm,
                                    onChange = { nextAm ->
                                        if (isAm != nextAm) {
                                            view.hapticTick()
                                            isAm = nextAm
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            // Wheel container
                            val hours = remember { (1..12).map { "%02d".format(it) } }
                            val minutes = remember { (0..59).map { "%02d".format(it) } }
                            var externalMinuteJump by remember { mutableIntStateOf(0) }

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(WheelViewportHeight)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        RoundedCornerShape(22.dp)
                                    )
                            ) {
                                // Center selection pill
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                            RoundedCornerShape(14.dp)
                                        )
                                )

                                // Wheels with seamless alpha mask (BlendMode.DstIn) — no harsh overlay boxes
                                val fadeMask = remember {
                                    Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.24f to Color.Black,
                                        0.76f to Color.Black,
                                        1.0f to Color.Transparent
                                    )
                                }
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp)
                                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(brush = fadeMask, blendMode = BlendMode.DstIn)
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SmoothLoopWheel(
                                        items = hours,
                                        initialIndex = hour12 - 1,
                                        modifier = Modifier.weight(1f),
                                        onSelected = { hour12 = it + 1 }
                                    )
                                    Text(
                                        ":",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 2.dp)
                                    )
                                    SmoothLoopWheel(
                                        items = minutes,
                                        initialIndex = minute,
                                        externalTargetIndex = minute,
                                        externalJumpToken = externalMinuteJump,
                                        modifier = Modifier.weight(1f),
                                        onSelected = { minute = it }
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // Quick minute presets
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickMinutePresets.forEach { preset ->
                                    val selected = minute == preset
                                    val chipBg by animateColorAsState(
                                        targetValue = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        },
                                        animationSpec = motionTween(Motion.Fast),
                                        label = "minutePresetBg"
                                    )
                                    val chipFg by animateColorAsState(
                                        targetValue = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        animationSpec = motionTween(Motion.Fast),
                                        label = "minutePresetFg"
                                    )
                                    Surface(
                                        onClick = {
                                            view.hapticTick()
                                            minute = preset
                                            externalMinuteJump++
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = chipBg,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            }
                                        )
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                ":%02d".format(preset),
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = chipFg
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val totalMinutes = if (useKeyboardInput) {
                                val st = keyboardState
                                if (st != null) st.hour * 60 + st.minute
                                else initialHour24 * 60 + minute
                            } else {
                                toHour24(isAm, hour12) * 60 + minute
                            }
                            onConfirm(totalMinutes)
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GndecTeal,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Set time", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AmPmSegmentedToggle(
    isAm: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(true to "AM", false to "PM").forEach { (amOption, label) ->
            val selected = isAm == amOption
            val bg by animateColorAsState(
                targetValue = if (selected) GndecTeal else Color.Transparent,
                animationSpec = motionTween(Motion.Fast),
                label = "amPmBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = motionTween(Motion.Fast),
                label = "amPmFg"
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .clickable { onChange(amOption) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            }
        }
    }
}

@Composable
private fun SmoothLoopWheel(
    items: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    externalTargetIndex: Int = initialIndex,
    externalJumpToken: Int = 0,
    onSelected: (Int) -> Unit
) {
    val count = items.size
    val totalCount = count * VirtualLoopMultiplier
    val middleBase = (VirtualLoopMultiplier / 2) * count
    val startVirtual = middleBase + initialIndex.coerceIn(0, count - 1)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startVirtual)
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val currentOnSelected by rememberUpdatedState(onSelected)

    var centeredLogicalIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, count - 1)) }

    // Observe scroll position without ever writing back to listState on scroll
    LaunchedEffect(listState, count) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) null
            else {
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                visible.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index
            }
        }
            .mapNotNull { it }
            .distinctUntilChanged()
            .collect { virtualIdx ->
                val logical = ((virtualIdx % count) + count) % count
                if (logical != centeredLogicalIndex) {
                    centeredLogicalIndex = logical
                    view.hapticTick()
                    currentOnSelected(logical)
                }
            }
    }

    // Only animate scroll when explicitly triggered by an external preset button
    LaunchedEffect(externalJumpToken) {
        if (externalJumpToken > 0) {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val currentVirtual = info.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index ?: startVirtual
            val currentMod = ((currentVirtual % count) + count) % count
            var delta = externalTargetIndex.coerceIn(0, count - 1) - currentMod
            if (delta > count / 2) delta -= count
            if (delta < -count / 2) delta += count
            val targetVirtual = (currentVirtual + delta).coerceIn(0, totalCount - 1)
            listState.animateScrollToItem(targetVirtual)
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = snapBehavior,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(vertical = WheelVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(totalCount) { virtualIdx ->
            val logical = virtualIdx % count
            val isCentered = logical == centeredLogicalIndex
            Box(
                modifier = Modifier
                    .height(WheelItemHeight)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        scope.launch { listState.animateScrollToItem(virtualIdx) }
                    }
                    .graphicsLayer {
                        val info = listState.layoutInfo
                        val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == virtualIdx }
                        if (itemInfo != null && info.viewportEndOffset > info.viewportStartOffset) {
                            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val itemCenter = itemInfo.offset + itemInfo.size / 2f
                            val distanceInItems = (abs(itemCenter - viewportCenter) / itemInfo.size.coerceAtLeast(1)).coerceIn(0f, 2.2f)
                            val scale = 1f - (distanceInItems * 0.11f)
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - (distanceInItems * 0.34f)).coerceIn(0.22f, 1f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = items[logical],
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = if (isCentered) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCentered) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 23.sp
                )
            }
        }
    }
}

private fun toHour24(isAm: Boolean, hour12: Int): Int = when {
    isAm && hour12 == 12 -> 0
    !isAm && hour12 == 12 -> 12
    isAm -> hour12
    else -> hour12 + 12
}

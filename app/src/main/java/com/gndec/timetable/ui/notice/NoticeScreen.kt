package com.gndec.timetable.ui.notice

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.launch

@Composable
fun NoticeScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val notices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val refreshing by container.erpNoticeManager.refreshing.collectAsStateWithLifecycle()
    val lastError by container.erpNoticeManager.lastError.collectAsStateWithLifecycle()
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    val holidaysRefreshing by container.holidayManager.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var holidaysExpanded by remember { mutableStateOf(false) }

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp + PremiumBottomBarContentClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PremiumPageHeader(
                        title = "Notice",
                        subtitle = "Latest GNDEC official updates",
                        onSettings = onOpenSettings
                    )
                }
                item {
                    HolidaysSection(
                        holidays = holidays,
                        refreshing = holidaysRefreshing,
                        expanded = holidaysExpanded,
                        onToggle = { holidaysExpanded = !holidaysExpanded }
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text("GNDEC OFFICIAL NOTICE FEED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                Text("ERP + homepage notices · cached for instant loading", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { container.appScope.launch { container.erpNoticeManager.refresh(forceRefresh = true) } }, enabled = !refreshing) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh notices", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (notices.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.animateItem()) {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
                                val phase = if (refreshing) "__loading" else (lastError ?: "__empty")
                                val phaseSwapIn = motionTween<Float>(Motion.Normal)
                                val phaseSwapOut = motionTween<Float>(Motion.Fast)
                                AnimatedContent(
                                    targetState = phase,
                                    modifier = Modifier.animateContentSize(motionTween(Motion.Normal)),
                                    transitionSpec = { fadeIn(phaseSwapIn) togetherWith fadeOut(phaseSwapOut) },
                                    label = "noticePhase"
                                ) { current ->
                                    Text(
                                        when {
                                            current == "__loading" -> "Loading official notices…"
                                            current == "__empty" -> "No cached notices available yet."
                                            else -> current
                                        },
                                        modifier = Modifier.padding(18.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(notices, key = { it.id }) { notice ->
                        Box(Modifier.animateItem()) {
                            NoticeCard(notice = notice, onClick = { open(notice.url) })
                        }
                    }
                }
                item {
                    TealOutlineButton(
                        text = "View all previous notices on GNDEC official website",
                        icon = Icons.Default.OpenInNew,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        onClick = { open(ErpNoticeManager.NOTICE_URL) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HolidaysSection(
    holidays: List<Holiday>,
    refreshing: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).animateContentSize(motionTween(Motion.Normal)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp),
        onClick = onToggle
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("HOLIDAYS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        when {
                            refreshing && holidays.isEmpty() -> "Loading official holiday list…"
                            holidays.isEmpty() -> "Official list unavailable"
                            else -> "Official GNDEC holidays · ${holidays.first().year}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Hide holidays" else "Show holidays", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = expanded && holidays.isNotEmpty(),
                enter = expandVertically(animationSpec = motionTween(Motion.Normal)) + fadeIn(motionTween(Motion.Normal)),
                exit = shrinkVertically(animationSpec = motionTween(Motion.Fast)) + fadeOut(motionTween(Motion.Fast))
            ) {
                val categoryGroups = listOf(
                    Triple("PUBLIC HOLIDAYS", "Public holiday", MaterialTheme.colorScheme.primary),
                    Triple("RESTRICTED HOLIDAYS", "Restricted holiday", MaterialTheme.colorScheme.tertiary),
                    Triple("HALF-DAY HOLIDAYS", "Half-day holiday", MaterialTheme.colorScheme.secondary)
                )
                Column(Modifier.padding(top = 13.dp)) {
                    var visibleGroupCount = 0
                    categoryGroups.forEach { (label, category, color) ->
                        val group = holidays.filter { it.category == category }
                        if (group.isNotEmpty()) {
                            if (visibleGroupCount > 0) Spacer(Modifier.size(14.dp))
                            visibleGroupCount += 1
                            Text(
                                "$label · ${group.size}",
                                color = color,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.9.sp
                            )
                            Spacer(Modifier.size(7.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                group.forEach { holiday ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Column(Modifier.width(92.dp)) {
                                            Text(holiday.displayDate.removeSuffix(", ${holiday.year}"), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            Text(holiday.weekday, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(holiday.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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

@Composable
private fun NoticeCard(notice: ErpNotice, onClick: () -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(4.dp))
                Text(notice.displayDate, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                val sourceLabel = notice.author.ifBlank { notice.source }
                if (sourceLabel.isNotBlank()) Text(sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

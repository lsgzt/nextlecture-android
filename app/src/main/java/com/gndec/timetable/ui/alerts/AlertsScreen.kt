package com.gndec.timetable.ui.alerts

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecGreen
import com.gndec.timetable.ui.theme.GndecInk
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.theme.GndecOrangeSoft
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.ui.settings.ReliabilityStatus
import com.gndec.timetable.ui.settings.SettingsViewModel
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId

@Composable
fun AlertsScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLecture: (LectureEntity) -> Unit,
    onBack: () -> Unit = {}
) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val group = settings.group
    val lectures by remember(group) {
        if (group == null) flowOf(emptyList()) else container.db.lectureDao().observeForGroup(group)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val vm = remember { SettingsViewModel(container) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val reliability by vm.reliability.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val waitMillis = 60_000L - (System.currentTimeMillis() % 60_000L)
            kotlinx.coroutines.delay(waitMillis.coerceAtLeast(1_000L))
            now = System.currentTimeMillis()
        }
    }
    val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val upcoming = lectures.filter { it.dayOfWeek == zdt.dayOfWeek.value && it.startMinutes > zdt.hour * 60 + zdt.minute }.take(4)
    val enabled = settings.remind15 || settings.remind30 || settings.remind5 || settings.remindAtStart

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    PremiumPageHeader(
                        title = "Alerts",
                        subtitle = "${upcoming.size} scheduled lectures",
                        onBack = onBack,
                        onSettings = onOpenSettings
                    )
                }
                item { HealthCard(enabled, reliability, meta?.lastSuccessfulFetch) }
                item { Text("UPCOMING REMINDERS", modifier = Modifier.padding(horizontal = 20.dp), fontWeight = FontWeight.Bold) }
                if (upcoming.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Text("No upcoming reminders today.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                upcoming.forEachIndexed { index, lecture ->
                                    ReminderRow(lecture, settings, onClick = { onOpenLecture(lecture) })
                                    if (index < upcoming.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Default.BatteryAlert, containerColor = MaterialTheme.colorScheme.tertiary, tint = MaterialTheme.colorScheme.onTertiary, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Battery restrictions · Check recommended", fontWeight = FontWeight.Bold)
                                Text("Android may limit delivery on some devices", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
                        }
                    }
                }
                item {
                    TealOutlineButton(text = "Run reliability check", icon = Icons.Default.Security, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), onClick = vm::runReliabilityCheck)
                }
                item {
                    TextButton(
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Open notification settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                reliability?.let { status ->
                    item(key = "reliability-summary") {
                        Box(Modifier.fillMaxWidth().animateItem()) { ReliabilitySummary(status) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(enabled: Boolean, reliability: ReliabilityStatus?, lastFetch: Long?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            HealthRow(Icons.Default.Notifications, "Notifications enabled", if (enabled) "You'll get reminders on time" else "Turn on reminders in Settings", enabled)
            HealthRow(Icons.Default.AccessAlarm, "Exact alarms enabled", reliability?.let { if (it.exactAlarms) "Exact alarm permission is on" else "Exact alarm permission is off" } ?: "Run a reliability check", reliability?.exactAlarms ?: true)
            HealthRow(Icons.Default.CloudDone, "Timetable cached", lastFetch?.let { "Last updated ${Formatters.freshnessText(it, System.currentTimeMillis()).removePrefix("Updated ")}" } ?: "No saved timetable yet", lastFetch != null)
        }
    }
}

@Composable
private fun HealthRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, ok: Boolean) {
    val checkTint by animateColorAsState(
        targetValue = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        animationSpec = motionTween(Motion.Normal),
        label = "healthCheck"
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = checkTint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ReminderRow(lecture: LectureEntity, settings: AppSettings, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(when (lecture.lectureType?.lowercase()) { "practical", "lab" -> Icons.Default.Code; else -> Icons.Default.MenuBook }, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val before = when { settings.remind30 -> 30; settings.remind15 -> 15; settings.remind5 -> 5; else -> 0 }
            Text("${Formatters.hm(lecture.startMinutes - before)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ReliabilitySummary(status: ReliabilityStatus) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Reliability check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Notifications: ${if (status.notificationsEnabled) "Enabled" else "Disabled"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Exact alarms: ${if (status.exactAlarms) "Enabled" else "Not granted"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Scheduled reminders: ${status.scheduledReminders}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

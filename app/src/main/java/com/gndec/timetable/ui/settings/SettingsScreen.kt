package com.gndec.timetable.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gndec.timetable.BuildConfig
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecGreen
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.util.Formatters

private val TestDelayOptions = listOf(1, 5, 10, 15)
private const val SOURCE_CODE_URL = "https://github.com/lsgzt/nextlecture-android"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenAlerts: () -> Unit = {}) {
    val vm = remember { SettingsViewModel(container) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val reliability by vm.reliability.collectAsStateWithLifecycle()
    val releaseUpdate by vm.releaseUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.runReliabilityCheck() }
    var groupDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var backendInput by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var pyqBackendInput by remember(settings.pyqRagBackendUrl) { mutableStateOf(settings.pyqRagBackendUrl) }
    var modelMenu by remember { mutableStateOf(false) }
    var testDelay by remember { mutableStateOf(5) }
    var testDelayMenu by remember { mutableStateOf(false) }

    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            confirmButton = { TextButton(onClick = { groupDialog = false }) { Text("Close") } },
            title = { Text("Change group") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(groups) { group ->
                        TextButton(onClick = { vm.changeGroup(group); groupDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(group, fontWeight = if (group == settings.group) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Header("Settings", "Manage timetable & reminders", onBack = onBack, modifier = Modifier.padding(top = 8.dp)) }
                message?.let { info ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(info, Modifier.weight(1f))
                                TextButton(onClick = vm::clearMessage) { Text("OK") }
                            }
                        }
                    }
                }
                item {
                    SectionCard("Timetable", Icons.Default.CalendarMonth) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Current group", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(settings.group ?: "Not selected", style = MaterialTheme.typography.titleLarge)
                            }
                            TextButton(onClick = { groupDialog = true }) { Text("Change") }
                        }
                        Text("Updated · ${meta?.lastSuccessfulFetch?.let { Formatters.dateTime(it) } ?: "never"}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        Text("Checked · ${meta?.lastChecked?.let { Formatters.dateTime(it) } ?: "never"}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        TealOutlineButton("Fetch again", Icons.Default.Refresh, modifier = Modifier.fillMaxWidth(), onClick = vm::fetchAgain, enabled = !busy)
                    }
                }
                item {
                    SectionCard("Notifications", Icons.Default.Notifications) {
                        ToggleRow("Lecture reminders · 15 min before", settings.remind15, vm::setRemind15)
                        ToggleRow("30 minutes before", settings.remind30, vm::setRemind30)
                        ToggleRow("5 minutes before", settings.remind5, vm::setRemind5)
                        ToggleRow("At lecture start", settings.remindAtStart, vm::setRemindAtStart)
                        Text("Reminders run locally and work offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        Text("Schedule a real test reminder, then close the app or lock your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        TealOutlineButton("Notification alerts", Icons.Default.Notifications, modifier = Modifier.fillMaxWidth(), onClick = onOpenAlerts)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box {
                                TealOutlineButton("${testDelay} min", modifier = Modifier.width(96.dp), onClick = { testDelayMenu = true })
                                DropdownMenu(expanded = testDelayMenu, onDismissRequest = { testDelayMenu = false }) {
                                    TestDelayOptions.forEach { minutes ->
                                        DropdownMenuItem(text = { Text("$minutes minute${if (minutes == 1) "" else "s"}") }, onClick = { testDelay = minutes; testDelayMenu = false })
                                    }
                                }
                            }
                            TealOutlineButton("Schedule test", Icons.Default.Notifications, modifier = Modifier.weight(1f), onClick = { vm.scheduleTestNotification(testDelay) }, enabled = !busy)
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_REMINDERS))
                        }) { Text("Notification sound and channel") }
                    }
                }
                item {
                    SectionCard("App updates", Icons.Default.Notifications) {
                        ToggleRow("Announcements and updates", settings.announcementNotifications, vm::setAnnouncementNotifications)
                        Text("Announcements and release checks run when the app opens and during the existing background refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        if (releaseUpdate.latestMarker.isNotBlank()) {
                            Text(
                                if (releaseUpdate.updateAvailable) "Update available · release ${releaseUpdate.latestMarker}" else "You’re up to date · release ${com.gndec.timetable.domain.ReleaseUpdateManager.installedMarker()}",
                                color = if (releaseUpdate.updateAvailable) GndecOrange else GndecGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (releaseUpdate.updateAvailable) {
                                Text(releaseUpdate.releaseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                                releaseUpdate.notes.takeIf { it.isNotBlank() }?.let { notes ->
                                    Text(notes.take(240), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                                }
                                TealOutlineButton("Download update", modifier = Modifier.fillMaxWidth(), onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.gndec.timetable.domain.ReleaseUpdateManager.DOWNLOAD_URL)))
                                })
                            }
                        } else {
                            Text("No release check has completed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::checkForUpdates, enabled = !busy) { Text("Check for updates") }
                            TextButton(onClick = vm::checkAnnouncements, enabled = !busy) { Text("Check announcements") }
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lsgzt/nextlecture-android/edit/main/announcements.json")))
                        }) { Text("Manage announcements on GitHub") }
                    }
                }
                item {
                    SectionCard("Notification reliability", Icons.Default.Security) {
                        Text("Check notification permissions and battery settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        TealOutlineButton("Run reliability check", Icons.Default.Security, modifier = Modifier.fillMaxWidth(), onClick = vm::runReliabilityCheck)
                        reliability?.let {
                            StatusRow("Notifications", if (it.notificationsEnabled) "Enabled" else "Disabled", it.notificationsEnabled)
                            StatusRow("Exact alarms", if (it.exactAlarms) "Enabled" else "Not granted", it.exactAlarms)
                            StatusRow("Timetable cached", if (it.timetableCached) "Ready" else "Missing", it.timetableCached)
                            StatusRow("Scheduled reminders", it.scheduledReminders.toString(), it.scheduledReminders > 0)
                            StatusRow("Battery restrictions", if (it.batteryUnrestricted) "Unrestricted" else "Check recommended", it.batteryUnrestricted)
                        }
                        Row {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery settings") }
                            if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("Exact alarms") }
                        }
                    }
                }
                item {
                    SectionCard("Previous-year paper analysis", Icons.Default.Description) {
                        Text("Frequently Asked uses the secure GNDEC paper-analysis service. It is optional and the original Drive browser works without it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(pyqBackendInput, { pyqBackendInput = it }, label = { Text("PYQ analysis backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TealOutlineButton("Save PYQ URL", modifier = Modifier.fillMaxWidth(), onClick = { vm.setPyqRagBackendUrl(pyqBackendInput) })
                    }
                }
                item {
                    SectionCard("Gemini AI & timetable parsing", Icons.Default.Tune) {
                        ToggleRow("AI normalization (Gemini)", settings.aiEnabled, vm::setAiEnabled)
                        Text("AI helps normalize ambiguous fields; timetable parsing stays authoritative.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(backendInput, { backendInput = it }, label = { Text("Backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TealOutlineButton("Save backend URL", modifier = Modifier.fillMaxWidth(), onClick = { vm.setBackendUrl(backendInput) })
                        OutlinedTextField(keyInput, { keyInput = it }, label = { Text(if (vm.hasUserKey()) "Gemini API key · saved" else "Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row {
                            TextButton(onClick = { vm.saveKey(keyInput); keyInput = "" }) { Text("Save") }
                            TextButton(onClick = vm::testKey, enabled = !busy) { Text("Test") }
                            TextButton(onClick = vm::removeKey) { Text("Remove") }
                        }
                        Text("Gemini model", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box {
                                TealOutlineButton(settings.model, modifier = Modifier, onClick = { modelMenu = true })
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    models.forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = { vm.setModel(model); modelMenu = false }) }
                                }
                            }
                            TextButton(onClick = vm::refreshModels, enabled = !busy) { Text("Refresh models") }
                        }
                        OutlinedTextField(customModel, { customModel = it }, label = { Text("Custom model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { if (customModel.isNotBlank()) vm.setModel(customModel) }) { Text("Save custom model") }
                    }
                }
                item {
                    SectionCard("Appearance", Icons.Default.Palette) {
                        listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.themeMode == mode, onClick = { vm.setThemeMode(mode) })
                                Text(label)
                            }
                        }
                    }
                }
                item {
                    SectionCard("About", Icons.Default.CheckCircle) {
                        Text("GNDEC Timetable v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text("An open-source Android app for GNDEC students. Reminders are local and offline-capable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL))) }) {
                            Text("View code on GitHub")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, size = 38.dp)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
    }
}

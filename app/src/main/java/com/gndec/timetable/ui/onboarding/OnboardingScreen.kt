package com.gndec.timetable.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.ui.IconBadge
import com.gndec.timetable.ui.ScreenSurface
import com.gndec.timetable.ui.TealOutlineButton
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("ITB2") }
    val scope = rememberCoroutineScope()

    fun finish() {
        scope.launch {
            container.settings.setOnboardingDone(true)
            onDone()
        }
    }

    ScreenSurface {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                0 -> CenteredContent {
                    IconBadge(Icons.Default.CalendarMonth, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 82.dp)
                    Spacer(Modifier.height(24.dp))
                    Text("Welcome to GNDEC\nTimetable", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Never miss your next lecture.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Your official timetable, cached for offline access, with reminders that work even without internet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(28.dp))
                    TealOutlineButton("Get started", icon = Icons.Default.Refresh, modifier = Modifier.fillMaxWidth(), onClick = { step = 1 })
                }
                1 -> CenteredContent {
                    IconBadge(Icons.Default.CloudDone, size = 68.dp)
                    Spacer(Modifier.height(20.dp))
                    if (error == null) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Fetching the official GNDEC timetable…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        LaunchedEffect(Unit) {
                            when (val result = container.refreshManager.refresh(force = true)) {
                                is RefreshResult.Success, RefreshResult.UpToDate -> {
                                    groups = container.db.lectureDao().distinctGroups()
                                    if (groups.isNotEmpty()) step = 2 else error = "No groups found in the timetable."
                                }
                                is RefreshResult.Failed -> error = result.reason
                            }
                        }
                    } else {
                        Text("Couldn't fetch the timetable", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        TealOutlineButton("Try again", icon = Icons.Default.Refresh, modifier = Modifier.fillMaxWidth(), onClick = { error = null })
                    }
                }
                2 -> CenteredContent {
                    IconBadge(Icons.Default.CalendarMonth, size = 68.dp)
                    Spacer(Modifier.height(18.dp))
                    Text("Select your timetable group", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(query, { query = it }, label = { Text("Search groups…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(groups.filter { it.contains(query.trim(), ignoreCase = true) }) { group ->
                                Text(group, modifier = Modifier.fillMaxWidth().clickable { selected = group; query = group }.padding(15.dp), fontWeight = if (group == selected) FontWeight.Bold else FontWeight.Normal, color = if (group == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TealOutlineButton("Continue with ${selected ?: "…"}", modifier = Modifier.fillMaxWidth(), enabled = selected != null, onClick = {
                        val group = selected ?: return@TealOutlineButton
                        scope.launch { if (container.refreshManager.changeGroup(group)) step = 3 else error = "Group is not available in the cached timetable." }
                    })
                }
                3 -> CenteredContent {
                    IconBadge(Icons.Default.Notifications, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 72.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("Never miss a lecture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Enable notifications so the app can remind you before every lecture, even when you're offline.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
                    TealOutlineButton("Enable notifications", icon = Icons.Default.Notifications, modifier = Modifier.fillMaxWidth(), onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else finish() })
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { finish() }) { Text("Skip for now", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun CenteredContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

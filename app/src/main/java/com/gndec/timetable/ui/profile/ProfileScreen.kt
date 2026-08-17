package com.gndec.timetable.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.ScreenSurface
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var name by remember(settings.studentName) { mutableStateOf(settings.studentName) }
    var rollNumber by remember(settings.rollNumber) { mutableStateOf(settings.rollNumber) }
    var branch by remember(settings.branch) { mutableStateOf(settings.branch) }
    var registrationNumber by remember(settings.registrationNumber) { mutableStateOf(settings.registrationNumber) }
    var saved by remember { mutableStateOf(false) }

    val dirty = name != settings.studentName ||
        rollNumber != settings.rollNumber ||
        branch != settings.branch ||
        registrationNumber != settings.registrationNumber
    val displayName = name.trim().ifBlank { "Your profile" }
    val initials = displayName.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "S" }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        ScreenSurface {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Header("Profile", "Student identity", onBack = onBack, modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    ProfileHero(
                        initials = initials,
                        name = displayName,
                        branch = branch.trim().ifBlank { "Add your branch" },
                        rollNumber = rollNumber.trim().ifBlank { "Add roll number" }
                    )
                }
                item {
                    Text("Personal details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileField(value = name, label = "Full name", icon = Icons.Default.Person) { name = it; saved = false }
                            ProfileField(value = rollNumber, label = "Roll number", icon = Icons.Default.Badge) { rollNumber = it; saved = false }
                        }
                    }
                }
                item {
                    Text("Academic details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileField(value = branch, label = "Branch", icon = Icons.Default.Badge) { branch = it; saved = false }
                            ProfileField(value = registrationNumber, label = "Registration number", icon = Icons.Default.Badge) { registrationNumber = it; saved = false }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            scope.launch {
                                container.settings.setStudentName(name)
                                container.settings.setRollNumber(rollNumber)
                                container.settings.setBranch(branch)
                                container.settings.setRegistrationNumber(registrationNumber)
                                saved = true
                            }
                        },
                        enabled = dirty || !saved,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Text(if (saved) "Profile saved" else "Save profile", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
                item {
                    AnimatedVisibility(visible = saved, enter = fadeIn(), exit = fadeOut()) {
                        Text("Saved privately on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(initials: String, name: String, branch: String, rollNumber: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(initials, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(branch, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rollNumber, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProfileField(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

package com.gndec.timetable.ui.syllabus

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.net.PyqFrequentlyAskedResponse
import com.gndec.timetable.net.PyqGroupDetailResponse
import kotlinx.coroutines.launch

@Composable
fun FrequentlyAskedEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("🔥 Frequently Asked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("See questions repeated across distinct papers", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("Open", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun FrequentlyAskedScreen(container: AppContainer, onBack: () -> Unit, onOpenGroup: (Long) -> Unit) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    var course by rememberSaveable { mutableStateOf("") }
    var response by remember { mutableStateOf<PyqFrequentlyAskedResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun load() {
        val cleanCourse = course.trim()
        if (cleanCourse.isBlank()) {
            error = "Enter a course code first, for example PCME-110."
            return
        }
        scope.launch {
            loading = true
            error = null
            runCatching { container.pyqRagClient.frequentlyAsked(settings.pyqRagBackendUrl, cleanCourse) }
                .onSuccess { response = it }
                .onFailure { error = "Frequently Asked is temporarily unavailable. The original paper browser is still available below." }
            loading = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("🔥 Frequently Asked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Repeated questions by course", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = course,
                onValueChange = { course = it.uppercase() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                label = { Text("Course code") },
                placeholder = { Text("PCME-110 or BBA-101") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            Text("Examples: PCME-110 · PCCE-111 · BBA-101", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
            Button(onClick = ::load, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) { Text("Find repeated questions") }
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            response?.let { result ->
                if (result.groups.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text("No indexed repeated questions for ${result.course} yet. Processing is resumable and may still be in progress.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("${result.groups.size} repeated groups · frequency means distinct papers", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                        items(result.groups, key = { it.groupId }) { group ->
                            Card(onClick = { onOpenGroup(group.groupId) }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(7.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${group.frequency} distinct paper${if (group.frequency == 1L) "" else "s"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        group.confidence?.let { Text(" · ${(it * 100).toInt()}% match", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            } ?: run {
                if (!loading && error == null) Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text("Enter a course code to see conservatively grouped repeated questions.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun FrequentlyAskedGroupScreen(container: AppContainer, groupId: Long, onBack: () -> Unit) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    var result by remember { mutableStateOf<PyqGroupDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(groupId, settings.pyqRagBackendUrl) {
        runCatching { container.pyqRagClient.groupDetails(settings.pyqRagBackendUrl, groupId) }
            .onSuccess { result = it }
            .onFailure { error = "This repeated-question group is temporarily unavailable." }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text("Question details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                if (result == null && error == null) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            result?.let { detail ->
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(detail.group.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${detail.frequency} distinct paper${if (detail.frequency == 1) "" else "s"}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        detail.group.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp)) }
                    }
                    items(detail.occurrences, key = { occurrence -> occurrence.question?.id ?: occurrence.hashCode().toLong() }) { occurrence ->
                        occurrence.question?.let { question ->
                            Card(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                    Text(question.questionText, style = MaterialTheme.typography.bodyLarge)
                                    Text("Page ${question.sourcePage} · ${question.paper?.examSession ?: "session unavailable"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                                    question.paper?.let { paper ->
                                        Text(paper.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                        androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(paper.driveUrl) }) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.size(6.dp))
                                            Text("Open original PDF")
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
}


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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.gndec.timetable.net.PyqCoverage
import com.gndec.timetable.net.PyqFrequentlyAskedResponse
import com.gndec.timetable.net.PyqGroupDetailResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

private fun coverageHeadline(coverage: PyqCoverage): String = when {
    coverage.total == 0 -> "No papers for this course are in the catalog."
    coverage.failureReason == "provider_quota" -> "AI quota reached; ${coverage.failed} paper${if (coverage.failed == 1) " is" else "s are"} waiting to retry."
    coverage.failed > 0 && coverage.processing > 0 -> "Indexing is running, but ${coverage.failed} paper${if (coverage.failed == 1) " needs" else "s need"} a retry."
    coverage.failed > 0 -> "${coverage.failed} paper${if (coverage.failed == 1) " needs" else "s need"} a retry."
    coverage.processing > 0 -> "Indexing is running right now."
    coverage.pending > 0 && coverage.completed == 0 -> "Queued for indexing; nothing has failed."
    coverage.pending > 0 -> "Partially indexed; more papers are queued."
    coverage.completed > 0 -> "All catalog papers for this course are indexed."
    else -> "No processing activity has started for this course yet."
}

private fun formatRetryWait(seconds: Int?): String {
    val total = seconds ?: 0
    if (total < 60) return "less than a minute"
    val minutes = total / 60
    return if (minutes < 60) "about $minutes minute${if (minutes == 1) "" else "s"}" else "about ${minutes / 60} hour${if (minutes / 60 == 1) "" else "s"}"
}

private fun coverageSupportingText(coverage: PyqCoverage): String = when {
    coverage.total == 0 -> "Check the course code, then use the original Previous year papers browser to confirm the paper exists."
    coverage.failureReason == "provider_quota" -> "Gemini’s request quota was reached. Completed results remain available; failed papers will retry after quota recovery."
    coverage.failed > 0 -> "Completed results remain available. Use the retry action below to attempt one more paper safely."
    coverage.processing > 0 -> "This screen refreshes automatically while the server processes papers. You can also refresh manually."
    coverage.pending > 0 -> "The remaining papers will appear as resumable batches finish."
    else -> "The repeated-question list below is based on indexed papers only."
}

@Composable
private fun CoverageCard(coverage: PyqCoverage, loading: Boolean, onRefresh: () -> Unit, retrying: Boolean, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (coverage.processing > 0 || coverage.pending > 0) Icons.Default.Sync else if (coverage.failed > 0) Icons.Default.ErrorOutline else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (coverage.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("Indexing status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(coverageHeadline(coverage), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(coverageSupportingText(coverage), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverageStat("Ready", coverage.completed, Modifier.weight(1f))
                CoverageStat("Queued", coverage.pending, Modifier.weight(1f))
                CoverageStat("Working", coverage.processing, Modifier.weight(1f))
                CoverageStat("Failed", coverage.failed, Modifier.weight(1f), isError = coverage.failed > 0)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (coverage.total > 0) "${coverage.completed} of ${coverage.total} papers indexed" else "No matching catalog papers",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                if (coverage.pending > 0 || coverage.processing > 0 || coverage.failed > 0) {
                    OutlinedButton(onClick = onRefresh, enabled = !loading && !retrying, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(5.dp))
                        Text("Refresh")
                    }
                }
            }
            if (coverage.failed > 0) {
                OutlinedButton(onClick = onRetry, enabled = !retrying && !loading, modifier = Modifier.fillMaxWidth()) {
                    if (retrying) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(7.dp))
                    Text(if (retrying) "Retrying one paper…" else "Retry one failed paper now")
                }
            }
            coverage.updatedAt?.let {
                Text("Last catalog activity: ${formatStatusTime(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CoverageStat(label: String, value: Int, modifier: Modifier, isError: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatStatusTime(value: String): String = value
    .replace('T', ' ')
    .removeSuffix("+00:00")
    .removeSuffix("Z")
    .take(16)

/**
 * PDF text extraction can expose Symbol/MathType private-use glyphs instead of normal Unicode.
 * Convert the known glyphs to readable Unicode/ASCII and make common OCR layout noise visible
 * without attempting to alter the source PDF itself.
 */
private fun formatPyqText(raw: String): String {
    val replacements = mapOf(
        '\uF028' to '(', '\uF029' to ')', '\uF02B' to '+', '\uF02D' to '−', '\uF03D' to '=',
        '\uF0A5' to '∞', '\uF0AE' to '→', '\uF0C2' to '·', '\uF0CC' to 'λ', '\uF0CD' to 'μ', '\uF0D0' to 'π',
        '\uF126' to '(', '\uF127' to '(', '\uF128' to ')', '\uF129' to '(', '\uF12A' to '[', '\uF12B' to '[',
        '\uF132' to '∫', '\uF136' to '[', '\uF137' to '−', '\uF138' to ']', '\uF139' to ')', '\uF13A' to ']', '\uF13B' to ']'
    )
    val mapped = buildString(raw.length) {
        raw.forEach { character ->
            append(replacements[character] ?: if (character.code in 0xE000..0xF8FF) '?' else character)
        }
    }
    var text = mapped.replace(Regex("\\s+"), " ").trim()
    text = text.replace(Regex("(?i)\\s+(?:page\\s+[0-9il]+(?:\\s+of\\s+[0-9il]+)?|morning|evening)\\b.*$"), "")
    text = text.replace(Regex("\\s+[RKHA]{5,}\\s*$"), "")
    text = text.replace(Regex("\\s+(?=Q\\.?\\s*[2-9]\\b)"), "\\n")
    text = text.replace(Regex("\\s+(?=(?:\\(?[ivx]+\\)|[a-z]\\)))"), "\\n")
    text = text.replace(Regex("\\s+OR\\s+"), "\\nOR\\n")
    text = text.replace(Regex("\\s+([,.;:!?])"), "$1")
    text = text
        .replace("( ", "(")
        .replace("[ ", "[")
        .replace("{ ", "{")
        .replace(" )", ")")
        .replace(" ]", "]")
        .replace(" }", "}")
    return text.trim()
}

@Composable
fun FrequentlyAskedScreen(container: AppContainer, onBack: () -> Unit, onOpenGroup: (Long) -> Unit) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.gndec.timetable.data.prefs.AppSettings())
    var course by rememberSaveable { mutableStateOf("") }
    var response by remember { mutableStateOf<PyqFrequentlyAskedResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryNotice by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val responseListState = rememberLazyListState()

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
                .onFailure { error = "Couldn’t reach the analysis service. The original Previous year papers browser is still available below." }
            loading = false
        }
    }

    fun retryNow() {
        val cleanCourse = (response?.course ?: course).trim()
        if (cleanCourse.isBlank() || retrying) return
        scope.launch {
            retrying = true
            retryNotice = null
            try {
                val outcome = container.pyqRagClient.retryCourse(settings.pyqRagBackendUrl, cleanCourse)
                response = container.pyqRagClient.frequentlyAsked(settings.pyqRagBackendUrl, cleanCourse)
                retryNotice = when (outcome.status) {
                    "processed" -> "Retry started and one paper completed successfully."
                    "failed" -> "Retry was attempted, but Gemini still rejected this paper. The failure is recorded and will be retried again after quota recovery."
                    "nothing_to_retry" -> "There is no eligible failed or pending paper for this course right now."
                    "cooldown" -> "A retry was recently requested for this course. Try again in ${formatRetryWait(outcome.retryAfterSeconds)}."
                    else -> "Retry request completed; the status above is current."
                }
            } catch (_: Exception) {
                retryNotice = "Retry could not reach the analysis service. Try again later."
            } finally {
                retrying = false
            }
        }
    }

    LaunchedEffect(response?.course, response?.groups?.map { it.groupId }) {
        responseListState.scrollToItem(0)
    }

    LaunchedEffect(response?.course, response?.coverage?.pending, response?.coverage?.processing) {
        val coverage = response?.coverage ?: return@LaunchedEffect
        if (coverage.pending > 0 || coverage.processing > 0) {
            while (isActive) {
                delay(30_000)
                load()
            }
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
                onValueChange = { course = it.uppercase(); if (error != null) error = null },
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
            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), elevation = CardDefaults.cardElevation(0.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(14.dp))
                }
            }
            retryNotice?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
                response?.let { result ->
                LazyColumn(
                    state = responseListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { CoverageCard(result.coverage, loading, ::load, retrying, ::retryNow) }
                    if (result.groups.isEmpty()) {
                        item {
                            Text(
                                "No repeated-question groups are available yet for ${result.course}. The status above explains whether papers are missing, queued, processing, or need a retry.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    } else {
                        item { Text("${result.groups.size} repeated groups · frequency means distinct papers", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                        items(result.groups, key = { it.groupId }) { group ->
                            Card(onClick = { onOpenGroup(group.groupId) }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                    Text(formatPyqText(group.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                if (!loading && error == null) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text("Enter a course code to see indexed coverage and conservatively grouped repeated questions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(formatPyqText(detail.group.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${detail.frequency} distinct paper${if (detail.frequency == 1) "" else "s"}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        detail.group.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp)) }
                    }
                    items(detail.occurrences, key = { occurrence -> occurrence.question?.id ?: occurrence.hashCode().toLong() }) { occurrence ->
                        occurrence.question?.let { question ->
                            Card(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                    Text(formatPyqText(question.questionText), style = MaterialTheme.typography.bodyLarge)
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

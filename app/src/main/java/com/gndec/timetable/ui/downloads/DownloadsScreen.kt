package com.gndec.timetable.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.DownloadsState
import com.gndec.timetable.parse.DownloadLink
import com.gndec.timetable.parse.DownloadsParser
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Bridges the manager into Compose; filter text lives in the screen. */
class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<DownloadsState> = container.downloadsManager.state

    fun open() {
        viewModelScope.launch { container.downloadsManager.ensureLoaded() }
    }

    fun forceRefresh() {
        viewModelScope.launch { container.downloadsManager.refresh(force = true) }
    }
}

/**
 * "Downloads" — every form and document the college publishes on its
 * Downloads page, student-priority sorted (bus pass, vehicle pass, ID
 * cards, bonafide… first), searchable, one tap away in the browser.
 */
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm = remember { DownloadsViewModel(container) }
    LaunchedEffect(Unit) { vm.open() }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
            is DownloadsState.Loading -> DownloadsLoading()
            is DownloadsState.Error -> DownloadsLoadError(message = current.message, onRetry = vm::forceRefresh)
            is DownloadsState.Ready -> {
                val links = current.links
                val trimmed = query.trim()
                val visible = links.filter {
                    trimmed.isEmpty() || it.title.contains(trimmed, ignoreCase = true)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "header") {
                        DownloadsHeader(
                            refreshing = current.refreshing,
                            onBack = onBack,
                            onRefresh = vm::forceRefresh
                        )
                    }
                    item(key = "hero") {
                        DownloadsHeroCard(
                            total = links.size,
                            fromSnapshot = current.fromSnapshot,
                            fetchedAtMillis = current.fetchedAtMillis,
                            modifier = Modifier.itemEntrance(1).padding(horizontal = 20.dp)
                        )
                    }
                    if (current.error != null) {
                        item(key = "error") {
                            DownloadsErrorBanner(
                                text = current.error!!,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                    item(key = "search") {
                        DownloadsSearchField(
                            query = query,
                            onQueryChange = { query = it }
                        )
                    }
                    if (trimmed.isEmpty() && links.isNotEmpty()) {
                        item(key = "label-top") { SectionLabel("MOST ASKED FOR — BUS PASS, VEHICLE PASS, ID CARDS…") }
                    }
                    items(
                        visible,
                        key = { it.url }
                    ) { link ->
                        DownloadRow(
                            link = link,
                            highlight = DownloadsParser.priority(link.title) >= DownloadsParser.TOP_SCORE,
                            onOpen = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                    if (visible.isEmpty() && trimmed.isNotEmpty()) {
                        item(key = "empty-search") {
                            Text(
                                "Nothing matches “$trimmed”",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp)
                            )
                        }
                    }
                    item(key = "footer") {
                        Text(
                            if (current.fromSnapshot) {
                                "Bundled snapshot of gndec.ac.in/?q=node/85 · refresh to go live"
                            } else {
                                "Live from gndec.ac.in/?q=node/85 · " +
                                    Formatters.freshnessText(current.fetchedAtMillis, System.currentTimeMillis())
                            },
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
private fun DownloadsHeader(refreshing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Official forms & documents from gndec.ac.in",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
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
private fun DownloadsHeroCard(
    total: Int,
    fromSnapshot: Boolean,
    fetchedAtMillis: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
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
                        if (fromSnapshot) "OFFLINE COPY" else "STUDENT ESSENTIALS",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "$total documents, sorted for students",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Bus pass, vehicle pass, ID card, bonafide and degree forms first — everything else below",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DownloadsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        placeholder = { Text("Search forms — bus pass, ID card…", maxLines = 1) },
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun DownloadRow(
    link: DownloadLink,
    highlight: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = {
            view.hapticTick()
            onOpen()
        },
        modifier = modifier.fillMaxWidth().pressFeedback(pressInteraction, pressedScale = 0.98f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    iconFor(link),
                    contentDescription = null,
                    tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    link.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    if (link.sizeLabel.isNotBlank()) append(link.sizeLabel)
                    if (!link.isFile) {
                        if (isNotEmpty()) append(" · ")
                        append("opens in browser")
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

private fun iconFor(link: DownloadLink): ImageVector {
    val path = link.url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".pdf") || path.endsWith(".doc") || path.endsWith(".docx") -> Icons.Default.Description
        path.endsWith(".xls") || path.endsWith(".xlsx") -> Icons.Default.TableChart
        path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") -> Icons.Default.Image
        link.isFile -> Icons.Default.Description
        else -> Icons.Default.Link
    }
}

@Composable
private fun DownloadsErrorBanner(text: String, modifier: Modifier = Modifier) {
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
private fun DownloadsLoading() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Loading downloads…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadsLoadError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("Couldn’t load downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
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

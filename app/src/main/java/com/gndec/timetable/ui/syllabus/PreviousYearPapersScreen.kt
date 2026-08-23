package com.gndec.timetable.ui.syllabus

import android.content.Context
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class PreviousYearPaper(
    val id: String,
    val session: String,
    val title: String,
    val fileName: String,
    val pdfUrl: String,
    val downloadUrl: String
)

private fun loadPreviousYearPapers(context: Context): List<PreviousYearPaper> = runCatching {
    val json = context.assets.open("previous_year_papers.json")
        .bufferedReader()
        .use { it.readText() }
    val array = JSONObject(json).getJSONArray("papers")
    buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                PreviousYearPaper(
                    id = item.optString("id"),
                    session = item.optString("session"),
                    title = item.optString("title"),
                    fileName = item.optString("fileName"),
                    pdfUrl = item.optString("pdfUrl"),
                    downloadUrl = item.optString("downloadUrl")
                )
            )
        }
    }
}.getOrDefault(emptyList())

@Composable
fun PreviousYearPapersScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenFrequentlyAsked: () -> Unit = {}
) {
    val papers by produceState<List<PreviousYearPaper>>(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) { loadPreviousYearPapers(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filtered = if (normalizedQuery.isBlank()) {
        papers
    } else {
        papers.filter { paper ->
            paper.session.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                paper.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                paper.fileName.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }
    val uriHandler = LocalUriHandler.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("Previous year papers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Question papers from previous GNDEC examinations",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FrequentlyAskedEntryCard(onClick = onOpenFrequentlyAsked)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search subject, course code, year…") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )

            if (papers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No previous paper matches your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    item {
                        Text(
                            if (normalizedQuery.isBlank()) "${papers.size} papers · newest exam sessions first" else "${filtered.size} matching papers",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    itemsIndexed(filtered, key = { _, paper -> paper.id }) { index, paper ->
                        val previous = filtered.getOrNull(index - 1)
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (previous?.session != paper.session) {
                                Text(
                                    paper.session,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp)
                                )
                            }
                            PreviousYearPaperCard(
                                paper = paper,
                                onOpen = { uriHandler.openUri(paper.pdfUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviousYearPaperCard(
    paper: PreviousYearPaper,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    paper.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    paper.fileName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Open PDF",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

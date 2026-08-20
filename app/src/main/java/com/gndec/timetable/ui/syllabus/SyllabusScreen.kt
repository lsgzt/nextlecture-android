package com.gndec.timetable.ui.syllabus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gndec.timetable.data.db.SyllabusChatMessageEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.SyllabusLoadState
import com.gndec.timetable.ui.PremiumBottomBar
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyllabusViewModel(private val container: AppContainer) : ViewModel() {
    private val manager = container.syllabusManager
    val sessions = manager.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val loadState = manager.loadState

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()
    val messages = _selectedSessionId.flatMapLatest { id ->
        if (id.isNullOrBlank()) emptyFlow() else manager.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    private val _streamingAnswer = MutableStateFlow("")
    val streamingAnswer: StateFlow<String> = _streamingAnswer.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch { runCatching { manager.ensureReady() }.onFailure { _error.value = it.message } }
    }

    fun selectSession(id: String) {
        _error.value = null
        _selectedSessionId.value = id
    }

    fun newChat() {
        if (_sending.value) return
        _error.value = null
        _streamingAnswer.value = ""
        _selectedSessionId.value = null
    }

    fun ask(question: String) {
        if (_sending.value || question.isBlank()) return
        viewModelScope.launch {
            _sending.value = true
            _error.value = null
            _streamingAnswer.value = ""
            try {
                val result = manager.sendStreaming(_selectedSessionId.value, question) { delta ->
                    _streamingAnswer.value += delta
                }
                _selectedSessionId.value = result.sessionId
                _streamingAnswer.value = ""
            } catch (error: Exception) {
                _error.value = error.message ?: "Could not get an answer from Gemini"
            } finally {
                _sending.value = false
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            manager.deleteSession(id)
            if (_selectedSessionId.value == id) _selectedSessionId.value = null
        }
    }

    fun dismissError() { _error.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyllabusScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm: SyllabusViewModel = viewModel(
        factory = remember(container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SyllabusViewModel(container) as T
            }
        }
    )
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val streamingAnswer by vm.streamingAnswer.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.newChat(); historyOpen = false }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New chat")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (sessions.isEmpty()) {
                    Text("Your syllabus questions will appear here for offline continuation.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            Card(
                                onClick = { vm.selectSession(session.id); historyOpen = false },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = if (session.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text(session.branch, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { deleteId = session.id }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete saved chat?") },
            text = { Text("This removes the conversation from this device.") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(id); deleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PremiumBottomBar("syllabus") { route ->
                when (route) {
                    "home" -> onOpenHome()
                    "today" -> onOpenToday()
                    "notice" -> onOpenNotice()
                }
            }
        }
    ) { padding ->
        PremiumScreenBackground {
            Column(Modifier.fillMaxSize().padding(padding)) {
                PremiumPageHeader(
                    title = "Syllabus",
                    subtitle = "Ask AI about your syllabus",
                    onSettings = onOpenSettings
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TealOutlineButton(
                        text = "New chat",
                        icon = Icons.Default.Add,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.newChat(); question = "" }
                    )
                    OutlinedButton(onClick = { historyOpen = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Saved chats")
                    }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        StatusCard(loadState = loadState, error = error, onRetry = { vm.dismissError(); container.appScope.launch { runCatching { container.syllabusManager.ensureReady() } } })
                    }
                    if (messages.isEmpty() && streamingAnswer.isBlank()) {
                        item { WelcomeCard() }
                    } else {
                        items(messages, key = { it.id }) { message -> ChatBubble(message) }
                    }
                    if (streamingAnswer.isNotBlank()) {
                        item(key = "streaming-answer") {
                            ChatBubble(
                                SyllabusChatMessageEntity(
                                    id = Long.MIN_VALUE,
                                    sessionId = selectedId.orEmpty(),
                                    role = "model",
                                    content = streamingAnswer,
                                    timestamp = 0L
                                )
                            )
                        }
                    }
                    if (sending) {
                        item(key = "streaming-status") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(9.dp))
                                Text(if (streamingAnswer.isBlank()) "Gemini is checking the complete syllabus…" else "Gemini is writing…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = question,
                                onValueChange = { question = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ask about a subject, semester, unit…") },
                                maxLines = 4,
                                shape = RoundedCornerShape(18.dp),
                                enabled = !sending
                            )
                            IconButton(
                                onClick = { val q = question.trim(); question = ""; vm.ask(q) },
                                enabled = !sending && question.isNotBlank(),
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send question", tint = if (question.isNotBlank() && !sending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text("Try an example", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            EXAMPLE_PROMPTS.forEach { example ->
                                AssistChip(
                                    onClick = { question = example },
                                    label = { Text(example, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val EXAMPLE_PROMPTS = listOf(
    "What's my Math syllabus for Semester 1?",
    "List all units of Physics",
    "What are Chemistry course outcomes?"
)

@Composable
private fun StatusCard(loadState: SyllabusLoadState, error: String?, onRetry: () -> Unit) {
    val statusText = when (loadState) {
        SyllabusLoadState.Idle -> "The official GNDEC syllabus will be prepared when you ask your first question."
        SyllabusLoadState.Loading -> "Preparing the official syllabus for offline, complete-topic answers…"
        SyllabusLoadState.Ready -> "Official syllabus ready · answers are grounded in the full document"
        is SyllabusLoadState.Error -> loadState.message
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            when (loadState) {
                SyllabusLoadState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Official GNDEC syllabus", fontWeight = FontWeight.SemiBold)
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (loadState is SyllabusLoadState.Error) {
                IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "Retry syllabus download") }
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Ask AI about your syllabus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Get complete units, topics, hours, and outcomes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(13.dp))
            Text("Ask a precise question about a subject and semester. Follow up in the same chat whenever you need clarification.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChatBubble(message: SyllabusChatMessageEntity) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f),
            colors = CardDefaults.cardColors(containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(if (isUser) "You" else "GNDEC syllabus assistant", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                if (isUser) Text(message.content, style = MaterialTheme.typography.bodyLarge) else MarkdownText(message.content)
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
}

@Composable
private fun MarkdownText(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        parseMarkdown(markdown).forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold
                )
                is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge)
                is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                is MarkdownBlock.Table -> MarkdownTable(block.rows)
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val columns = rows.maxOfOrNull { it.size }?.coerceAtMost(5) ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth().background(if (index == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)) {
                    repeat(columns) { column ->
                        Text(
                            inlineMarkdown(row.getOrNull(column).orEmpty()),
                            modifier = Modifier.width(145.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\\r", "").lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var paragraph = mutableListOf<String>()
    var index = 0
    fun flushParagraph() {
        val text = paragraph.joinToString(" ").trim()
        if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(text)
        paragraph = mutableListOf()
    }
    while (index < lines.size) {
        val line = lines[index].trim()
        if (line.isBlank()) {
            flushParagraph(); index++; continue
        }
        if (line.startsWith("|") && line.count { it == '|' } >= 2) {
            flushParagraph()
            val tableLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith("|") && lines[index].trim().count { it == '|' } >= 2) {
                val candidate = lines[index].trim()
                if (!candidate.matches(Regex("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?"))) tableLines += candidate
                index++
            }
            val rows = tableLines.map { it.trim('|').split('|').map { cell -> cell.trim() } }.filter { it.any(String::isNotBlank) }
            if (rows.isNotEmpty()) blocks += MarkdownBlock.Table(rows)
            continue
        }
        val heading = Regex("^(#{1,3})\\s+(.+)$").find(line)
        if (heading != null) {
            flushParagraph(); blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]); index++; continue
        }
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
            flushParagraph(); blocks += MarkdownBlock.Bullet(line.drop(2).trim()); index++; continue
        }
        paragraph += line
        index++
    }
    flushParagraph()
    return blocks
}

private fun inlineMarkdown(value: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex("\\*\\*(.+?)\\*\\*")
    var cursor = 0
    regex.findAll(value).forEach { match ->
        append(value.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    append(value.substring(cursor))
}

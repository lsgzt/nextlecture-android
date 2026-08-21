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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.foundation.layout.statusBarsPadding
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
    onBack: () -> Unit
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
    var chatStarted by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.newChat(); chatStarted = false; historyOpen = false }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (sessions.isEmpty()) {
                    Text("Your syllabus questions will appear here for offline continuation.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            Card(
                                onClick = { vm.selectSession(session.id); chatStarted = true; historyOpen = false },
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

    val firstChat = !chatStarted && !sending && selectedId == null && messages.isEmpty() && streamingAnswer.isBlank()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Exit syllabus AI")
                }
                Text(
                    "Syllabus",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Default.History, contentDescription = "Previous chats")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (firstChat) {
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
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Gemini is writing…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { message ->
                    item(key = "chat-error") {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
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
                            onClick = { val q = question.trim(); question = ""; chatStarted = true; vm.ask(q) },
                            enabled = !sending && question.isNotBlank(),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send question", tint = if (question.isNotBlank() && !sending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (firstChat) {
                        Spacer(Modifier.height(6.dp))
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
    Column(
        Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Ask AI about your syllabus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            "Ask about a subject, semester, unit, hours, or outcomes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ChatBubble(message: SyllabusChatMessageEntity) {
    val isUser = message.role == "user"
    if (isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                modifier = Modifier.fillMaxWidth(0.88f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("You", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(message.content, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("GNDEC syllabus assistant", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            RichMarkdownText(message.content)
        }
    }
}


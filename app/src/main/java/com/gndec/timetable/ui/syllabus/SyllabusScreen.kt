package com.gndec.timetable.ui.syllabus

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.gndec.timetable.net.AiProvider
import com.gndec.timetable.net.AiStreamEvent
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** The turn currently being generated: reasoning and answer arrive as two independent streams. */
data class StreamingReply(val reasoning: String = "", val answer: String = "")

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
    private val _streaming = MutableStateFlow(StreamingReply())
    val streaming: StateFlow<StreamingReply> = _streaming.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Display name of the backend that will answer, so the status line names the real one. */
    val providerLabel: StateFlow<String> = container.settings.flow
        .map { it.provider.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProvider.GEMINI.label)

    private var requestJob: Job? = null

    init {
        viewModelScope.launch { runCatching { manager.ensureReady() }.onFailure { _error.value = it.message } }
    }

    fun selectSession(id: String) {
        _error.value = null
        _selectedSessionId.value = id
    }

    fun newChat() {
        requestJob?.cancel()
        requestJob = null
        _sending.value = false
        _error.value = null
        _streaming.value = StreamingReply()
        _selectedSessionId.value = null
    }

    fun ask(question: String) {
        if (_sending.value || question.isBlank()) return
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _sending.value = true
            _error.value = null
            _streaming.value = StreamingReply()
            val reasoning = StringBuilder()
            val answer = StringBuilder()
            var lastUiUpdateNanos = 0L
            val uiIntervalNanos = 33_000_000L
            val streamUpdates = Channel<StreamingReply>(capacity = Channel.CONFLATED)
            val streamCollector = launch(Dispatchers.Main.immediate) {
                for (snapshot in streamUpdates) _streaming.value = snapshot
            }
            try {
                val result = manager.sendStreaming(_selectedSessionId.value, question) { event ->
                    when (event) {
                        is AiStreamEvent.Reasoning -> reasoning.append(event.delta)
                        is AiStreamEvent.Answer -> answer.append(event.delta)
                        // A recitation retry replays the request; the discarded attempt's
                        // deltas must go rather than being shown twice.
                        is AiStreamEvent.Restart -> {
                            reasoning.clear()
                            answer.clear()
                        }
                        is AiStreamEvent.Meta -> Unit
                    }
                    val now = System.nanoTime()
                    if (lastUiUpdateNanos == 0L || now - lastUiUpdateNanos >= uiIntervalNanos) {
                        lastUiUpdateNanos = now
                        streamUpdates.trySend(StreamingReply(reasoning.toString(), answer.toString()))
                    }
                }
                // The answer is sent as the manager trimmed it, so the final frame matches
                // the saved message exactly and the handover is invisible.
                streamUpdates.trySend(StreamingReply(reasoning.toString(), result.answer))
                streamUpdates.close()
                streamCollector.join()
                _selectedSessionId.value = result.sessionId
                _streaming.value = StreamingReply()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                _error.value = error.message ?: "Could not get an answer from Gemini"
            } finally {
                streamUpdates.close()
                streamCollector.cancel()
                _sending.value = false
                requestJob = null
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
    onBack: () -> Unit,
    onOpenPreviousYearPapers: () -> Unit
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
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val providerLabel by vm.providerLabel.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    var chatStarted by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var aiSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun startNewChat() {
        historyOpen = false
        deleteId = null
        question = ""
        chatStarted = false
        vm.newChat()
    }

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { startNewChat() }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                }
                OutlinedButton(
                    onClick = { historyOpen = false; onOpenPreviousYearPapers() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Previous year papers")
                }
                Spacer(Modifier.height(8.dp))
                if (sessions.isEmpty()) {
                    Text("Your syllabus questions will appear here for offline continuation.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            val sessionInteraction = remember { MutableInteractionSource() }
                            val sessionContainer by animateColorAsState(
                                targetValue = if (session.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                animationSpec = motionTween(Motion.Normal),
                                label = "sessionCardContainer"
                            )
                            Card(
                                onClick = { vm.selectSession(session.id); chatStarted = true; historyOpen = false },
                                modifier = Modifier.fillMaxWidth().animateItem().pressFeedback(sessionInteraction),
                                interactionSource = sessionInteraction,
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = sessionContainer),
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

    if (aiSettingsOpen) {
        AiQuickSettingsSheet(container = container, onDismiss = { aiSettingsOpen = false })
    }

    val firstChat = !chatStarted && !sending && selectedId == null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Title is centered on the full screen width; actions overlay start/end so
            // the asymmetric back vs history+pdf buttons cannot pull "Syllabus" left.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    "Syllabus",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Exit syllabus AI")
                }
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick AI settings: provider, key and model without the detour
                    // through Settings — the chat is unusable without them.
                    IconButton(onClick = { aiSettingsOpen = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "AI settings")
                    }
                    IconButton(onClick = { historyOpen = true }) {
                        Icon(Icons.Default.History, contentDescription = "Previous chats")
                    }
                    SourcePdfButton()
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (firstChat) {
                    item { WelcomeCard(onOpenPreviousYearPapers) }
                } else {
                    items(messages, key = { it.id }) { message ->
                        Box(Modifier.animateItem()) { ChatBubble(message) }
                    }
                }
                // The manager saves the finished answer before this screen clears the live
                // buffer, so the saved copy arriving is the signal to drop the live bubble.
                // Matching the text rather than just "the last message is from the model"
                // keeps a follow-up turn in an existing chat streaming normally, and leaves a
                // cancelled or failed turn — which is never saved — on screen.
                val savedAnswer = messages.lastOrNull { it.role != "user" }?.content
                val showStreaming = (streaming.answer.isNotBlank() || streaming.reasoning.isNotBlank()) &&
                    savedAnswer != streaming.answer
                if (showStreaming) {
                    item(key = "streaming-answer") {
                        Box(Modifier.fillMaxWidth().animateItem()) {
                            AssistantReply(
                                reasoning = streaming.reasoning,
                                answer = streaming.answer,
                                streaming = true
                            )
                        }
                    }
                }
                if (sending) {
                    item(key = "streaming-status") {
                        Box(Modifier.fillMaxWidth().animateItem()) {
                            // A thinking model reasons before it answers; a plain model never
                            // produces reasoning and goes straight to writing.
                            val provider = providerLabel
                            val status = if (streaming.reasoning.isNotBlank() && streaming.answer.isBlank()) {
                                "$provider is thinking…"
                            } else {
                                "$provider is writing…"
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let { message ->
                    item(key = "chat-error") {
                        Box(Modifier.fillMaxWidth().animateItem()) {
                            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
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
                        val canSend = question.isNotBlank() && !sending
                        IconButton(
                            onClick = { val q = question.trim(); question = ""; chatStarted = true; vm.ask(q) },
                            enabled = canSend,
                            modifier = Modifier.size(52.dp).background(
                                if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                CircleShape
                            )
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send question", tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun WelcomeCard(onOpenPreviousYearPapers: () -> Unit) {
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
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onOpenPreviousYearPapers,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Previous year papers")
        }
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
        // Saved turns render through the same composable as the live one, so reasoning
        // collapses and the answer settles exactly as it did while streaming.
        AssistantReply(reasoning = message.reasoning.orEmpty(), answer = message.content, streaming = false)
    }
}


/** Official syllabus source document (Applied Sciences upload). */
private const val SYLLABUS_SOURCE_URL = "https://appsc.gndec.ac.in/sites/default/files/2026-03/ss%20and%20Syllabus%20sem1%2C2%20Dec%202025%20unsigned.pdf"

@Composable
private fun SourcePdfButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SYLLABUS_SOURCE_URL)))
    }) {
        Icon(Icons.Default.OpenInNew, contentDescription = "Open official syllabus PDF")
    }
}

/**
 * Quick AI settings sheet: provider, API key and model in one place, reached
 * directly from the Syllabus AI header instead of the long detour through
 * Settings. Edits the same preferences and encrypted key store the Settings
 * screen uses, so both views always agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiQuickSettingsSheet(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var keyStatus by remember { mutableStateOf<Map<AiProvider, Boolean>>(emptyMap()) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var customModel by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The encrypted store is disk work; never read it on the composition thread.
    suspend fun refreshKeyStatus() = withContext(Dispatchers.IO) {
        AiProvider.values().associateWith { runCatching { container.keys.getApiKey(it) != null }.getOrDefault(false) }
    }
    LaunchedEffect(Unit) { keyStatus = refreshKeyStatus() }

    val cfg = settings
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (cfg == null) {
            // One frame before DataStore emits — keep the sheet from flashing empty content.
            Box(Modifier.fillMaxWidth().height(120.dp))
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Provider, API key and model for syllabus answers. Keys are stored encrypted on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            // Provider
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TealOutlineButton(cfg.provider.label, modifier = Modifier, onClick = { providerMenu = true })
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    AiProvider.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                providerMenu = false
                                if (option != cfg.provider) {
                                    // IDs belong to the provider just left; a stale list
                                    // would offer models the new one does not have.
                                    models = emptyList()
                                    scope.launch { withContext(Dispatchers.IO) { container.settings.setAiProvider(option.id) } }
                                    message = "AI provider set to ${option.label}"
                                }
                            }
                        )
                    }
                }
            }
            Text(
                "Each provider keeps its own key and model, so switching back restores your earlier choice.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            // API key
            val saved = keyStatus[cfg.provider] == true
            OutlinedTextField(
                keyInput, { keyInput = it },
                label = { Text(if (saved) "${cfg.provider.label} API key · saved" else "${cfg.provider.label} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (keyInput.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) { container.keys.setApiKey(cfg.provider, keyInput) }
                            keyStatus = refreshKeyStatus()
                            message = "${cfg.provider.label} API key saved (stored encrypted on this device)"
                            keyInput = ""
                            busy = false
                        }
                    },
                    enabled = !busy && keyInput.isNotBlank()
                ) { Text("Save key") }
                if (saved) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                withContext(Dispatchers.IO) { container.keys.removeApiKey(cfg.provider) }
                                keyStatus = refreshKeyStatus()
                                message = "${cfg.provider.label} API key removed"
                                busy = false
                            }
                        },
                        enabled = !busy
                    ) { Text("Remove key") }
                }
            }

            // Model
            Text("${cfg.provider.label} model", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TealOutlineButton(cfg.model, modifier = Modifier, onClick = { modelMenu = true })
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(text = { Text("Load models first") }, onClick = { modelMenu = false })
                    } else {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    modelMenu = false
                                    scope.launch { withContext(Dispatchers.IO) { container.settings.setModel(model) } }
                                    message = "Model set to $model"
                                }
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val key = withContext(Dispatchers.IO) { container.keys.getApiKey(cfg.provider) }
                            if (key == null) {
                                message = "Save a ${cfg.provider.label} API key first"
                            } else {
                                val list = withContext(Dispatchers.IO) { container.aiClients[cfg.provider].listModels(key).map { it.id } }
                                models = list
                                message = if (list.isEmpty()) "No models returned" else "✓ ${list.size} models loaded"
                            }
                        } catch (e: Exception) {
                            message = "Couldn't fetch models (${e.message})"
                        }
                        busy = false
                    }
                }, enabled = !busy) { Text("Refresh models") }
            }

            // Custom model ID
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    customModel, { customModel = it },
                    label = { Text("Custom model ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        if (customModel.isNotBlank()) {
                            val m = customModel.trim()
                            scope.launch { withContext(Dispatchers.IO) { container.settings.setModel(m) } }
                            customModel = ""
                            message = "Model set to $m"
                        }
                    },
                    enabled = customModel.isNotBlank()
                ) { Text("Save") }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.data.db.SyllabusChatDao
import com.gndec.timetable.data.db.SyllabusChatMessageEntity
import com.gndec.timetable.data.db.SyllabusChatSessionEntity
import com.gndec.timetable.data.prefs.SettingsManager
import com.gndec.timetable.net.AiClients
import com.gndec.timetable.net.AiRecitationException
import com.gndec.timetable.net.AiStreamEvent
import com.gndec.timetable.net.ChatMessage
import com.gndec.timetable.net.Net
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

sealed interface SyllabusLoadState {
    data object Idle : SyllabusLoadState
    data object Loading : SyllabusLoadState
    data object Ready : SyllabusLoadState
    data class Error(val message: String) : SyllabusLoadState
}

data class SyllabusChatResult(val sessionId: String, val answer: String, val reasoning: String = "")

class SyllabusManager(
    private val context: Context,
    private val settings: SettingsManager,
    private val keys: com.gndec.timetable.data.prefs.SecureKeyStore,
    private val clients: AiClients,
    private val chats: SyllabusChatDao
) {
    companion object {
        const val OFFICIAL_SYLLABUS_URL = "https://appsc.gndec.ac.in/sites/default/files/2026-03/ss%20and%20Syllabus%20sem1%2C2%20Dec%202025%20unsigned.pdf"
        private const val PDF_FILE_NAME = "gndec_official_syllabus.pdf"
        private const val TEXT_FILE_NAME = "gndec_official_syllabus.txt"
        private const val MAX_TITLE_LENGTH = 72
        private const val MAX_CONTINUATION_ROUNDS = 8
        private val RECITATION_RECOVERY_PROMPT = """

            IMPORTANT RECITATION RECOVERY: The previous generation was stopped because it resembled source text too closely. Answer again in your own words. Do not quote or reproduce long sentences from the document. Keep the complete unit and topic coverage, but use concise paraphrases, short labels, hours, and clearly structured summaries.
        """.trimIndent()
    }

    private val mutex = Mutex()
    private val pdfFile by lazy { File(context.filesDir, PDF_FILE_NAME) }
    private val textFile by lazy { File(context.filesDir, TEXT_FILE_NAME) }

    /**
     * Course index over [textFile], or null when the document could not be split into
     * courses. Null is a supported state, not a failure: the whole document is then sent,
     * which is slower but exactly as correct as before retrieval existed.
     */
    @Volatile
    private var index: SyllabusIndex? = null
    @Volatile
    private var indexBuilt = false

    private val _loadState = MutableStateFlow<SyllabusLoadState>(SyllabusLoadState.Idle)
    val loadState: StateFlow<SyllabusLoadState> = _loadState.asStateFlow()

    init {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    fun observeSessions(): Flow<List<SyllabusChatSessionEntity>> = chats.observeSessions()

    fun observeMessages(sessionId: String): Flow<List<SyllabusChatMessageEntity>> = chats.observeMessages(sessionId)

    suspend fun ensureReady() = mutex.withLock {
        if (textFile.isFile && textFile.length() > 1000L) {
            _loadState.value = SyllabusLoadState.Ready
            return@withLock
        }
        _loadState.value = SyllabusLoadState.Loading
        try {
            if (!pdfFile.isFile || pdfFile.length() < 1000L) downloadPdf()
            extractText()
            // The cache was just replaced, so any index built from the old text is stale.
            indexBuilt = false
            _loadState.value = SyllabusLoadState.Ready
        } catch (error: Exception) {
            _loadState.value = SyllabusLoadState.Error(error.message ?: "Could not prepare the official syllabus")
            throw error
        }
    }

    /**
     * The course index, built once per extracted document.
     *
     * A parse that does not yield enough courses leaves [index] null, which is not an error:
     * the caller then sends the whole document, so an unexpected layout costs tokens rather
     * than answers.
     */
    private suspend fun courseIndex(): SyllabusIndex? {
        if (indexBuilt) return index
        return mutex.withLock {
            if (indexBuilt) return@withLock index
            index = SyllabusIndex.parse(readExtractedText())
            indexBuilt = true
            index
        }
    }

    suspend fun send(sessionId: String?, question: String): SyllabusChatResult =
        sendStreaming(sessionId, question) { }

    /**
     * Runs one syllabus turn, forwarding the model's classified stream to [onEvent] as it
     * arrives. Reasoning and answer text are accumulated separately and returned apart, so
     * a thinking model and a plain model can share this single path.
     */
    suspend fun sendStreaming(
        sessionId: String?,
        question: String,
        onEvent: (AiStreamEvent) -> Unit
    ): SyllabusChatResult {
        val prompt = question.trim()
        require(prompt.isNotBlank()) { "Please enter a syllabus question" }
        ensureReady()
        val config = settings.flow.first()
        val provider = config.provider
        val client = clients[provider]
        val apiKey = keys.getApiKey(provider)?.trim().orEmpty()
        require(apiKey.isNotBlank()) { "Add and save your ${provider.label} API key in Settings first" }
        val branch = config.branch.trim().ifBlank { "not saved; answer using the common first-year syllabus and explain that branch-specific matching needs a saved branch" }
        val now = System.currentTimeMillis()
        val actualSessionId = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        if (sessionId.isNullOrBlank()) {
            chats.insertSession(
                SyllabusChatSessionEntity(
                    id = actualSessionId,
                    title = prompt.replace(Regex("\\s+"), " ").take(MAX_TITLE_LENGTH),
                    createdAt = now,
                    updatedAt = now,
                    branch = branch
                )
            )
        } else if (chats.getSession(actualSessionId) == null) {
            chats.insertSession(
                SyllabusChatSessionEntity(actualSessionId, prompt.take(MAX_TITLE_LENGTH), now, now, branch)
            )
        }
        chats.insertMessage(SyllabusChatMessageEntity(sessionId = actualSessionId, role = "user", content = prompt, timestamp = now))
        val history = chats.getMessages(actualSessionId).map { ChatMessage(it.role, it.content) }
        val systemPrompt = buildSystemPrompt(
            branch = branch,
            question = prompt,
            history = history,
            contextWindow = client.contextWindow(config.model, apiKey)
        )
        var finishReason: String? = null
        val reasoningBuilder = StringBuilder()
        suspend fun streamAnswer(requestMessages: List<ChatMessage>, promptSuffix: String = ""): String =
            client.chatStream(
                messages = requestMessages,
                systemPrompt = systemPrompt + promptSuffix,
                model = config.model,
                apiKey = apiKey,
                onEvent = { event ->
                    if (event is AiStreamEvent.Reasoning) reasoningBuilder.append(event.delta)
                    onEvent(event)
                },
                onFinishReason = { finishReason = it }
            )

        val answerBuilder = StringBuilder(try {
            streamAnswer(history)
        } catch (_: AiRecitationException) {
            // A provider may stop when the answer resembles long source passages. Retry once
            // with a paraphrase-only instruction so the student still receives a complete answer.
            finishReason = null
            // The discarded attempt is replaced wholesale, so its partial reasoning no longer
            // describes the answer that is actually shown.
            reasoningBuilder.clear()
            // Its deltas are already on screen, so tell the consumer to drop them before the
            // replacement stream starts.
            onEvent(AiStreamEvent.Restart)
            streamAnswer(history, RECITATION_RECOVERY_PROMPT)
        })

        // A long syllabus answer may legitimately end at MAX_TOKENS. Ask for continuation
        // using the already generated text as model history, then append each delta live.
        var continuationRound = 0
        while (finishReason == "MAX_TOKENS" && continuationRound < MAX_CONTINUATION_ROUNDS) {
            continuationRound++
            val continuationHistory = history + listOf(
                ChatMessage("model", answerBuilder.toString()),
                ChatMessage(
                    "user",
                    "Continue exactly where the previous answer stopped. Do not repeat any earlier text. " +
                        "Finish every incomplete table, list, code block, or syllabus section and preserve valid Markdown."
                )
            )
            finishReason = null
            val continuation = streamAnswer(continuationHistory)
            if (continuation.isBlank()) break
            if (reasoningBuilder.isNotEmpty()) reasoningBuilder.append("\n\n")
            answerBuilder.append(continuation)
        }

        val answer = answerBuilder.toString()
        val reasoning = reasoningBuilder.toString().trim()
        val answerTime = System.currentTimeMillis()
        chats.insertMessage(
            SyllabusChatMessageEntity(
                sessionId = actualSessionId,
                role = "model",
                content = answer,
                timestamp = answerTime,
                reasoning = reasoning.takeIf { it.isNotBlank() }
            )
        )
        chats.touchSession(actualSessionId, chats.getSession(actualSessionId)?.title ?: prompt.take(MAX_TITLE_LENGTH), answerTime)
        return SyllabusChatResult(actualSessionId, answer, reasoning)
    }

    suspend fun deleteSession(sessionId: String) {
        chats.deleteMessages(sessionId)
        chats.deleteSession(sessionId)
    }

    suspend fun clearDocumentCache() = mutex.withLock {
        pdfFile.delete()
        textFile.delete()
        // The document is gone, so the index built from it must not outlive it.
        index = null
        indexBuilt = false
        _loadState.value = SyllabusLoadState.Idle
    }

    private suspend fun downloadPdf() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(OFFICIAL_SYLLABUS_URL)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "GNDEC-Timetable/2.0")
            .get()
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Official syllabus returned HTTP ${response.code}")
            val body = response.body ?: error("Official syllabus returned an empty response")
            val temporary = File(context.cacheDir, "$PDF_FILE_NAME.part")
            body.byteStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            if (temporary.length() < 1000L) error("Official syllabus file was incomplete")
            if (!temporary.renameTo(pdfFile)) {
                temporary.copyTo(pdfFile, overwrite = true)
                temporary.delete()
            }
        }
    }

    private suspend fun extractText() = withContext(Dispatchers.IO) {
        val extracted = PDDocument.load(pdfFile).use { document ->
            PDFTextStripper().apply {
                sortByPosition = true
                addMoreFormatting = true
            }.getText(document)
        }.replace("\\r\\n", "\\n").replace("\\r", "\\n").trim()
        if (extracted.length < 1000) error("Could not extract usable syllabus text")
        val temporary = File(context.cacheDir, "$TEXT_FILE_NAME.part")
        temporary.writeText(extracted, Charsets.UTF_8)
        if (!temporary.renameTo(textFile)) {
            temporary.copyTo(textFile, overwrite = true)
            temporary.delete()
        }
    }

    private suspend fun readExtractedText(): String = withContext(Dispatchers.IO) {
        textFile.readText(Charsets.UTF_8)
    }

    private suspend fun buildSystemPrompt(
        branch: String,
        question: String,
        history: List<ChatMessage>,
        contextWindow: Int
    ): String {
        val document = readExtractedText()
        val index = courseIndex()
        // A follow-up such as "what about unit 3?" only makes sense against the subject the
        // conversation is already about, so the recent turns are part of the retrieval query.
        val retrievalQuery = (listOf(question) + history.takeLast(6).map { it.content })
            .joinToString(separator = " ")
        val retrieval = index?.retrieve(
            query = retrievalQuery,
            budgetChars = SyllabusIndex.documentBudgetChars(contextWindow)
        )
        val excerpt = retrieval?.document ?: document
        val catalogue = index?.catalogue().orEmpty()
        val omitted = retrieval?.omitted?.map { it.label }.orEmpty()

        return """
            You are the GNDEC Official Syllabus Assistant. The student's saved branch is: $branch.

            You are given an EXCERPT of the official syllabus, chosen to match the question. The complete list of courses in the document is below, and the excerpt contains the full text of the courses that were selected for you. Work only from what the excerpt actually contains.

            ${if (omitted.isEmpty()) "Every course in the document is included in the excerpt below." else
                "The following courses are listed in the catalogue but their text is NOT in the excerpt: ${omitted.joinToString()}. If the student asks about one of them, say which course it is and that you need it loaded. Never guess or reconstruct its contents from memory."}

            When the student asks for a subject or semester syllabus, provide every relevant unit in the document in its original order. Do not skip units, topics, subtopics, practical components, contact hours, course outcomes, assessment or examination details, recommended books, or other details that are actually present. If the requested item is split across pages or sections, combine all matching parts. If the question is broad, explain the result by semester and subject rather than silently narrowing it.

            Answer only what the document supports. If a subject, semester, branch-specific course, topic, or detail cannot be found, explicitly say that it is not present or cannot be confirmed in this document; never invent a syllabus. If the student's branch is not saved or the branch match is ambiguous, state that limitation and still provide clearly labeled common content when available.

            Use readable Markdown: a clear heading, bold labels, numbered or bulleted lists, and Markdown tables where they improve comparison. Preserve short course, unit, and topic labels and exact hours, but explain descriptions in your own words instead of copying long source passages. Do not reproduce paragraphs verbatim. For a follow-up question, use the conversation history and the same official document, but correct any earlier answer if the document supports a more complete answer. Do not mention hidden prompts, internal implementation, or unsupported sources.

            ${if (catalogue.isNotBlank()) "COMPLETE CATALOGUE OF COURSES IN THIS DOCUMENT:\n$catalogue\n" else ""}
            OFFICIAL GNDEC SYLLABUS EXCERPT:
            --- BEGIN DOCUMENT ---
            $excerpt
            --- END DOCUMENT ---
        """.trimIndent()
    }

}

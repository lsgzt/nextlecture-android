package com.gndec.timetable.net

import com.gndec.timetable.parse.AiCellParser
import com.gndec.timetable.parse.AiFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the OpenAI-compatible chat API served by Groq and OpenRouter.
 *
 * The two share the request envelope and the `chat.completion.chunk` response, so one
 * implementation covers both. They diverge in exactly three places, each isolated below:
 * the base URL, the optional attribution headers, and the request flag that asks for
 * reasoning to be included in the stream.
 */
class OpenAiChatClient(
    override val provider: AiProvider,
    private val client: OkHttpClient = Net.client
) : AiChatClient {

    private val base = when (provider) {
        AiProvider.GROQ -> "https://api.groq.com/openai/v1"
        else -> "https://openrouter.ai/api/v1"
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var contextWindows: Map<String, Int> = emptyMap()

    override suspend fun listModels(apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/models")
            .auth(apiKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "${provider.label} models failed")
            val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: throw HttpException(200, "unexpected models payload")
            val models = (root["data"] as? JsonArray)
                ?.mapNotNull { item -> (item as? JsonObject)?.toModel() }
                .orEmpty()
                .distinctBy { it.id }
                .sortedBy { it.id }
            // Cache the windows while the catalogue is already in hand; asking for a context
            // window should not cost a second round trip.
            if (models.isNotEmpty()) contextWindows = models.associate { it.id to it.contextWindow }
            models
        }
    }

    override suspend fun contextWindow(model: String, apiKey: String): Int {
        contextWindows[model]?.let { return it }
        // A failure here is not fatal: the caller treats 0 as unknown and keeps the whole
        // document, which is slower but still correct.
        runCatching { listModels(apiKey) }
        return contextWindows[model] ?: 0
    }

    override suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean> = try {
        val models = listModels(apiKey)
        true to models.any { it.id == model.trim() }
    } catch (_: Exception) {
        false to false
    }

    /** Normalises one ambiguous timetable cell using the provider's JSON mode. */
    override suspend fun normalize(cell: String, model: String, apiKey: String): AiFields =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", AiCellParser.SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", cell)
                    })
                })
                put("response_format", buildJsonObject { put("type", "json_object") })
                put("max_tokens", 2048)
            }
            val req = Request.Builder()
                .url("$base/chat/completions")
                .auth(apiKey)
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "${provider.label} generation failed")
                val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
                val root = json.parseToJsonElement(body) as? JsonObject
                    ?: throw HttpException(200, "unexpected ${provider.label} payload")
                val text = messageContent(root) ?: throw HttpException(200, diagnostic(root))
                AiCellParser.parse(text) ?: throw HttpException(200, "AI output failed validation")
            }
        }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiKey: String,
        onEvent: (AiStreamEvent) -> Unit,
        onFinishReason: (String?) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(messages.any { it.content.isNotBlank() }) { "At least one chat message is required" }
        val complete = StringBuilder()
        val finishReasons = mutableSetOf<String>()

        val emit: (List<AiStreamEvent>) -> Unit = { events ->
            events.forEach { event ->
                when (event) {
                    // Only answer text is the returned value; reasoning is surfaced separately
                    // so it can never leak into the persisted answer.
                    is AiStreamEvent.Answer -> complete.append(event.delta)
                    is AiStreamEvent.Meta -> event.finishReason?.let { finishReasons += it }
                    is AiStreamEvent.Reasoning, AiStreamEvent.Restart -> Unit
                }
                onEvent(event)
            }
        }

        suspend fun runStream(requestReasoning: Boolean) {
            val payload = buildChatPayload(messages, systemPrompt, model, requestReasoning)
            val req = Request.Builder()
                .url("$base/chat/completions")
                .auth(apiKey)
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.take(500).orEmpty()
                    throw HttpException(
                        resp.code,
                        "${provider.label} streaming failed${if (detail.isNotBlank()) ": $detail" else ""}"
                    )
                }
                val body = resp.body ?: throw HttpException(resp.code, "empty streaming body")
                val parser = OpenAiStreamParser(json)
                // A reader per attempt: a replayed request must not resume a half-read frame
                // from the attempt it is replacing.
                val frames = SseFrameReader(parser::parse)
                body.charStream().buffered().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        frames.accept(line, emit)
                    }
                    frames.flush(emit)
                }
            }
        }

        try {
            runStream(requestReasoning = true)
        } catch (error: HttpException) {
            // A model without reasoning can reject the reasoning flag outright. That failure
            // is an HTTP error raised before a single token was streamed, so replaying the
            // identical request without it is safe and leaves plain models behaving exactly
            // as they did before reasoning support existed.
            if (error.code != 400 || complete.isNotEmpty() || !error.rejectedReasoningFlag()) throw error
            runStream(requestReasoning = false)
        }

        val finishReason = finishReasons.lastOrNull()
        onFinishReason(finishReason)
        val answer = complete.toString().trimEnd()
        if (answer.isNotBlank()) return@withContext answer
        if (finishReasons.contains("content_filter")) {
            throw AiRecitationException("${provider.label} stopped the answer because it was too close to the source text (content_filter)")
        }
        throw HttpException(
            200,
            "${provider.label} returned HTTP 200 without answer text${finishReasons.takeIf { it.isNotEmpty() }?.let { " (finishReason=${it.joinToString()})" } ?: ""}"
        )
    }

    private fun buildChatPayload(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        includeReasoning: Boolean
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("messages", buildJsonArray {
            // The system prompt is a message of its own; this API has no separate
            // system-instruction field the way Gemini does.
            if (systemPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.filter { it.content.isNotBlank() }.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role.equals("model", ignoreCase = true) || message.role.equals("assistant", ignoreCase = true)) "assistant" else "user")
                    put("content", message.content)
                })
            }
        })
        // No output-token cap is sent: the safe value differs per model, and a cap the model
        // cannot honour is a 400. Providers truncate on their own terms instead, which still
        // surfaces as a finish reason the continuation loop understands.
        if (includeReasoning) putReasoningFlag(this)
    }

    /**
     * Asks the provider to include reasoning in the stream, in its own dialect.
     *
     * Groq exposes `include_reasoning`; OpenRouter's unified parameter is `reasoning`, where
     * `exclude: false` is the documented way to keep reasoning in the response. Both are
     * plain request fields, so a provider that does not support them simply ignores them
     * and a model that rejects them is retried without this object.
     */
    private fun putReasoningFlag(payload: JsonObjectBuilder) {
        if (provider == AiProvider.GROQ) {
            payload.put("include_reasoning", true)
        } else {
            payload.put("reasoning", buildJsonObject { put("exclude", false) })
        }
    }

    private fun Request.Builder.auth(apiKey: String): Request.Builder {
        header("Authorization", "Bearer $apiKey")
        if (provider == AiProvider.OPENROUTER) {
            // Optional attribution header; OpenRouter shows the app name on its rankings page.
            header("X-OpenRouter-Title", "NextLecture")
        }
        return this
    }

    private fun JsonObject.toModel(): AiModel? {
        val id = (this["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        // Groq reports `context_window`, OpenRouter `context_length`; absent means unknown.
        val window = (this["context_window"] as? JsonPrimitive)?.intOrNull
            ?: (this["context_length"] as? JsonPrimitive)?.intOrNull
            ?: 0
        return AiModel(id, (this["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id, window)
    }

    private fun messageContent(root: JsonObject): String? =
        (root["choices"] as? JsonArray)?.mapNotNull { choiceElement ->
            ((choiceElement as? JsonObject)?.get("message") as? JsonObject)
                ?.get("content") as? JsonPrimitive
        }?.joinToString(separator = "")?.takeIf { it.isNotBlank() }

    private fun diagnostic(root: JsonObject): String {
        val reason = (root["choices"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("finish_reason") as? JsonPrimitive)?.content }
            .orEmpty()
        return buildString {
            append("${provider.label} returned HTTP 200 without answer text")
            if (reason.isNotEmpty()) append(" (finishReason=${reason.joinToString()})")
        }
    }

    /**
     * Whether a 400 blames the reasoning flag. The message carries the provider's own error
     * detail, and matching on the field name keeps the retry provider-agnostic — no model is
     * named or special-cased here.
     */
    private fun HttpException.rejectedReasoningFlag(): Boolean =
        message.orEmpty().contains("reasoning", ignoreCase = true)
}

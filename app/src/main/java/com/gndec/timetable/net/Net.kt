package com.gndec.timetable.net

import com.gndec.timetable.parse.AiCellParser
import com.gndec.timetable.parse.AiFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The provider stopped the answer because it resembled the source text too closely. The
 * student should get a paraphrased answer instead, so the request is replayed with a
 * paraphrase-only instruction.
 */
class AiRecitationException(message: String) : Exception(message)

data class ChatMessage(val role: String, val content: String)

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/** Direct Gemini API client used with a key supplied and encrypted on the user’s device. */
class GeminiClient(private val client: OkHttpClient = Net.client) : AiChatClient {

    override val provider: AiProvider get() = AiProvider.GEMINI

    private val base = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var contextWindows: Map<String, Int> = emptyMap()

    /** Returns bare model IDs, for example `gemini-3.6-flash`. */
    override suspend fun listModels(apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "models failed")
            val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
            val data = json.parseToJsonElement(body) as? JsonObject
                ?: throw HttpException(200, "unexpected models payload")
            val arr = data["models"] as? JsonArray ?: return@withContext emptyList()
            val models = arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val supported = (obj["supportedGenerationMethods"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                if (supported.isNotEmpty() && "generateContent" !in supported) return@mapNotNull null
                val id = (obj["name"] as? JsonPrimitive)?.content
                    ?.removePrefix("models/")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AiModel(
                    id = id,
                    label = (obj["displayName"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id,
                    contextWindow = (obj["inputTokenLimit"] as? JsonPrimitive)?.intOrNull ?: 0
                )
            }.distinctBy { it.id }.sortedBy { it.id }
            // Cache the windows while the catalogue is already in hand; asking for a context
            // window should not cost a second round trip.
            if (models.isNotEmpty()) contextWindows = models.associate { it.id to it.contextWindow }
            models
        }
    }

    override suspend fun contextWindow(model: String, apiKey: String): Int {
        val id = normalizeModel(model)
        contextWindows[id]?.let { return it }
        runCatching { listModels(apiKey) }
        return contextWindows[id] ?: 0
    }

    /** Normalizes one ambiguous timetable cell using Gemini JSON mode. */
    override suspend fun normalize(cell: String, model: String, apiKey: String): AiFields =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", AiCellParser.SYSTEM_PROMPT) })
                    })
                })
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", cell) })
                        })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 2048)
                })
            }
            val req = Request.Builder()
                .url(generateUrl(model))
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "Gemini generation failed")
                val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
                val root = json.parseToJsonElement(body) as? JsonObject
                    ?: throw HttpException(200, "unexpected Gemini payload")
                val text = extractTextOrNull(root) ?: throw HttpException(200, diagnostic(root))
                AiCellParser.parse(text) ?: throw HttpException(200, "AI output failed validation")
            }
        }

    /** Sends a complete multi-turn response. Syllabus uses chatStream for progressive UI updates. */
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiKey: String
    ): String = chatStream(messages, systemPrompt, model, apiKey, onEvent = {})

    /**
     * Streams Gemini's REST SSE response. Every delta is classified by the provider as
     * reasoning or answer text and delivered immediately on the IO thread, while the returned
     * string contains the complete answer for persistence. Models without thinking simply
     * never emit [AiStreamEvent.Reasoning].
     */
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

        suspend fun runStream(requestThoughts: Boolean) {
            val payload = buildChatPayload(messages, systemPrompt, requestThoughts)
            val req = Request.Builder()
                .url(streamUrl(model))
                .header("x-goog-api-key", apiKey)
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.take(500).orEmpty()
                    throw HttpException(resp.code, "Gemini streaming failed${if (detail.isNotBlank()) ": $detail" else ""}")
                }
                val body = resp.body ?: throw HttpException(resp.code, "empty streaming body")
                // A reader per attempt: a replayed request must not resume a half-read frame
                // from the attempt it is replacing.
                val parser = GeminiStreamParser(json)
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
            runStream(requestThoughts = true)
        } catch (error: HttpException) {
            // A model without thinking can reject the whole `thinkingConfig` object. That
            // failure is an HTTP error raised before a single token was streamed, so
            // replaying the identical request without it is safe and leaves plain models
            // behaving exactly as they did before reasoning support existed.
            if (error.code != 400 || complete.isNotEmpty() || !error.rejectedThinkingConfig()) throw error
            runStream(requestThoughts = false)
        }

        val finishReason = finishReasons.lastOrNull()
        onFinishReason(finishReason)
        val answer = complete.toString().trimEnd()
        if (answer.isNotBlank()) return@withContext answer
        if (finishReasons.contains("RECITATION")) {
            throw AiRecitationException("Gemini stopped because the answer was too close to source text (RECITATION)")
        }
        throw HttpException(200, "Gemini returned HTTP 200 without answer text${finishReasons.takeIf { it.isNotEmpty() }?.let { " (finishReason=${it.joinToString()})" } ?: ""}")
    }

    /** Validates the API key and whether the configured model is available for generation. */
    override suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean> = try {
        val models = listModels(apiKey)
        true to models.any { it.id == normalizeModel(model) }
    } catch (_: Exception) {
        false to false
    }

    private fun buildChatPayload(
        messages: List<ChatMessage>,
        systemPrompt: String,
        includeThoughts: Boolean
    ): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", systemPrompt) })
            })
        })
        put("contents", buildJsonArray {
            messages.filter { it.content.isNotBlank() }.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role.equals("model", ignoreCase = true) || message.role.equals("assistant", ignoreCase = true)) "model" else "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", message.content) })
                    })
                })
            }
        })
        put("generationConfig", buildJsonObject {
            // Gemini 3.6+ should use its default sampling behavior; legacy temperature/topP are omitted.
            put("maxOutputTokens", 32768)
            if (includeThoughts) {
                // Thought summaries are opt-in on the Gemini API and, when enabled, arrive
                // as parts flagged `thought` rather than being blended into the answer.
                // Models that reject the object are replayed without it (see chatStream).
                put("thinkingConfig", buildJsonObject { put("includeThoughts", true) })
            }
        })
    }

    private fun extractTextOrNull(root: JsonObject): String? {
        val texts = mutableListOf<String>()
        val candidates = root["candidates"] as? JsonArray
        candidates?.forEach { candidateElement ->
            val candidate = candidateElement as? JsonObject ?: return@forEach
            val content = candidate["content"] as? JsonObject ?: return@forEach
            val parts = content["parts"] as? JsonArray ?: return@forEach
            parts.forEach { partElement ->
                val part = partElement as? JsonObject ?: return@forEach
                val text = (part["text"] as? JsonPrimitive)?.content.orEmpty()
                if (text.isNotEmpty()) texts += text
            }
        }
        val topLevelText = (root["text"] as? JsonPrimitive)?.content.orEmpty()
        if (topLevelText.isNotEmpty()) texts += topLevelText
        return texts.joinToString(separator = "").takeIf { it.isNotBlank() }
    }

    private fun finishReasons(root: JsonObject): List<String> =
        (root["candidates"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("finishReason") as? JsonPrimitive)?.content }
            .orEmpty()

    private fun diagnostic(root: JsonObject): String {
        val reasons = finishReasons(root)
        val feedback = root["promptFeedback"] as? JsonObject
        val blockReason = (feedback?.get("blockReason") as? JsonPrimitive)?.content.orEmpty()
        return buildString {
            append("Gemini returned HTTP 200 without answer text")
            if (blockReason.isNotBlank()) append(" (blockReason=$blockReason)")
            if (reasons.isNotEmpty()) append(" (finishReason=${reasons.joinToString()})")
        }
    }

    private fun generateUrl(model: String): String = "$base/models/${normalizeModel(model)}:generateContent"
    private fun streamUrl(model: String): String = "$base/models/${normalizeModel(model)}:streamGenerateContent?alt=sse"

    /**
     * Whether a 400 blames the thinking configuration. The message carries the provider's
     * own error detail, and matching on the field name keeps the retry provider-agnostic —
     * no model is named or special-cased here.
     */
    private fun HttpException.rejectedThinkingConfig(): Boolean =
        message.orEmpty().contains("thinking", ignoreCase = true)

    companion object {
        fun normalizeModel(value: String): String = value.trim().removePrefix("models/")
    }
}

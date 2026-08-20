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
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

class GeminiRecitationException(message: String) : Exception(message)

data class ChatMessage(val role: String, val content: String)

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/** Direct Gemini API client used with a key supplied and encrypted on the user’s device. */
class GeminiClient(private val client: OkHttpClient = Net.client) {

    private val base = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns bare model IDs, for example `gemini-3.6-flash`. */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
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
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val supported = (obj["supportedGenerationMethods"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                if (supported.isNotEmpty() && "generateContent" !in supported) return@mapNotNull null
                (obj["name"] as? JsonPrimitive)?.content
                    ?.removePrefix("models/")
                    ?.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }
    }

    /** Normalizes one ambiguous timetable cell using Gemini JSON mode. */
    suspend fun normalize(cell: String, model: String, apiKey: String): AiFields =
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
    ): String = chatStream(messages, systemPrompt, model, apiKey) { }

    /**
     * Streams Gemini's REST SSE response. Each non-empty text delta is delivered immediately
     * on the IO thread, while the returned string contains the complete answer for persistence.
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiKey: String,
        onText: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(messages.any { it.content.isNotBlank() }) { "At least one chat message is required" }
        val payload = buildChatPayload(messages, systemPrompt)
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
            val complete = StringBuilder()
            val finishReasons = mutableSetOf<String>()
            body.charStream().buffered().useLines { lines ->
                lines.forEach { line ->
                    val data = line.trim().takeIf { it.startsWith("data:") }
                        ?.removePrefix("data:")?.trim()
                        ?.takeIf { it.isNotBlank() && it != "[DONE]" }
                        ?: return@forEach
                    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return@forEach
                    finishReasons += finishReasons(root)
                    val delta = extractTextOrNull(root).orEmpty()
                    if (delta.isNotBlank()) {
                        complete.append(delta)
                        onText(delta)
                    }
                }
            }
            val answer = complete.toString().trim()
            if (answer.isNotBlank()) return@withContext answer
            if (finishReasons.contains("RECITATION")) {
                throw GeminiRecitationException("Gemini stopped because the answer was too close to source text (RECITATION)")
            }
            throw HttpException(200, "Gemini returned HTTP 200 without answer text${finishReasons.takeIf { it.isNotEmpty() }?.let { " (finishReason=${it.joinToString()})" } ?: ""}")
        }
    }

    /** Validates the API key and whether the configured model is available for generation. */
    suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean> = try {
        val models = listModels(apiKey)
        true to models.any { it == normalizeModel(model) }
    } catch (_: Exception) {
        false to false
    }

    private fun buildChatPayload(messages: List<ChatMessage>, systemPrompt: String): JsonObject = buildJsonObject {
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
                val text = (part["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (text.isNotBlank()) texts += text
            }
        }
        val topLevelText = (root["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (topLevelText.isNotBlank()) texts += topLevelText
        return texts.joinToString("\n").trim().takeIf { it.isNotBlank() }
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

    companion object {
        fun normalizeModel(value: String): String = value.trim().removePrefix("models/")
    }
}

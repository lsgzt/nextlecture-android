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

data class ChatMessage(val role: String, val content: String)

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/** Direct Gemini API client used with a key supplied and encrypted on the user’s device. */
class GeminiClient(private val client: OkHttpClient = Net.client) {

    private val base = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns bare model IDs, for example `gemini-2.5-flash`. */
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
                    put("temperature", 0.0)
                    put("responseMimeType", "application/json")
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
                val candidates = root["candidates"] as? JsonArray
                val content = (candidates?.firstOrNull() as? JsonObject)?.get("content") as? JsonObject
                val parts = content?.get("parts") as? JsonArray
                val text = (parts?.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive
                AiCellParser.parse(text?.content ?: throw HttpException(200, "no Gemini content"))
                    ?: throw HttpException(200, "AI output failed validation")
            }
        }

    /** Sends Gemini's multi-turn chat request with user/model turns and a separate system instruction. */
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        require(messages.any { it.content.isNotBlank() }) { "At least one chat message is required" }
        val payload = buildJsonObject {
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
                put("temperature", 0.2)
                put("topP", 0.9)
                put("maxOutputTokens", 4096)
            })
        }
        val req = Request.Builder()
            .url(generateUrl(model))
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = resp.body?.string()?.take(300).orEmpty()
                throw HttpException(resp.code, "Gemini chat failed${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: throw HttpException(200, "unexpected Gemini payload")
            val candidates = root["candidates"] as? JsonArray
            val content = (candidates?.firstOrNull() as? JsonObject)?.get("content") as? JsonObject
            val parts = content?.get("parts") as? JsonArray
            parts?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                ?.joinToString("") { it.content }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw HttpException(200, "no Gemini chat content")
        }
    }

    /** Validates the API key and whether the configured model is available for generation. */
    suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean> = try {
        val models = listModels(apiKey)
        true to models.any { it == normalizeModel(model) }
    } catch (_: Exception) {
        false to false
    }

    private fun generateUrl(model: String): String = "$base/models/${normalizeModel(model)}:generateContent"

    companion object {
        fun normalizeModel(value: String): String = value.trim().removePrefix("models/")
    }
}

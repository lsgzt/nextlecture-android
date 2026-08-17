package com.gndec.timetable.net

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
import com.gndec.timetable.parse.AiCellParser
import com.gndec.timetable.parse.AiFields
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/** Direct Groq API client (used when the user supplied their own key). */
class GroqClient(private val client: OkHttpClient = Net.client) {

    private val base = "https://api.groq.com/openai/v1"

    /** @return list of model ids; @throws HttpException on non-2xx */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/models")
            .header("Authorization", "Bearer $apiKey").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "models failed")
            val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
            val data = Json.parseToJsonElement(body) as? JsonObject
                ?: throw HttpException(200, "unexpected models payload")
            val arr = data["data"] as? JsonArray ?: return@withContext emptyList()
            arr.mapNotNull { (it as? JsonObject)?.get("id") }
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .sorted()
        }
    }

    /** Normalize one ambiguous timetable cell. @throws on network/HTTP/validation errors */
    suspend fun normalize(cell: String, model: String, apiKey: String): AiFields =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("model", model)
                put("temperature", 0.0)
                put("response_format", buildJsonObject { put("type", "json_object") })
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", AiCellParser.SYSTEM_PROMPT) })
                    add(buildJsonObject { put("role", "user"); put("content", cell) })
                })
            }
            val req = Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "chat completion failed")
                val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
                val root = Json.parseToJsonElement(body) as? JsonObject
                    ?: throw HttpException(200, "unexpected payload")
                val choices = root["choices"] as? JsonArray
                val msg = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                val content = (msg?.get("content") as? JsonPrimitive)?.content
                    ?: throw HttpException(200, "no content")
                AiCellParser.parse(content)
                    ?: throw HttpException(200, "ai output failed validation")
            }
        }

    /** Validate key + model availability. Never throws; returns (keyOk, modelOk). */
    suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean> {
        return try {
            val models = listModels(apiKey)
            (true to models.contains(model))
        } catch (e: Exception) {
            (false to false)
        }
    }
}

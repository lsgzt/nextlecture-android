package com.gndec.timetable.net

import com.gndec.timetable.parse.AiCellParser
import com.gndec.timetable.parse.AiFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to the restricted developer backend (see /backend in the repo).
 * Used ONLY when the user has not provided their own Gemini key.
 * The user's key (when present) is NEVER sent here.
 */
class BackendClient(private val client: OkHttpClient = Net.client) {

    suspend fun normalize(backendUrl: String, cell: String, model: String): AiFields =
        withContext(Dispatchers.IO) {
            val url = backendUrl.trimEnd('/') + "/normalize"
            val payload = buildJsonObject {
                put("cell", cell)
                put("model", model)
            }
            val req = Request.Builder().url(url)
                .post(payload.toString().toRequestBody(Net.JSON_MEDIA)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "backend normalize failed")
                val body = resp.body?.string() ?: throw HttpException(resp.code, "empty body")
                AiCellParser.parse(Json.parseToJsonElement(body).toString())
                    ?: throw HttpException(200, "backend output failed validation")
            }
        }
}

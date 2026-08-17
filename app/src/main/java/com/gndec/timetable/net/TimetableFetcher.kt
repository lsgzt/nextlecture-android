package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class FetchOutcome {
    /** New content was downloaded. */
    data class Changed(val html: String, val etag: String?, val lastModified: String?) : FetchOutcome()
    /** Server said nothing changed (HTTP 304) — keep the cached timetable untouched. */
    object NotModified : FetchOutcome()
    data class Failed(val reason: String) : FetchOutcome()
}

/** Downloads the official timetable with HTTP conditional-request support. */
class TimetableFetcher(private val client: OkHttpClient = Net.client) {

    suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchOutcome =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).get()
                if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
                if (!lastModified.isNullOrBlank()) builder.header("If-Modified-Since", lastModified)
                client.newCall(builder.build()).execute().use { resp ->
                    when {
                        resp.code == 304 -> FetchOutcome.NotModified
                        !resp.isSuccessful -> FetchOutcome.Failed("HTTP ${resp.code}")
                        else -> {
                            val body = resp.body?.string()
                            if (body.isNullOrBlank()) FetchOutcome.Failed("empty response")
                            else FetchOutcome.Changed(
                                html = body,
                                etag = resp.header("ETag"),
                                lastModified = resp.header("Last-Modified")
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                FetchOutcome.Failed(e.message ?: "network error")
            }
        }
}

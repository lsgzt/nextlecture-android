package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A single ZenQuotes entry. [text] is the quote body, [author] may be blank. */
data class ZenQuote(val text: String, val author: String)

/**
 * Client for the public ZenQuotes "quote of the day" feed.
 *
 * `/today` answers with a single-element array (`[{"q":…,"a":…,"h":…}]`) rather than the
 * random-quote array shape, so only the first element is ever read. `h` carries HTML that
 * this app does not render; the plain `q`/`a` fields are used instead.
 */
class ZenQuoteClient(private val client: OkHttpClient = Net.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Fetches today's quote. Throws rather than returning a blank quote so callers can retry. */
    suspend fun today(): ZenQuote = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(TODAY_URL)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "ZenQuotes returned ${response.code}")
            val body = response.body?.string() ?: throw HttpException(response.code, "Empty ZenQuotes response")
            val entry = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
                ?: throw HttpException(200, "ZenQuotes returned no quote")
            val quote = ZenQuote(
                text = entry["q"]?.jsonPrimitive?.content.orEmpty().trim(),
                author = entry["a"]?.jsonPrimitive?.content.orEmpty().trim()
            )
            if (quote.text.isEmpty()) throw HttpException(200, "ZenQuotes returned a blank quote")
            quote
        }
    }

    companion object {
        const val TODAY_URL = "https://zenquotes.io/api/today"
    }
}

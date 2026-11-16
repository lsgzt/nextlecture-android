package com.gndec.timetable.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Turns one Gemini `streamGenerateContent` SSE payload into classified [AiStreamEvent]s.
 *
 * The v1beta schema marks reasoning at *part* granularity: a thought part carries
 * `"thought": true` beside its own `"text"`, so reasoning and answer text arrive as separate
 * parts that can share an event and must be split per part, never per event. Parts without
 * the marker are answer text, which leaves non-thinking models byte-for-byte identical to
 * the previous behaviour — no reasoning is invented from answer text.
 */
internal class GeminiStreamParser(private val json: Json) {

    fun parse(data: String): List<AiStreamEvent> {
        // Keep-alive comments and the OpenAI-style terminator carry no payload.
        if (data.isBlank() || data == DONE_SENTINEL) return emptyList()
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return emptyList()

        val events = mutableListOf<AiStreamEvent>()
        (root["candidates"] as? JsonArray)?.forEach { candidateElement ->
            val parts = ((candidateElement as? JsonObject)?.get("content") as? JsonObject)
                ?.get("parts") as? JsonArray ?: return@forEach
            parts.forEach { partElement ->
                val part = partElement as? JsonObject ?: return@forEach
                val text = (part["text"] as? JsonPrimitive)?.content.orEmpty()
                if (text.isEmpty()) return@forEach
                events += if (isReasoning(part)) AiStreamEvent.Reasoning(text) else AiStreamEvent.Answer(text)
            }
        }
        // Bare `text` payloads (the non-candidate shape) have no part marker to inspect, so
        // they are answer text by definition.
        (root["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
            ?.let { events += AiStreamEvent.Answer(it) }

        val finishReason = finishReasons(root).lastOrNull()
        val thoughtTokenCount = thoughtTokens(root)
        if (finishReason != null || thoughtTokenCount != null) {
            events += AiStreamEvent.Meta(finishReason, thoughtTokenCount)
        }
        return events
    }

    /**
     * True when the part is reasoning rather than answer text.
     *
     * `thought` is documented as a boolean, but the same field has also been observed as a
     * string enum on newer revisions, so both are accepted rather than tied to one model.
     * `thoughtSignature` is the fallback signal: the API only attaches it to thought parts,
     * a plain text part never carries one.
     */
    private fun isReasoning(part: JsonObject): Boolean {
        (part["thought"] as? JsonPrimitive)?.let { marker ->
            if (marker.isString) return marker.content.isThoughtMarker()
            marker.booleanOrNull?.let { return it }
        }
        return (part["thoughtSignature"] as? JsonPrimitive)?.content?.isNotBlank() == true
    }

    private fun String.isThoughtMarker(): Boolean = when (trim().lowercase()) {
        "false", "0", "" -> false
        else -> true
    }

    private fun finishReasons(root: JsonObject): List<String> =
        (root["candidates"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("finishReason") as? JsonPrimitive)?.content }
            .orEmpty()

    private fun thoughtTokens(root: JsonObject): Int? {
        val usage = root["usageMetadata"] as? JsonObject ?: return null
        return (usage["thoughtsTokenCount"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (usage["thoughtTokenCount"] as? JsonPrimitive)?.content?.toIntOrNull()
    }

    private companion object {
        const val DONE_SENTINEL = "[DONE]"
    }
}

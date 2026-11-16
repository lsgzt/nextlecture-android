package com.gndec.timetable.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns one OpenAI-compatible `chat.completion.chunk` payload into [AiStreamEvent]s.
 *
 * The two providers this speaks for agree on the chunk envelope and disagree only on where
 * reasoning hides, so all three documented spellings are read and none is assumed:
 *
 *  - Groq streams a plain `delta.reasoning` string.
 *  - OpenRouter documents `delta.reasoning_details`, an array of blocks whose `text` forms
 *    the reasoning when concatenated in order, and accepts `reasoning_content` as an alias.
 *
 * Anything not explicitly marked as reasoning is answer text, so a provider that streams no
 * reasoning at all behaves exactly like a non-thinking model.
 */
internal class OpenAiStreamParser(private val json: Json) {

    fun parse(data: String): List<AiStreamEvent> {
        // The stream terminator carries no payload.
        if (data.isBlank() || data == DONE_SENTINEL) return emptyList()
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return emptyList()

        val events = mutableListOf<AiStreamEvent>()
        (root["choices"] as? JsonArray)?.forEach { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@forEach
            val delta = choice["delta"] as? JsonObject ?: return@forEach
            // Reasoning is read first: providers that interleave both within one delta must
            // still have them separated, and answer text is never mistaken for reasoning.
            reasoningOf(delta)?.takeIf { it.isNotEmpty() }?.let { events += AiStreamEvent.Reasoning(it) }
            (delta["content"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
                ?.let { events += AiStreamEvent.Answer(it) }
        }

        val finishReason = finishReason(root)
        val thoughtTokenCount = thoughtTokens(root)
        if (finishReason != null || thoughtTokenCount != null) {
            events += AiStreamEvent.Meta(finishReason, thoughtTokenCount)
        }
        return events
    }

    /**
     * The reasoning text carried by one delta, in priority order.
     *
     * `reasoning` is checked first because it is the one Groq documents, then the alias,
     * then the structured blocks. Only the first source present in this delta is used so a
     * provider exposing the same content twice is not counted twice.
     */
    private fun reasoningOf(delta: JsonObject): String? {
        (delta["reasoning"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { return it }
        (delta["reasoning_content"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { return it }
        val details = delta["reasoning_details"] as? JsonArray ?: return null
        val text = details.mapNotNull { blockElement ->
            val block = blockElement as? JsonObject ?: return@mapNotNull null
            // `reasoning.encrypted` blocks are opaque and carry no displayable text.
            val type = (block["type"] as? JsonPrimitive)?.content.orEmpty()
            if (type == "reasoning.encrypted") return@mapNotNull null
            (block["text"] as? JsonPrimitive)?.content
        }.joinToString(separator = "")
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * The provider's own finish reason, mapped onto the vocabulary callers already handle.
     *
     * `length` means the answer was cut off by the token budget, which is the signal the
     * continuation loop watches for; renaming it here keeps that loop provider-agnostic.
     */
    private fun finishReason(root: JsonObject): String? =
        (root["choices"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("finish_reason") as? JsonPrimitive)?.content }
            ?.lastOrNull()
            ?.let { if (it == "length") "MAX_TOKENS" else it }

    private fun thoughtTokens(root: JsonObject): Int? {
        val details = (root["usage"] as? JsonObject)?.get("completion_tokens_details") as? JsonObject
            ?: return null
        return (details["reasoning_tokens"] as? JsonPrimitive)?.intOrNull
    }

    private companion object {
        const val DONE_SENTINEL = "[DONE]"
    }
}

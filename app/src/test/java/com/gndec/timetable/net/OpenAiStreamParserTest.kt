package com.gndec.timetable.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two OpenAI-compatible reasoning spellings: Groq's plain `delta.reasoning`
 * and OpenRouter's structured `delta.reasoning_details`.
 */
class OpenAiStreamParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val parser = OpenAiStreamParser(json)

    @Test
    fun `answer only chunk yields answer text`() {
        val events = parser.parse(
            """{"object":"chat.completion.chunk","choices":[{"delta":{"content":"Physics "}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Answer("Physics ")), events)
    }

    @Test
    fun `groq reasoning field is classified as reasoning not answer`() {
        val events = parser.parse(
            """{"choices":[{"delta":{"reasoning":"The student asked for"}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Reasoning("The student asked for")), events)
    }

    @Test
    fun `reasoning_content alias is read`() {
        val events = parser.parse("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""")
        assertEquals(listOf(AiStreamEvent.Reasoning("thinking")), events)
    }

    @Test
    fun `openrouter reasoning_details text blocks are concatenated in order`() {
        val events = parser.parse(
            """{"choices":[{"delta":{"reasoning_details":[
               {"type":"reasoning.text","text":"Let me "},
               {"type":"reasoning.text","text":"check the units."}]}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Reasoning("Let me check the units.")), events)
    }

    @Test
    fun `encrypted reasoning blocks carry no displayable text`() {
        val events = parser.parse(
            """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","signature":"abc"}]}}]}"""
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `reasoning and answer in the same delta are kept apart`() {
        val events = parser.parse(
            """{"choices":[{"delta":{"reasoning":"consider units","content":"Unit 1 covers"}}]}"""
        )
        assertEquals(
            listOf(AiStreamEvent.Reasoning("consider units"), AiStreamEvent.Answer("Unit 1 covers")),
            events
        )
    }

    @Test
    fun `reasoning wins over the alias when a provider sends both`() {
        val events = parser.parse(
            """{"choices":[{"delta":{"reasoning":"once","reasoning_content":"twice"}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Reasoning("once")), events)
    }

    @Test
    fun `length finish reason is reported as MAX_TOKENS for the continuation loop`() {
        val events = parser.parse("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals(listOf(AiStreamEvent.Meta("MAX_TOKENS", null)), events)
    }

    @Test
    fun `other finish reasons are passed through unchanged`() {
        val events = parser.parse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(listOf(AiStreamEvent.Meta("stop", null)), events)
    }

    @Test
    fun `content_filter finish reason is reported for the recitation retry`() {
        val events = parser.parse("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""")
        assertEquals(listOf(AiStreamEvent.Meta("content_filter", null)), events)
    }

    @Test
    fun `reasoning token usage is reported as thought tokens`() {
        val events = parser.parse(
            """{"choices":[],"usage":{"completion_tokens_details":{"reasoning_tokens":301}}}"""
        )
        assertEquals(listOf(AiStreamEvent.Meta(null, 301)), events)
    }

    @Test
    fun `terminator and malformed payloads produce nothing`() {
        assertTrue(parser.parse("[DONE]").isEmpty())
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
        assertTrue(parser.parse("not json at all").isEmpty())
    }

    @Test
    fun `a null delta is skipped rather than failing the frame`() {
        val events = parser.parse("""{"choices":[{"finish_reason":"stop","delta":null}]}""")
        assertEquals(listOf(AiStreamEvent.Meta("stop", null)), events)
    }

    @Test
    fun `sse frames spanning several data lines are one payload`() {
        val reader = SseFrameReader(parser::parse)
        val seen = mutableListOf<AiStreamEvent>()
        val emit: (List<AiStreamEvent>) -> Unit = { seen += it }
        reader.accept("data: {\"choices\":[{\"delta\":{\"content\":", emit)
        reader.accept("data: \"joined\"}}]}", emit)
        reader.accept("", emit)
        assertEquals(listOf(AiStreamEvent.Answer("joined")), seen)
    }

    @Test
    fun `a trailing frame without a final blank line is still emitted`() {
        val reader = SseFrameReader(parser::parse)
        val seen = mutableListOf<AiStreamEvent>()
        val emit: (List<AiStreamEvent>) -> Unit = { seen += it }
        reader.accept("data: {\"choices\":[{\"delta\":{\"content\":\"tail\"}}]}", emit)
        reader.flush(emit)
        assertEquals(listOf(AiStreamEvent.Answer("tail")), seen)
    }
}

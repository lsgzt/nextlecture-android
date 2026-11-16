package com.gndec.timetable.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the Gemini stream. These drive the real SSE framing and the real
 * parser, so a change in how the provider marks reasoning fails here rather than silently
 * merging a model's internal notes into the answer on the device.
 */
class GeminiStreamParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val parser = GeminiStreamParser(json)

    /** Scenario 1 — a non-thinking model streams answer text and nothing else. */
    @Test
    fun nonThinkingModelProducesAnswerDeltasOnly() {
        val events = parser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"Unit 1: Engineering "}],"index":0}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Answer("Unit 1: Engineering ")), events)
    }

    @Test
    fun nonThinkingModelNeverSynthesisesReasoning() {
        var sawReasoning = false
        listOf("Physics", "## Unit 2\n\nOptics", "3.1 Refraction").forEach { text ->
            parser.parse("""{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""")
                .forEach { if (it is AiStreamEvent.Reasoning) sawReasoning = true }
        }
        assertTrue("a model that sends no reasoning field must not produce any", !sawReasoning)
    }

    /** Scenario 2 — a thinking model streams reasoning first, then the answer. */
    @Test
    fun thinkingModelStreamsReasoningThenAnswer() {
        val thought = parser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"The document lists","thought":true,"thoughtSignature":"Cq8C"}]}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Reasoning("The document lists")), thought)

        val answer = parser.parse("""{"candidates":[{"content":{"parts":[{"text":" Physics."}]}}]}""")
        assertEquals(listOf(AiStreamEvent.Answer(" Physics.")), answer)
    }

    /** Both streams can arrive inside a single event and must still be split per part. */
    @Test
    fun reasoningAndAnswerInTheSameEventStaySeparate() {
        val events = parser.parse(
            """{"candidates":[{"content":{"parts":[
                {"text":"Let me check the units.","thought":true},
                {"text":"## Physics"},
                {"text":"\n\nUnit 1","thought":true}
            ]}}]}"""
        )
        assertEquals(
            listOf(
                AiStreamEvent.Reasoning("Let me check the units."),
                AiStreamEvent.Answer("## Physics"),
                AiStreamEvent.Reasoning("\n\nUnit 1")
            ),
            events
        )
    }

    /** The field is documented as a boolean but has shipped as a string enum; accept both. */
    @Test
    fun thoughtMarkerIsAcceptedAsBooleanOrString() {
        listOf("\"THOUGHT\"", "\"RETHINK\"", "true").forEach { raw ->
            val events = parser.parse("""{"candidates":[{"content":{"parts":[{"text":"why","thought":$raw}]}}]}""")
            assertEquals("marker $raw should read as reasoning", listOf(AiStreamEvent.Reasoning("why")), events)
        }
        listOf("\"false\"", "false").forEach { raw ->
            val events = parser.parse("""{"candidates":[{"content":{"parts":[{"text":"why","thought":$raw}]}}]}""")
            assertEquals("marker $raw should read as answer", listOf(AiStreamEvent.Answer("why")), events)
        }
    }

    /** A thought signature is only ever attached to a thought part. */
    @Test
    fun thoughtSignatureAloneMarksReasoning() {
        val events = parser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"signed thought","thoughtSignature":"Er4BCg=="}]}}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Reasoning("signed thought")), events)
    }

    /** Control data stays out of the message body. */
    @Test
    fun finishReasonAndUsageArriveAsMeta() {
        val events = parser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}],
               "usageMetadata":{"thoughtsTokenCount":812,"totalTokenCount":900}}"""
        )
        assertEquals(AiStreamEvent.Answer("done"), events[0])
        assertEquals(AiStreamEvent.Meta("STOP", 812), events[1])
    }

    @Test
    fun malformedAndEmptyPayloadsAreIgnored() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
        assertTrue(parser.parse("[DONE]").isEmpty())
        assertTrue(parser.parse("not json at all").isEmpty())
        assertTrue(parser.parse("""{"candidates":[]}""").isEmpty())
        assertTrue(parser.parse("""{"candidates":[{"content":{"parts":[]}}]}""").isEmpty())
        assertTrue(parser.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""").isEmpty())
    }

    /** Scenario 3 — thousands of reasoning deltas must all be classified, not just the first. */
    @Test
    fun veryLongReasoningStreamStaysClassified() {
        val reader = SseFrameReader(parser::parse)
        val collected = mutableListOf<AiStreamEvent>()
        val emit: (List<AiStreamEvent>) -> Unit = { collected += it }

        repeat(500) { i ->
            reader.accept("""data: {"candidates":[{"content":{"parts":[{"text":"step $i ","thought":true}]}}]}""", emit)
            reader.accept("", emit)
        }
        reader.accept("""data: {"candidates":[{"content":{"parts":[{"text":"## Physics"}]}}]}""", emit)
        reader.accept("", emit)
        reader.flush(emit)

        assertEquals(501, collected.size)
        assertEquals(500, collected.count { it is AiStreamEvent.Reasoning })
        assertEquals(AiStreamEvent.Answer("## Physics"), collected.last())
    }

    /** Scenario 7 — rapid arrival, and a payload split across several `data:` lines. */
    @Test
    fun rapidChunksAndMultiLineFramesReassembleInOrder() {
        val reader = SseFrameReader(parser::parse)
        val collected = mutableListOf<AiStreamEvent>()
        val emit: (List<AiStreamEvent>) -> Unit = { collected += it }

        listOf("Unit ", "1 ", "covers ", "mechanics.").forEach { piece ->
            reader.accept("""data: {"candidates":[{"content":{"parts":[{"text":"$piece"}]}}]}""", emit)
            reader.accept("", emit)
        }
        // A frame split across two data: lines must not emit until the blank line closes it.
        val splitFrame = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true}],"index":0}}]}"""
        val cut = splitFrame.indexOf(",\"index\"")
        reader.accept("data: " + splitFrame.substring(0, cut), emit)
        reader.accept("data: " + splitFrame.substring(cut), emit)
        assertEquals("a frame is only emitted once it is complete", 4, collected.size)
        reader.accept("", emit)
        // A keep-alive comment between frames must not disturb framing.
        reader.accept(": keep-alive", emit)
        reader.accept("""data: {"candidates":[{"content":{"parts":[{"text":"more"}]}}]}""", emit)
        reader.flush(emit)

        assertEquals(
            listOf(
                AiStreamEvent.Answer("Unit "),
                AiStreamEvent.Answer("1 "),
                AiStreamEvent.Answer("covers "),
                AiStreamEvent.Answer("mechanics."),
                AiStreamEvent.Reasoning("thinking"),
                AiStreamEvent.Answer("more")
            ),
            collected
        )
    }

    /** Scenario 4 — a very short response still yields exactly one answer delta. */
    @Test
    fun singleCharacterResponseIsPreserved() {
        val events = parser.parse("""{"candidates":[{"content":{"parts":[{"text":"4"}]}}]}""")
        assertEquals(listOf(AiStreamEvent.Answer("4")), events)
    }

    /** Scenario 5 — an answer-less response yields no text events at all. */
    @Test
    fun emptyResponseYieldsNoTextEvents() {
        val events = parser.parse("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""")
        assertEquals(listOf(AiStreamEvent.Meta("MAX_TOKENS", null)), events)
    }
}

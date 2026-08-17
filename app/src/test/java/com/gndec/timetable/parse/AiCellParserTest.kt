package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiCellParserTest {

    @Test
    fun validJsonIsParsed() {
        val out = AiCellParser.parse(
            """{"subject":"Engineering Mathematics","teacher":"Dr. Example","venue":"F106","lecture_type":"Lecture"}"""
        )
        assertEquals("Engineering Mathematics", out!!.subject)
        assertEquals("Dr. Example", out.teacher)
        assertEquals("F106", out.venue)
        assertEquals("Lecture", out.lectureType)
    }

    @Test
    fun missingFieldsBecomeNull() {
        val out = AiCellParser.parse("""{"subject":"Physics"}""")
        assertEquals("Physics", out!!.subject)
        assertNull(out.teacher)
        assertNull(out.venue)
        assertNull(out.lectureType)
    }

    @Test
    fun explicitNullsStayNull() {
        val out = AiCellParser.parse(
            """{"subject":null,"teacher":null,"venue":"S205","lecture_type":null}"""
        )
        assertNull(out!!.subject)
        assertEquals("S205", out.venue)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(AiCellParser.parse("not json at all"))
        assertNull(AiCellParser.parse("{broken"))
        assertNull(AiCellParser.parse("[1,2,3]"))
        assertNull(AiCellParser.parse("\"just a string\""))
    }

    @Test
    fun markdownFencesAreStripped() {
        val out = AiCellParser.parse("```json\n{\"subject\":\"Chemistry\"}\n```")
        assertEquals("Chemistry", out!!.subject)
    }

    @Test
    fun hallucinatedKeysAreRejected() {
        // AI invented extra fields -> whole response rejected, deterministic data wins
        assertNull(AiCellParser.parse("""{"subject":"X","building":"Main Block"}"""))
        assertNull(AiCellParser.parse("""{"subject":"X","confidence":0.9}"""))
    }

    @Test
    fun overlongFieldsAreTruncatedNotTrusted() {
        val long = "A".repeat(500)
        val out = AiCellParser.parse("""{"subject":"$long"}""")
        assertEquals(120, out!!.subject!!.length)
    }

    @Test
    fun lectureTypesAreNormalized() {
        assertEquals("Lecture", AiCellParser.normalizeType("L"))
        assertEquals("Practical", AiCellParser.normalizeType("P"))
        assertEquals("Practical", AiCellParser.normalizeType("lab"))
        assertEquals("Tutorial", AiCellParser.normalizeType("Tutorial"))
        assertNull(AiCellParser.normalizeType("free"))
        assertNull(AiCellParser.normalizeType(null))
    }

    @Test
    fun deterministicFieldsStayAuthoritativeOverAi() {
        val raw = RawLecture(
            groupName = "ITB2", dayOfWeek = 1, startMinutes = 510, endMinutes = 570,
            subjectHint = "CHEMISTRY", teacherHint = "DR AMANDEEP KAUR", venueHint = "S205",
            typeTag = "L", rawText = "raw", confidence = 1.0
        )
        val ai = AiFields(subject = "Wrong Subject", teacher = "Wrong Teacher",
            venue = "Wrong Room", lectureType = "Practical")
        val merged = AiCellParser.merge(raw, ai)
        assertEquals("CHEMISTRY", merged.subject)
        assertEquals("DR AMANDEEP KAUR", merged.teacher)
        assertEquals("S205", merged.venue)
        assertEquals("Lecture", merged.lectureType)
        // group/day/time are not even part of the merge input — AI can never touch them
    }

    @Test
    fun aiFillsGapsWhenDeterministicParserIsUnsure() {
        val raw = RawLecture(
            groupName = "ITB2", dayOfWeek = 3, startMinutes = 810, endMinutes = 870,
            subjectHint = "MENTORING", teacherHint = null, venueHint = null,
            typeTag = null, rawText = "raw", confidence = 0.45
        )
        val ai = AiFields(subject = null, teacher = "Some Faculty", venue = "F204", lectureType = "Tutorial")
        val merged = AiCellParser.merge(raw, ai)
        assertEquals("MENTORING", merged.subject) // deterministic hint still wins
        assertEquals("Some Faculty", merged.teacher)
        assertEquals("F204", merged.venue)
        assertEquals("Tutorial", merged.lectureType)
    }

    @Test
    fun cacheKeyIsVersionedAndStable() {
        val k1 = AiCellParser.cacheKey("some raw cell")
        assertEquals(k1, AiCellParser.cacheKey("some raw cell"))
        assertNotEquals(k1, AiCellParser.cacheKey("other raw cell"))
        assertEquals(64, k1.length)
    }
}

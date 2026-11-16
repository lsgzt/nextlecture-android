package com.gndec.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval is the accuracy-critical part of syllabus answering, so these cases are taken
 * from the real document: a first-year GNDEC syllabus of eleven courses, each introduced
 * by a `Course Code:` header.
 */
class SyllabusIndexTest {

    private fun course(
        code: String,
        title: String,
        body: String = "body of $title"
    ) = """
        Course Code: $code
        Course Title: $title

        Programme: B.Tech. L: 3 T: 1 P: 2 Credits: 5
        Semester: 1/2 Theory/Practical: Theory
        Contents
        Unit-1 $body
    """.trimIndent()

    /** Mirrors the eleven courses of the official document, with representative prose. */
    private val document = buildString {
        append("Study Scheme B.Tech. first year 2024, (Common for all branches)\n")
        append("Guru Nanak Dev Engineering College, Ludhiana\n\n")
        append(course("BSC101", "Physics", "DC Circuits and Electromagnetism. Quantum Mechanics and Superconductivity."))
        append("\n\n")
        append(course("BSC102", "Mathematics-I", "Matrices and Linear Algebra. Calculus and its applications."))
        append("\n\n")
        append(course("BSC103", "Chemistry", "Atomic structure and Chemical Bonding. Thermodynamics."))
        append("\n\n")
        append(course("HSMC101", "Professional English Communication", "Technical writing and presentation skills."))
        append("\n\n")
        append(course("HSMC102", "Economics", "Indian Economic Development and Marketing."))
        append("\n\n")
        append(course("ESC101", "Basic Electrical and Electronics Engineering", "Kirchhoff Laws, AC circuits, Transformers."))
        append("\n\n")
        append(course("ESC102", "Engineering Drawing and Graphics", "Projection of solids and Machine drawing."))
        append("\n\n")
        append(course("ESC103", "Programming for Problem Solving", "C programming, arrays, functions and recursion."))
        append("\n\n")
        append(course("LESC104", "Manufacturing Practices", "Workshop, lathe, milling and fitting."))
        append("\n\n")
        append(course("BSC104", "Mathematics-II", "Probability, Statistics and Complex Analysis."))
        append("\n\n")
        append(course("ESC105", "Programming Fundamentals using Python", "Python syntax, lists, dictionaries and files."))
    }

    private val index: SyllabusIndex = requireNotNull(SyllabusIndex.parse(document))

    private fun codes(query: String, budget: Int = 1_000_000): Set<String> =
        index.retrieve(query, budget).included.map { it.code }.toSet()

    @Test
    fun `parses every course with its code and title`() {
        assertEquals(11, index.sections.size)
        assertEquals("Physics", index.sections.first().title)
        assertEquals("BSC101", index.sections.first().code)
    }

    @Test
    fun `a document that is not the official one is rejected rather than half-parsed`() {
        assertNull(SyllabusIndex.parse("Some unrelated text with no course headers at all."))
    }

    @Test
    fun `a single course is not enough to trust the parse`() {
        assertNull(SyllabusIndex.parse(course("BSC101", "Physics")))
    }

    @Test
    fun `naming a subject retrieves exactly that course`() {
        assertEquals(setOf("BSC101"), codes("physics syllabus"))
        assertEquals(setOf("BSC103"), codes("explain chemistry"))
        assertEquals(setOf("HSMC102"), codes("tell me about economics"))
        assertEquals(setOf("ESC102"), codes("engineering drawing and graphics"))
    }

    @Test
    fun `a course code in the question selects that course`() {
        assertEquals(setOf("BSC101"), codes("BSC101"))
    }

    @Test
    fun `both mathematics courses are kept because they genuinely tie`() {
        assertEquals(setOf("BSC102", "BSC104"), codes("give me the maths syllabus"))
    }

    @Test
    fun `a question naming two subjects keeps both`() {
        assertEquals(setOf("BSC101", "BSC103"), codes("difference between physics and chemistry"))
    }

    @Test
    fun `colloquial spellings reach the course that uses the formal one`() {
        assertEquals(setOf("BSC102", "BSC104"), codes("maths"))
        assertEquals(setOf("BSC103"), codes("chem practical"))
        assertEquals(setOf("ESC101"), codes("eee"))
        assertEquals(setOf("LESC104"), codes("workshop"))
    }

    @Test
    fun `a topic named inside a course body still finds it`() {
        assertEquals(setOf("ESC101"), codes("explain Kirchhoff laws"))
    }

    @Test
    fun `a question with no course signal keeps the whole document`() {
        assertEquals(11, codes("what subjects are in semester 1").size)
        assertEquals(11, codes("hello").size)
    }

    @Test
    fun `an off-topic question is not narrowed to one arbitrary course`() {
        // "machine learning" is absent from the document; it must not cause a confident
        // single-course match that would hide the rest of the catalogue from the model.
        assertTrue(codes("tell me about machine learning").size > 1)
    }

    @Test
    fun `a course is included whole and never cut to fit the budget`() {
        val tiny = index.sections.first()
        val result = index.retrieve("physics", budgetChars = 200)
        // The best match is always allowed through even when it cannot fit.
        assertEquals(listOf("BSC101"), result.included.map { it.code })
        assertTrue(result.included.first().body.length > 200)
    }

    @Test
    fun `courses left out are reported so the prompt can admit the excerpt is partial`() {
        val result = index.retrieve("physics", budgetChars = 200)
        assertFalse(result.isComplete)
        assertEquals(10, result.omitted.size)
    }

    @Test
    fun `an unnarrowed retrieval reports nothing omitted`() {
        // A question with no course signal narrows nothing, so nothing can be left out.
        val result = index.retrieve("hello", budgetChars = 1_000_000)
        assertEquals(11, result.included.size)
        assertTrue(result.isComplete)
    }

    @Test
    fun `the catalogue lists every course including ones not sent`() {
        val catalogue = index.catalogue()
        assertTrue(catalogue.contains("BSC101"))
        assertTrue(catalogue.contains("ESC105"))
        assertEquals(11, catalogue.lines().size)
    }

    @Test
    fun `an unreported context window leaves room for the entire document`() {
        // A provider that reports no window must behave as it did before retrieval existed.
        val budget = SyllabusIndex.documentBudgetChars(0)
        assertTrue(budget > document.length)
        assertEquals(11, codes("hello", budget).size)
    }

    @Test
    fun `a reported context window gives a much smaller budget`() {
        assertTrue(
            SyllabusIndex.documentBudgetChars(8_192) < SyllabusIndex.documentBudgetChars(0)
        )
    }

    @Test
    fun `a small budget narrows to the best match and reports the rest as omitted`() {
        val result = index.retrieve("physics syllabus", budgetChars = 200)
        assertEquals(listOf("BSC101"), result.included.map { it.code })
        assertEquals(10, result.omitted.size)
    }

    @Test
    fun `a large context window still narrows a confident match`() {
        // Large windows must not disable retrieval, or every request pays for the full document.
        assertEquals(setOf("BSC101"), codes("physics syllabus", SyllabusIndex.documentBudgetChars(131_072)))
    }

    @Test
    fun `the budget grows with the context window`() {
        assertTrue(
            SyllabusIndex.documentBudgetChars(131_072) > SyllabusIndex.documentBudgetChars(32_768)
        )
    }
}

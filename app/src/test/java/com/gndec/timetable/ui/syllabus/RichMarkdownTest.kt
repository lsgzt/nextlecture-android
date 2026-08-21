package com.gndec.timetable.ui.syllabus

import org.junit.Assert.assertTrue
import org.junit.Test

class RichMarkdownTest {
    @Test
    fun rendersPartialDerivativeFraction() {
        val rendered = normalizeLatex("x \\frac{\\partial f}{\\partial x} + y \\frac{\\partial f}{\\partial y} = n f(x,y)")
        assertTrue(rendered.contains("∂"))
        assertTrue(rendered.contains("⁄"))
        assertTrue(!rendered.contains("\\frac"))
    }

    @Test
    fun rendersVectorFieldAndGreekSymbols() {
        val rendered = normalizeLatex("\\nabla \\cdot \\mathbf{E} = \\frac{\\rho}{\\varepsilon_0}")
        assertTrue(rendered.contains("∇"))
        assertTrue(rendered.contains("·"))
        assertTrue(rendered.contains("E"))
        assertTrue(rendered.contains("ρ"))
        assertTrue(rendered.contains("ε₀"))
        assertTrue(!rendered.contains("\\mathbf"))
        assertTrue(!rendered.contains("\\frac"))
    }

    @Test
    fun rendersScriptsAndSurvivesIncompleteStreaming() {
        val rendered = normalizeLatex("x^2 + y_{n} + \\frac{x")
        assertTrue(rendered.contains("x²"))
        assertTrue(rendered.contains("yₙ"))
        assertTrue(rendered.isNotBlank())
    }
}

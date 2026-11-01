package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathTest {

    @Test
    fun fractionBecomesAFractionNode() {
        val out = MarkdownMath.toMathML("\\frac{a}{b}")
        assertTrue(out, out.contains("<mfrac>"))
        assertTrue(out, out.contains("<mi>a</mi>"))
        assertTrue(out, out.contains("<mi>b</mi>"))
    }

    @Test
    fun squareRootWrapsItsRadicand() {
        val out = MarkdownMath.toMathML("\\sqrt{x}")
        assertTrue(out, out.contains("<msqrt>"))
        assertTrue(out, out.contains("<mi>x</mi>"))
    }

    @Test
    fun superscriptAttachesToTheBaseItFollows() {
        val out = MarkdownMath.toMathML("x^{2}")
        // The bug this guards: <msup><mrow></mrow>… renders the exponent on nothing.
        assertTrue(out, out.contains("<msup><mi>x</mi>"))
        assertFalse(out, out.contains("<msup><mrow></mrow>"))
    }

    @Test
    fun subscriptAndSuperscriptCanShareABase() {
        val out = MarkdownMath.toMathML("x_{i}^{2}")
        assertTrue(out, out.contains("<msup><msub><mi>x</mi>"))
    }

    @Test
    fun greekAndOperatorsUseUnicode() {
        val out = MarkdownMath.toMathML("\\pi \\leq \\infty")
        assertTrue(out, out.contains("π"))
        assertTrue(out, out.contains("≤"))
        assertTrue(out, out.contains("∞"))
    }

    @Test
    fun textCommandKeepsWordsUprightAndEscaped() {
        val out = MarkdownMath.toMathML("\\text{if a < b}")
        assertTrue(out, out.contains("<mtext>if a &lt; b</mtext>"))
    }

    @Test
    fun numbersAreOneNodeNotOnePerDigit() {
        val out = MarkdownMath.toMathML("3.14")
        assertTrue(out, out.contains("<mn>3.14</mn>"))
    }

    @Test
    fun unknownCommandDegradesVisibly() {
        val out = MarkdownMath.toMathML("\\notarealcommand")
        assertTrue(out, out.contains("<mtext>notarealcommand</mtext>"))
    }

    @Test
    fun displayFlagAsksForABlock() {
        assertTrue(MarkdownMath.toMathML("x", display = true).contains("display=\"block\""))
        assertEquals(false, MarkdownMath.toMathML("x").contains("display=\"block\""))
    }
}

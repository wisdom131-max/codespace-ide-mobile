package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermHighlighterTest {

    @Test
    fun theMatchedTermIsMarked() {
        val out = TermHighlighter.mark("Gradle failed", listOf("gradle"))
        assertTrue(out.contains("Gradle"))
        assertTrue(out.startsWith("\u00ab"))
    }

    @Test
    fun noTermsLeavesTheTextUntouched() {
        assertEquals("plain text", TermHighlighter.mark("plain text", emptyList()))
    }

    @Test
    fun twoOverlappingTermsDoNotNestMarks() {
        val out = TermHighlighter.mark("gradle", listOf("grad", "gradle"))
        assertEquals(1, Regex("\u00ab").findAll(out).count())
    }

    @Test
    fun aTermWithRegexCharactersIsTreatedLiterally() {
        val out = TermHighlighter.mark("a.b() call", listOf("a.b()"))
        assertEquals(1, Regex("\u00ab").findAll(out).count())
    }
}

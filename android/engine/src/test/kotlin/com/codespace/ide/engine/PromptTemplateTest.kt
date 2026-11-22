package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateTest {

    @Test
    fun aKnownValueIsSubstituted() {
        val r = PromptTemplate.render("hello {{name}}", mapOf("name" to "wisdom"))
        assertEquals("hello wisdom", r.text)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun aFallbackIsUsedAndNotReported() {
        val r = PromptTemplate.render("mode: {{mode|ask}}", emptyMap())
        assertEquals("mode: ask", r.text)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun aMissingValueIsReportedNotHidden() {
        val r = PromptTemplate.render("hello {{name}}", emptyMap())
        assertEquals(listOf("name"), r.missing)
    }

    @Test
    fun aPlaceholderInTheValueIsNotReExpanded() {
        val r = PromptTemplate.render("{{a}}", mapOf("a" to "{{b}}"))
        assertEquals("{{b}}", r.text)
    }
}

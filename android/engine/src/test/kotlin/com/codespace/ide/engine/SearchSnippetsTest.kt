package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetsTest {

    private val source = (1..20).joinToString("\n") { "line " + it + (if (it == 10) " gradle failure" else "") }

    @Test
    fun theWindowIsCentredOnTheMatch() {
        val s = SearchSnippets.around(source, listOf("gradle"), context = 1)!!
        assertEquals(9, s.startLine)
        assertTrue(s.text.contains("gradle failure"))
        assertEquals(3, s.text.lines().size)
    }

    @Test
    fun noMatchingTermIsNull() {
        assertNull(SearchSnippets.around(source, listOf("kubernetes")))
    }

    @Test
    fun noTermsIsNull() {
        assertNull(SearchSnippets.around(source, emptyList()))
    }

    @Test
    fun theMatchIsCaseInsensitive() {
        assertTrue(SearchSnippets.around(source, listOf("GRADLE")) != null)
    }
}

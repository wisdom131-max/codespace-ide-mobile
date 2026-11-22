package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffContextTest {

    @Test
    fun identicalTextHasNoDiff() {
        val r = DiffContext.between("a\nb", "a\nb")
        assertFalse(r.changed)
        assertEquals("", r.text)
    }

    @Test
    fun oneChangedLineShowsBothSides() {
        val r = DiffContext.between("a\nb\nc", "a\nB\nc")
        assertTrue(r.text.contains("- b"))
        assertTrue(r.text.contains("+ B"))
        assertFalse(r.text.contains("- a"))
    }

    @Test
    fun anAddedLineIsShownAsAddedOnly() {
        val r = DiffContext.between("a\nc", "a\nb\nc")
        assertTrue(r.text.contains("+ b"))
        assertFalse(r.text.contains("- "))
    }

    @Test
    fun aHugeChangeIsTruncatedWithAWarning() {
        val old = (1..50).joinToString("\n") { "old " + it }
        val new = (1..50).joinToString("\n") { "new " + it }
        val r = DiffContext.between(old, new, maxLines = 10)
        assertTrue(r.text.contains("more lines changed"))
    }
}

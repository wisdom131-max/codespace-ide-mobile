package com.codespace.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 1: oversized inspected tool output is capped, and the cap says so honestly. */
class ToolOutputBoundsTest {

    @Test
    fun shortOutputIsUnchanged() {
        val text = " M src/App.kt\n?? new.txt"
        assertEquals(text, ToolOutputBounds.bound(text))
    }

    @Test
    fun outputExactlyAtTheCapIsUnchanged() {
        val text = "x".repeat(ToolOutputBounds.DEFAULT_MAX_CHARS)
        assertEquals(text, ToolOutputBounds.bound(text))
    }

    @Test
    fun longOutputIsCappedAndMarked() {
        val text = "a".repeat(ToolOutputBounds.DEFAULT_MAX_CHARS * 3)
        val out = ToolOutputBounds.bound(text)
        assertTrue("cap must hold", out.length <= ToolOutputBounds.DEFAULT_MAX_CHARS)
        assertTrue("marker must be present", out.contains("[truncated:"))
        assertTrue("marker must report the original size", out.contains(text.length.toString()))
        assertTrue("head of the payload is preserved", out.startsWith("aaaa"))
    }

    @Test
    fun theMarkerUsesTheRequestedCapSize() {
        val text = "b".repeat(500)
        val out = ToolOutputBounds.bound(text, 200)
        assertTrue(out.length <= 200)
        assertTrue(out.contains("200 of 500"))
    }

    @Test
    fun emptyOutputStaysEmpty() {
        assertEquals("", ToolOutputBounds.bound(""))
    }

    @Test
    fun aNonPositiveCapNeverTruncates() {
        val text = "c".repeat(100)
        assertEquals(text, ToolOutputBounds.bound(text, 0))
    }
}

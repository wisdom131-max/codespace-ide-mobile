package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** STAGE 0 (Checkpoint 0.5): a retry continues from the text already shown, never padded. */
class RetryPrefixTest {

    @Test
    fun `a chunk that continues the answer is appended`() {
        assertEquals("Hello world", RetryPrefix.merge("Hello ", "world"))
    }

    @Test
    fun `a chunk that re-emits the prefix is not doubled`() {
        assertEquals("Hello world", RetryPrefix.merge("Hello ", "Hello world"))
    }

    @Test
    fun `an empty prefix takes the chunk whole`() {
        assertEquals("world", RetryPrefix.merge("", "world"))
    }

    @Test
    fun `a blank chunk leaves the shown text untouched`() {
        assertEquals("Hello", RetryPrefix.merge("Hello", ""))
    }

    @Test
    fun `merged text never contains a NUL character`() {
        val merged = RetryPrefix.merge("partial", " answer")
        assertEquals("partial answer", merged)
        assertFalse(merged.contains('\u0000'))
    }
}

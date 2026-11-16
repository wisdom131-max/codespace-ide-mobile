package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN 7.9E — streaming is announced in meaningful steps, never per token. */
class Stage79AnnouncementTest {

    @Test
    fun `a first token announces that a reply has begun, not the token`() {
        assertEquals("Assistant is replying.", CopilotAccessibility.announcement(null, "He"))
    }

    @Test
    fun `small growth is not announced`() {
        assertNull(CopilotAccessibility.announcement("Hello", "Hello wo"))
        assertNull(CopilotAccessibility.announcement("x".repeat(100), "x".repeat(399)))
    }

    @Test
    fun `a meaningful chunk is announced once, with progress`() {
        val text = CopilotAccessibility.announcement("x".repeat(100), "x".repeat(560))
        assertTrue(text!!.contains("still replying"))
        assertTrue(text.contains("560"))
    }

    @Test
    fun `terminal transitions are always announced and say what happened`() {
        assertTrue(CopilotAccessibility.announcement("a".repeat(10), "done", finished = true)!!.startsWith("Response complete"))
        assertTrue(CopilotAccessibility.announcement("a", "boom", failed = true)!!.startsWith("Response failed"))
        assertTrue(CopilotAccessibility.announcement("a", "cut", interrupted = true)!!.startsWith("Response interrupted"))
    }

    @Test
    fun `the announced tail is bounded`() {
        val text = CopilotAccessibility.announcement(null, "y".repeat(900), finished = true)!!
        assertTrue(text.length < 200)
    }

    @Test
    fun `an empty update announces nothing`() {
        assertNull(CopilotAccessibility.announcement("abc", "", finished = false))
    }
}

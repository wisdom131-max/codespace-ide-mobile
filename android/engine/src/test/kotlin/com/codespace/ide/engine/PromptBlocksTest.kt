package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBlocksTest {
    @Test fun emptyWhenNothingToSay() {
        assertEquals("", PromptBlocks.render("", "", ""))
    }
    @Test fun joinsInOrder() {
        assertEquals("A" + 10.toChar() + 10.toChar() + "B", PromptBlocks.render("A", "B", ""))
    }
    @Test fun respectsTheCap() {
        val big = "x".repeat(900)
        val out = PromptBlocks.render(big, big, big, maxChars = 1000)
        assertTrue("must not exceed the cap", out.length <= 1000)
        assertTrue("first block still present", out.startsWith("x"))
    }
}

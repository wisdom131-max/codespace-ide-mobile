package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionAnchorsTest {

    @Test
    fun noRulesMeansNoBlock() {
        assertEquals("", AttentionAnchors.block(emptyList()))
        assertEquals("", AttentionAnchors.block(listOf("  ", "")))
    }

    @Test
    fun theCountIsCapped() {
        assertEquals(3, AttentionAnchors.block((1..9).map { "rule " + it }, maxRules = 3).lines().size)
    }

    @Test
    fun theLengthIsCapped() {
        // This used to assert one line, which encoded the old behaviour of dropping every rule
        // after an over-long one. The intent is the total cap, not the line count: the second rule
        // is now truncated to fit instead of being silently thrown away.
        val out = AttentionAnchors.block(listOf("a".repeat(300), "b".repeat(300)), maxChars = 400)
        assertTrue("the body must stay within the cap", out.length <= 410)
        assertEquals("both rules survive, the second truncated", 2, out.lines().size)
    }

    @Test
    fun aRepeatedRuleAppearsOnce() {
        val out = AttentionAnchors.block(listOf("be brief", "be brief"))
        assertEquals(1, out.lines().size)
        assertTrue(out.startsWith("- be brief"))
    }
}

class AttentionAnchorsTruncationTest {

    @Test
    fun oneOverlongRuleStillAnchorsSomething() {
        val out = AttentionAnchors.block(listOf("a".repeat(500)), maxChars = 40)
        assertTrue("an overlong rule must not silently vanish", out.isNotEmpty())
        assertTrue(out.length <= 42)
    }

    @Test
    fun aLaterRuleIsNotLostBehindALongOne() {
        val out = AttentionAnchors.block(listOf("x".repeat(20), "be brief"), maxChars = 60)
        assertEquals(2, out.lines().size)
    }
}

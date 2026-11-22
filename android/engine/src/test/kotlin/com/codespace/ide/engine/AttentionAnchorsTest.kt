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
        val out = AttentionAnchors.block(listOf("a".repeat(300), "b".repeat(300)), maxChars = 400)
        assertEquals(1, out.lines().size)
    }

    @Test
    fun aRepeatedRuleAppearsOnce() {
        val out = AttentionAnchors.block(listOf("be brief", "be brief"))
        assertEquals(1, out.lines().size)
        assertTrue(out.startsWith("- be brief"))
    }
}

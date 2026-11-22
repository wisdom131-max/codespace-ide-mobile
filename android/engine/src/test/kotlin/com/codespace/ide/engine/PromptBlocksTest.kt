package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBlocksTest {

    @Test
    fun nothingInNothingOut() {
        assertEquals("", PromptBlocks.render("", "", ""))
    }

    @Test
    fun eachBlockIsFramedSoItsEndIsUnambiguous() {
        val out = PromptBlocks.render("A", "B", "")
        assertTrue(out.contains("<section name=\"plan\">"))
        assertTrue(out.contains("<section name=\"ledger\">"))
        assertTrue(out.contains("</section>"))
        assertFalse("the rules section must not appear when empty", out.contains("name=\"rules\""))
    }

    @Test
    fun rulesAreAnchoredAsBullets() {
        val out = PromptBlocks.render("", "", "always run the tests")
        assertTrue(out.contains("- always run the tests"))
    }

    @Test
    fun anAlreadyBulletedRuleDoesNotBecomeADoubleBullet() {
        val out = PromptBlocks.render("", "", "- always run the tests")
        assertTrue(out.contains("- always run the tests"))
        assertFalse(out.contains("- - "))
    }

    @Test
    fun aRuleThatFitsIsNotDropped() {
        val out = PromptBlocks.render("", "", "keep this rule", maxChars = 1200)
        assertTrue(out.contains("keep this rule"))
    }

    @Test
    fun theCapIsNeverExceeded() {
        val big = "x".repeat(5000)
        val out = PromptBlocks.render(big, big, big, maxChars = 1000)
        assertTrue("cap respected, was " + out.length, out.length <= 1000)
    }

    @Test
    fun aSectionIsNeverCutThroughItsClosingTag() {
        val big = "x".repeat(5000)
        val out = PromptBlocks.render(big, big, "", maxChars = 1000)
        val opens = Regex("<section name=").findAll(out).count()
        val closes = Regex("</section>").findAll(out).count()
        assertEquals("every opened section must close", opens, closes)
    }
}

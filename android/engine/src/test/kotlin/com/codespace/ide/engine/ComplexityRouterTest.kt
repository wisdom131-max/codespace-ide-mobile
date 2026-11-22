package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplexityRouterTest {

    @Test
    fun aTypoFixIsLight() {
        val r = ComplexityRouter.route("typo", "ASK")
        assertEquals(ComplexityRouter.Tier.LIGHT, r.tier)
    }

    @Test
    fun anArchitectureQuestionIsHeavy() {
        val r = ComplexityRouter.route("Refactor the whole project and investigate the root cause", "AGENT")
        assertEquals(ComplexityRouter.Tier.HEAVY, r.tier)
    }

    @Test
    fun aWriteModeNeverRoutesLight() {
        val r = ComplexityRouter.route("rename it", "EDIT")
        assertTrue("a write is never light", r.tier != ComplexityRouter.Tier.LIGHT)
    }

    @Test
    fun anUnknownRequestStaysStandard() {
        val r = ComplexityRouter.route("change the padding on the settings row", "AGENT")
        assertEquals(ComplexityRouter.Tier.STANDARD, r.tier)
    }

    @Test
    fun chainedStepsRaiseTheTier() {
        val r = ComplexityRouter.route("read it, then fix it, and then run the tests", "AGENT")
        assertTrue(r.tier != ComplexityRouter.Tier.LIGHT)
    }

    @Test
    fun emptyIsLightAndSaysWhy() {
        val r = ComplexityRouter.route("")
        assertEquals(ComplexityRouter.Tier.LIGHT, r.tier)
        assertTrue(r.reasons.isNotEmpty())
    }
}

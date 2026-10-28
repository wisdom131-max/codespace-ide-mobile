package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A(b) WP5: catalog matching, budget scaling, and the override rule.
 */
class ModelCatalogTest {

    @Test
    fun matchesBySubstringAcrossProviders() {
        val gem = ModelCatalog.capabilityFor("gemini", "gemini-3.8-flash")
        assertEquals(1_048_576, gem?.contextTokens)
        // Same vendor model surfacing under an OpenRouter id still matches.
        val or = ModelCatalog.capabilityFor("openrouter", "openai/gpt-6.1-terra-pro")
        assertEquals(1_050_000, or?.contextTokens)
        val deep = ModelCatalog.capabilityFor("deepseek", "deepseek-v4.1-flash-1017")
        assertEquals(1_048_576, deep?.contextTokens)
    }

    @Test
    fun unknownModelIsNull() {
        assertNull(ModelCatalog.capabilityFor("groq", "totally-unknown-model"))
    }

    @Test
    fun budgetScalesWithContextWindowAndStaysCapped() {
        // Big-window model: scaled 1,048,576*3*0.6 = 1,887k -> capped at the user max.
        val big = ModelCatalog.capabilityFor("gemini", "gemini-3.8-flash")!!
        assertEquals(48_000, ModelCatalog.payloadBudgetChars(big))
        // Small-window model: 131,072*3*0.6 = 236k -> still capped.
        val small = ModelCatalog.capabilityFor("groq", "llama-3.3-70b-instruct")!!
        assertEquals(48_000, ModelCatalog.payloadBudgetChars(small))
        // A tiny 20k-token model: 20,000*3*0.6 = 36,000 chars — under the cap.
        val tiny = ModelCapability("tiny-test", 20_000, supportsImages = false, supportsPdf = false)
        assertEquals(36_000, ModelCatalog.payloadBudgetChars(tiny))
        // Floor: a 2k-token model would get 3,600 -> floored at 8,000.
        val floor = ModelCapability("floor-test", 2_000, supportsImages = false, supportsPdf = false)
        assertEquals(8_000, ModelCatalog.payloadBudgetChars(floor))
        // Unknown model -> user max, behavior unchanged from the pre-catalog era.
        assertEquals(48_000, ModelCatalog.payloadBudgetChars(null))
        // User max is respected when smaller than the scaled estimate.
        val tinyKnown = ModelCapability("c", 500_000, supportsImages = false, supportsPdf = false)
        assertEquals(20_000, ModelCatalog.payloadBudgetChars(tinyKnown, userMaxChars = 20_000))
    }

    @Test
    fun overrideWinsOverBundled() {
        val bundled = ModelCatalog.capabilityFor("gemini", "gemini-3.8-flash")
        val custom = ModelCapability("gemini-3.8-flash", 555_000, supportsImages = true, supportsPdf = false)
        assertEquals(555_000, ModelCatalog.withOverride(bundled, custom)?.contextTokens)
        assertNull(ModelCatalog.withOverride(null, null))
        // Override absent -> bundled row stands.
        assertEquals(bundled, ModelCatalog.withOverride(bundled, null))
    }

    @Test
    fun reasoningFlagOnlyWhereTheControlExists() {
        assertTrue(ModelCatalog.capabilityFor("gemini", "gemini-3.8-flash")!!.supportsReasoning)
        assertTrue(ModelCatalog.capabilityFor("anthropic", "claude-sonnet-5.5")!!.supportsReasoning)
        assertEquals(false, ModelCatalog.capabilityFor("deepseek", "deepseek-v4.1")!!.supportsReasoning)
        assertEquals(false, ModelCatalog.capabilityFor("groq", "llama-3.3-70b")!!.supportsPdf)
    }
}

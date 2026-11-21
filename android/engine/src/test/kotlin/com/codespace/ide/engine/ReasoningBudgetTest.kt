package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.1 — the reasoning dial must reach the wire.
 *
 * These tests exist because the defect was not "a wrong number", it was "the number nobody read".
 * Every case below is a provider the app actually offers, so a regression here is a regression the
 * owner would feel as "the effort menu does nothing" again.
 */
class ReasoningBudgetTest {

    // ── effort name → budget ────────────────────────────────────────────────────────────────

    @Test
    fun `explicit off is the only way to disable thinking`() {
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forEffort("off"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forEffort("OFF"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forEffort(" none "))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forEffort("disabled"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forEffort("0"))
    }

    @Test
    fun `a missing effort keeps todays behaviour rather than turning thinking off`() {
        // Before this type existed, every native-thinking request carried 1024. A null must not
        // silently become "no thinking" or an un-migrated caller loses a feature it already had.
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forEffort(null))
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forEffort(""))
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forEffort("low"))
    }

    @Test
    fun `the three menu entries map to three distinct budgets`() {
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forEffort("low"))
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forEffort("medium"))
        assertEquals(ReasoningBudget.HIGH, ReasoningBudget.forEffort("high"))
        assertTrue(ReasoningBudget.LOW < ReasoningBudget.MEDIUM)
        assertTrue(ReasoningBudget.MEDIUM < ReasoningBudget.HIGH)
    }

    @Test
    fun `the picker default is medium`() {
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forEffort("default"))
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forEffort("Medium (default)"))
    }

    @Test
    fun `an unknown name falls back to medium instead of disabling reasoning`() {
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forEffort("turbo"))
    }

    @Test
    fun `aliases resolve`() {
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forEffort("minimal"))
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forEffort("med"))
        assertEquals(ReasoningBudget.HIGH, ReasoningBudget.forEffort("max"))
        assertEquals(ReasoningBudget.HIGH, ReasoningBudget.forEffort("xhigh"))
    }

    // ── which providers can take a control at all ──────────────────────────────────────────

    @Test
    fun `native thinking providers are supported without a preset row`() {
        assertTrue(ReasoningBudget.supports("gemini"))
        assertTrue(ReasoningBudget.supports("anthropic"))
        // Gemini and Anthropic are native adapters, not preset rows, so byId() is null for them.
        assertEquals(null, ProviderPresets.byId("gemini"))
        assertEquals(null, ProviderPresets.byId("anthropic"))
    }

    @Test
    fun `effort kind presets are supported`() {
        assertTrue(ReasoningBudget.supports("openai"))
        assertTrue(ReasoningBudget.supports("openrouter"))
        assertTrue(ReasoningBudget.supports("xai"))
    }

    @Test
    fun `providers that reject a reasoning parameter are not supported`() {
        for (id in listOf("deepseek", "groq", "mistral", "ollama")) {
            assertFalse("$id must not be offered a reasoning control", ReasoningBudget.supports(id))
        }
    }

    @Test
    fun `an unknown or custom endpoint is not supported and gets zero`() {
        assertFalse(ReasoningBudget.supports("some-custom-endpoint"))
        assertFalse(ReasoningBudget.supports(null))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider("some-custom-endpoint", "high"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider(null, "high"))
    }

    // ── provider + effort → the number that goes into EngineConfig ─────────────────────────

    @Test
    fun `wiring this in changes nothing until someone chooses`() {
        // Native providers keep the 1024 they always sent.
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forProvider("gemini", null))
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forProvider("anthropic", null))
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forProvider("anthropic", "  "))
        // Effort-kind providers keep sending nothing, because a 400 on a non-reasoning model is
        // worse than a menu entry that has not been touched yet.
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider("openai", null))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider("openrouter", null))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider("xai", null))
    }

    @Test
    fun `opting in is what turns an effort-kind provider on`() {
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.forProvider("openai", "low"))
        assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forProvider("openai", "medium"))
        assertEquals(ReasoningBudget.HIGH, ReasoningBudget.forProvider("openai", "high"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider("openai", "off"))
    }

    @Test
    fun `defaults differ by provider kind and that is the point`() {
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.defaultFor("gemini"))
        assertEquals(ReasoningBudget.LOW, ReasoningBudget.defaultFor("anthropic"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.defaultFor("openai"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.defaultFor("deepseek"))
        assertEquals(ReasoningBudget.OFF, ReasoningBudget.defaultFor(null))
    }

    @Test
    fun `the reported defect is fixed - high now reaches openai instead of zero`() {
        // This is the regression guard. The old panel passed 0 for every non-native provider, and
        // OpenAIAdapter.reasoningEffort returns null for 0, so reasoning_effort was never sent.
        val oldBehaviour = OpenAIAdapter().reasoningEffort(
            EngineConfig(
                modelId = "m",
                systemInstruction = "",
                providerId = "openai",
                baseUrl = "https://api.openai.com/v1/chat/completions",
                apiKey = "k",
                reasoningBudget = 0,
            )
        )
        assertNull("a zero budget must send nothing, which is what the old panel did", oldBehaviour)

        val high = ReasoningBudget.forProvider("openai", "high")
        assertEquals(ReasoningBudget.HIGH, high)
        assertTrue("a high setting must not resolve to the off value", high > 0)
    }

    @Test
    fun `the effort the adapter derives from the budget matches the effort chosen`() {
        // OpenAIAdapter maps budget -> low/medium/high, so the round trip has to survive it.
        fun wire(effort: String): String? =
            OpenAIAdapter().reasoningEffort(
                EngineConfig(
                    modelId = "m",
                    systemInstruction = "",
                    providerId = "openai",
                    baseUrl = "https://api.openai.com/v1/chat/completions",
                    apiKey = "k",
                    reasoningBudget = ReasoningBudget.forProvider("openai", effort),
                )
            )

        assertEquals("low", wire("low"))
        assertEquals("medium", wire("medium"))
        assertEquals("high", wire("high"))
        assertEquals(null, wire("off"))
    }

    @Test
    fun `native providers receive the chosen budget unchanged`() {
        for (provider in listOf("gemini", "anthropic")) {
            assertEquals(ReasoningBudget.HIGH, ReasoningBudget.forProvider(provider, "high"))
            assertEquals(ReasoningBudget.MEDIUM, ReasoningBudget.forProvider(provider, "medium"))
            assertEquals(ReasoningBudget.LOW, ReasoningBudget.forProvider(provider, "low"))
            assertEquals(ReasoningBudget.OFF, ReasoningBudget.forProvider(provider, "off"))
        }
    }

    @Test
    fun `anthropic clamps a budget below its floor`() {
        // Anthropic refuses budget_tokens under 1024. The adapter clamps; this proves the value the
        // panel would send is already at or above the floor, so the clamp never has to invent one.
        val config = EngineConfig(
            modelId = "m",
            systemInstruction = "",
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com/v1/messages",
            apiKey = "k",
            reasoningBudget = ReasoningBudget.forProvider("anthropic", "low"),
        )
        assertTrue(ReasoningBudget.forProvider("anthropic", "low") >= 1024)
        assertTrue(AnthropicAdapter().thinkingJson(config)!!.contains("\"budget_tokens\":1024"))
    }

    // ── the reverse direction, used by the picker to show the truth ────────────────────────

    @Test
    fun `a budget maps back to the name the picker shows`() {
        assertEquals("off", ReasoningBudget.nameFor(ReasoningBudget.OFF))
        assertEquals("low", ReasoningBudget.nameFor(ReasoningBudget.LOW))
        assertEquals("medium", ReasoningBudget.nameFor(ReasoningBudget.MEDIUM))
        assertEquals("high", ReasoningBudget.nameFor(ReasoningBudget.HIGH))
    }

    @Test
    fun `every menu entry survives a round trip through the wire value`() {
        for (name in listOf("off", "low", "medium", "high")) {
            val budget = ReasoningBudget.forProvider("anthropic", name)
            assertEquals(name, ReasoningBudget.nameFor(budget))
        }
    }
}

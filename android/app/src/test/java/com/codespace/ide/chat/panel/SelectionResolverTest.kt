package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U1 default-selection rules (build 657 finding d). Pure JVM. */
class SelectionResolverTest {

    private val slot = { pid: String -> com.codespace.ide.chat.keys.SlotNaming.primarySlot(pid) }

    private fun cfg(withKey: List<String> = emptyList(),
                    custom: List<Pair<String, String>> = emptyList()) =
        ConfiguredProviders(withKey, custom)

    @Test
    fun nothingConfiguredShowsSetupNeverAFailingSelection() {
        assertEquals(ResolvedSelection.NeedsSetup, SelectionResolver.resolve(null, cfg(), slot))
    }

    @Test
    fun firstProviderWithAKeyIsTheDefault() {
        val r = SelectionResolver.resolve(null, cfg(withKey = listOf("gemini", "anthropic")), slot)
        val sel = (r as ResolvedSelection.Ready).selection
        assertEquals("gemini", sel.providerId)
        assertEquals("ai_GEMINI", sel.keySlot)
        assertEquals("gemini-3.8-flash", sel.modelId)
        assertEquals(SelectionResolver.GEMINI_BASE, sel.baseUrl)
    }

    @Test
    fun anthropicDefaultUsesTheClaudeSlotAndItsOwnModel() {
        val sel = (SelectionResolver.resolve(null, cfg(withKey = listOf("anthropic")), slot)
            as ResolvedSelection.Ready).selection
        assertEquals("ai_CLAUDE", sel.keySlot)
        assertEquals(PanelSelection.ANTHROPIC_DEFAULT_MODEL, sel.modelId)
        assertEquals(PanelSelection.ANTHROPIC_BASE, sel.baseUrl)
    }

    /** THE 657 BUG: ollama + a gemini model id. Rejected at restore AND unproducible at apply. */
    @Test
    fun theMismatchedOllamaGeminiPairFromBuild657IsDiscarded() {
        val bad = PanelSelection("ollama", "", "gemini-3.8-flash",
            "http://localhost:11434/v1/chat/completions")
        val c = cfg(withKey = listOf("gemini"))
        assertFalse("the persisted 657 pair must not be coherent", SelectionResolver.isCoherent(bad, c))
        // Restore falls through to the configured Gemini provider instead of the broken pair.
        val sel = (SelectionResolver.resolve(bad, c, slot) as ResolvedSelection.Ready).selection
        assertEquals("gemini", sel.providerId)
        // A preset click can no longer produce the pair either: the model is NOT inherited.
        val applied = SelectionResolver.applyProvider("ollama",
            PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", SelectionResolver.GEMINI_BASE),
            typedModel = null, cfg = c, keySlotFor = slot)!!
        assertEquals("", applied.modelId)
        assertFalse(SelectionResolver.isCoherent(applied, c))
    }

    @Test
    fun foreignFamilyModelIsRejectedUnderNativeAndPresetProviders() {
        assertTrue(SelectionResolver.modelBelongsToAnotherFamily("openai", "claude-opus-5-5"))
        assertTrue(SelectionResolver.modelBelongsToAnotherFamily("anthropic", "gemini-3.8-flash"))
        assertTrue(SelectionResolver.modelBelongsToAnotherFamily("gemini", "claude-x"))
        assertFalse("gateways may serve any family",
            SelectionResolver.modelBelongsToAnotherFamily("openrouter", "anthropic/claude-3.7-sonnet"))
        assertFalse(SelectionResolver.modelBelongsToAnotherFamily("custom_hf", "gemini-compat"))
        assertFalse(SelectionResolver.modelBelongsToAnotherFamily("ollama", "llama3.2"))
    }

    @Test
    fun applyingAProviderNeverCarriesTheOldModelAcross() {
        val prev = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", SelectionResolver.GEMINI_BASE)
        val c = cfg(withKey = listOf("gemini", "openai"))
        val openai = SelectionResolver.applyProvider("openai", prev, null, c, slot)!!
        assertEquals("", openai.modelId)
        val typed = SelectionResolver.applyProvider("openai", prev, "gpt-4o-mini", c, slot)!!
        assertEquals("gpt-4o-mini", typed.modelId)
        assertEquals("ai_OPENAI", typed.keySlot)
        assertTrue(SelectionResolver.isCoherent(typed, c))
    }

    @Test
    fun reapplyingTheSameProviderKeepsItsOwnModel() {
        val prev = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", SelectionResolver.GEMINI_BASE)
        val again = SelectionResolver.applyProvider("gemini", prev, null, cfg(withKey = listOf("gemini")), slot)!!
        assertEquals("gemini-3.8-flash", again.modelId)
    }

    @Test
    fun persistedLastUsedWinsWhenCoherentAndStillConfigured() {
        val persisted = PanelSelection("anthropic", "ai_CLAUDE", "claude-opus-5-5", PanelSelection.ANTHROPIC_BASE)
        val sel = (SelectionResolver.resolve(persisted, cfg(withKey = listOf("gemini", "anthropic")), slot)
            as ResolvedSelection.Ready).selection
        assertEquals("anthropic", sel.providerId)
    }

    @Test
    fun persistedSelectionForADeletedKeyFallsBackToAConfiguredProvider() {
        val persisted = PanelSelection("anthropic", "ai_CLAUDE", "claude-opus-5-5", PanelSelection.ANTHROPIC_BASE)
        val sel = (SelectionResolver.resolve(persisted, cfg(withKey = listOf("gemini")), slot)
            as ResolvedSelection.Ready).selection
        assertEquals("gemini", sel.providerId)
    }

    @Test
    fun persistedPairWithWrongBaseUrlIsDiscarded() {
        val persisted = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", "https://example.invalid/chat/completions")
        assertFalse(SelectionResolver.isCoherent(persisted, cfg(withKey = listOf("gemini"))))
    }

    @Test
    fun keyedPresetWithoutADefaultModelIsNotAutoPicked() {
        // OpenAI has a key but no bundled default model: owner must choose one, so no auto default.
        assertEquals(ResolvedSelection.NeedsSetup,
            SelectionResolver.resolve(null, cfg(withKey = listOf("openai")), slot))
    }

    @Test
    fun keylessOllamaIsNeverAutoPickedAsTheDefault() {
        assertEquals(ResolvedSelection.NeedsSetup, SelectionResolver.resolve(null, cfg(), slot))
    }

    @Test
    fun customEndpointIsCoherentWithItsOwnBaseUrl() {
        val c = cfg(custom = listOf("custom_hf" to "https://router.huggingface.co/v1/chat/completions"))
        val sel = SelectionResolver.applyProvider("custom_hf",
            PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", SelectionResolver.GEMINI_BASE),
            "meta-llama/Llama-3.1-8B-Instruct", c, slot)!!
        assertEquals("ai_CUSTOM_HF", sel.keySlot)
        assertTrue(SelectionResolver.isCoherent(sel, c))
        assertNull(SelectionResolver.baseUrlFor("custom_missing", c.customEndpoints))
    }
}

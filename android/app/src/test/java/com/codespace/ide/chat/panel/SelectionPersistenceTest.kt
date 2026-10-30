package com.codespace.ide.chat.panel

import com.codespace.ide.chat.keys.SlotNaming
import com.codespace.ide.chat.keys.StoredCredentialsSnapshot
import com.codespace.ide.chat.keys.StoredEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDIT DEFECT D: an endpoint URL edit must not throw the chosen model away.
 *
 * The selection persists a CACHE of the endpoint URL. `resolve` re-reads the current URL and
 * keeps the model; identity is provider + model, so a model can never cross providers.
 */
class SelectionPersistenceTest {

    private fun endpoint(baseUrl: String) = StoredEndpoint(
        id = "box", label = "Box", baseUrl = baseUrl, enabled = true, hasKey = true,
        providerId = "custom_box",
    )

    private fun cfg(baseUrl: String) = configuredProvidersFrom(
        StoredCredentialsSnapshot(keySlots = emptyList(), endpoints = listOf(endpoint(baseUrl))),
    )

    private fun slotFor(pid: String) = SlotNaming.primarySlot(pid)

    @Test
    fun anEndpointUrlEditKeepsTheSelectedModel() {
        val edited = cfg("https://box2.example/v1")
        val persisted = PanelSelection(
            providerId = "custom_box",
            keySlot = "ai_CUSTOM_BOX",
            modelId = "model-beta",
            baseUrl = "https://box.example/v1", // the stale cache
        )
        val resolved = SelectionResolver.resolve(persisted, edited, keySlotFor = { slotFor(it) })
        assertTrue("the pair is still usable after the URL changed", resolved is ResolvedSelection.Ready)
        val ready = resolved as ResolvedSelection.Ready
        assertEquals("the chosen model is NOT discarded", "model-beta", ready.selection.modelId)
        assertEquals("the request uses the CURRENT endpoint URL",
            "https://box2.example/v1", ready.selection.baseUrl)
    }

    @Test
    fun aModelFromAnotherFamilyIsStillRefused() {
        // A gemini key IS configured, so the only thing that can refuse this pair is the family
        // rule - which is exactly what this test is about.
        val geminiCfg = configuredProvidersFrom(
            StoredCredentialsSnapshot(
                keySlots = listOf(com.codespace.ide.chat.keys.StoredKeySlot(
                    "gemini", null, com.codespace.ide.chat.keys.SlotNaming.primarySlot("gemini"), true)),
                endpoints = emptyList(),
            ),
        )
        // A KNOWN single-family provider must never accept another family's id. (A custom
        // endpoint is deliberately exempt: it may proxy any family, so the model id alone
        // cannot prove a mismatch there.)
        val wrong = PanelSelection(
            providerId = "gemini",
            keySlot = "ai_GEMINI",
            modelId = "claude-opus-5-5",
            baseUrl = "https://generativelanguage.googleapis.com",
        )
        assertFalse("a model must never be carried across known providers",
            SelectionResolver.isCoherent(wrong, geminiCfg))
    }

    @Test
    fun anUnknownProviderIsNotCoherent() {
        val cfg = cfg("https://box.example/v1")
        val gone = PanelSelection(
            providerId = "custom_deleted",
            keySlot = "ai_CUSTOM_DELETED",
            modelId = "model-beta",
            baseUrl = "https://box.example/v1",
        )
        assertFalse(SelectionResolver.isCoherent(gone, cfg))
    }

    @Test
    fun currentBasePrefersTheEndpointAndFallsBackForPresets() {
        val cfg = cfg("https://box2.example/v1")
        assertEquals("https://box2.example/v1",
            SelectionResolver.currentBaseUrl("custom_box", "https://stale.example/v1", cfg))
        assertEquals("https://api.anthropic.com/v1/messages",
            SelectionResolver.currentBaseUrl("anthropic", "https://api.anthropic.com/v1/messages", cfg))
    }
}

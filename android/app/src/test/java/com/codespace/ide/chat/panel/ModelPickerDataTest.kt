package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 8 — what the picker is allowed to claim.
 *
 * The spec was already tested; what was never tested is the DATA behind it: which models a
 * provider is actually configured with, which are catalog-only, and that picking one changes
 * the model and NOTHING else about the selection (provider, endpoint, key slot).
 */
class ModelPickerDataTest {

    private fun sel(provider: String, model: String, baseUrl: String = "https://preset/v1") =
        PanelSelection(provider, "ai_" + provider.uppercase(), model, baseUrl)

    private fun slotKey(pid: String) = "ai_" + pid.uppercase()

    @Test
    fun theProvidersDefaultModelIsAPreferredRow() {
        val rows = ModelPickerData.modelsFor("openai", "gpt-6.1-terra-pro")
        val first = rows.first()
        assertEquals("gpt-6.1-terra-pro", first.modelId)
        assertFalse("the configured default is not a catalog-only entry", first.isOther)
    }

    @Test
    fun manualAndLiveModelsArePreferredAndDeduplicated() {
        val rows = ModelPickerData.modelsFor(
            providerId = "custom_x",
            providerDefault = "a",
            manual = listOf("b", "a", " "),
            live = listOf("b", "c"),
        )
        val preferred = rows.filter { !it.isOther }.map { it.modelId }
        assertEquals(listOf("a", "b", "c"), preferred)
    }

    @Test
    fun everyBundledCatalogModelStillAppearsAsCatalogOnly() {
        val rows = ModelPickerData.modelsFor("openai", "gpt-6.1-terra-pro")
        val others = rows.filter { it.isOther }.map { it.modelId }
        ModelCatalog.BUNDLED.forEach { cap ->
            if (cap.idMatch != "gpt-6.1-terra-pro") {
                assertTrue("the catalog entry " + cap.idMatch + " must not vanish", others.contains(cap.idMatch))
            }
        }
        assertFalse("a model already preferred is not duplicated into Other",
            others.contains("gpt-6.1-terra-pro"))
    }

    @Test
    fun reasoningSupportComesFromTheCatalogNotFromGuessing() {
        val rows = ModelPickerData.modelsFor("openai", "gpt-6.1-terra-pro")
        val reasoning = rows.single { it.modelId == "gpt-6.1-terra-pro" }
        assertTrue(reasoning.supportsReasoning)
        val llama = rows.single { it.modelId == "llama-3.3-70b" }
        assertFalse("llama has no reasoning control in this build", llama.supportsReasoning)
    }

    @Test
    fun noPriceIsInvented() {
        val rows = ModelPickerData.modelsFor("openai", "gpt-6.1-terra-pro")
        assertTrue("this build has no price data, so no row may show one",
            rows.all { it.priceText == null })
    }

    @Test
    fun pickingAModelOfTheSameProviderChangesOnlyTheModel() {
        val current = sel("openai", "old-model", "https://custom-endpoint/v1")
        val picked = ModelPickerData.modelsFor("openai", "gpt-6.1-terra-pro").first()
        val next = ModelPickerData.selectionFor(picked, current, ::slotKey)
        assertEquals("gpt-6.1-terra-pro", next.modelId)
        assertEquals("the endpoint the owner set up is untouched", "https://custom-endpoint/v1", next.baseUrl)
        assertEquals(current.providerId, next.providerId)
        assertEquals(current.keySlot, next.keySlot)
    }

    @Test
    fun pickingAcrossProvidersIsDelegatedToTheResolversRules() {
        val current = sel("openai", "gpt-6.1-terra-pro")
        val foreign = PickerModel("gemini", "gemini-3.8-flash", "gemini-3.8-flash", true, 1_000_000)
        var asked: Pair<String, String>? = null
        val resolved = PanelSelection("gemini", "ai_GEMINI", "gemini-3.8-flash", "https://native/gemini")
        val next = ModelPickerData.selectionFor(foreign, current, ::slotKey) { pid, mid ->
            asked = pid to mid
            resolved
        }
        assertEquals("gemini" to "gemini-3.8-flash", asked)
        assertEquals("the resolver owns the endpoint, not the picker", resolved, next)
    }

    @Test
    fun withoutAResolverThePickerRefusesToChangeProvider() {
        // Native providers (gemini, anthropic) are NOT ProviderPresets, so deriving their
        // endpoint inside the picker was wrong: byId("gemini") is null and the previous
        // provider's endpoint would have been reused. No resolver -> no provider change.
        val current = sel("openai", "gpt-6.1-terra-pro")
        val foreign = PickerModel("gemini", "gemini-3.8-flash", "gemini-3.8-flash", true, 1_000_000)
        assertEquals(current, ModelPickerData.selectionFor(foreign, current, ::slotKey))
    }

    @Test
    fun theEffortLabelMatchesWhatTheEngineActuallySends() {
        // Native adapters send a fixed 1024-token budget; nothing else sends reasoning.
        assertEquals(Effort.LOW, ModelPickerData.effortFor("gemini"))
        assertEquals(Effort.LOW, ModelPickerData.effortFor("anthropic"))
        assertEquals(Effort.LOW, ModelPickerData.effortFor("openai"))
    }
}

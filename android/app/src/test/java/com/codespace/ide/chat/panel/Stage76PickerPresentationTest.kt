package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN 7.6 — the picker's wording, tested without a device. */
class Stage76PickerPresentationTest {

    private fun model(
        providerId: String = "gemini",
        modelId: String = "gemini-2.5-pro",
        name: String = "Gemini 2.5 Pro",
        reasoning: Boolean = true,
        tokens: Int? = 1_048_576,
        price: String? = null,
        other: Boolean = false,
    ) = PickerModel(providerId, modelId, name, reasoning, tokens, price, other)

    @Test
    fun `provider and model identity are separate and both shown`() {
        val h = ModelPickerPresentation.header("Google Gemini", model())
        assertEquals("Google Gemini \u00b7 Gemini 2.5 Pro", h)
        assertTrue(ModelPickerPresentation.header("Custom", model(providerId = "custom", name = ""))
            .contains("gemini-2.5-pro"))
    }

    @Test
    fun `no selection is stated rather than implied`() {
        assertEquals("No model selected", ModelPickerPresentation.header("Google", null))
        assertTrue(ModelPickerPresentation.selectedNote(null, "Google").contains("Sending is disabled"))
    }

    @Test
    fun `an unknown context window is said to be unknown, never blank`() {
        val lines = ModelPickerPresentation.capabilityLines(model(tokens = null), customEndpoint = false)
        assertTrue(lines.any { it.contains("not known") })
        assertTrue(lines.none { it.contains("128000") })
    }

    @Test
    fun `a custom endpoint never has capabilities assumed`() {
        val lines = ModelPickerPresentation.capabilityLines(
            model(providerId = "custom", reasoning = false, tokens = null), customEndpoint = true,
        )
        assertTrue(lines.first().contains("capabilities are not verified"))
        assertTrue(lines.any { it.contains("not known for this endpoint") })
    }

    @Test
    fun `reasoning support is stated as supported or not offered, not guessed`() {
        assertTrue(ModelPickerPresentation.capabilityLines(model(reasoning = true), false)
            .any { it.contains("supported") })
        assertTrue(ModelPickerPresentation.capabilityLines(model(reasoning = false), false)
            .any { it.contains("not offered") })
    }

    @Test
    fun `an alternative is offered as a choice that changes nothing`() {
        val text = ModelPickerPresentation.alternative(model(name = "Gemini 2.5 Flash"), "it is faster", "Google")
        assertTrue(text.contains("Try Google \u00b7 Gemini 2.5 Flash"))
        assertTrue(text.contains("Nothing changes unless you pick it"))
    }

    @Test
    fun `dismissing without choosing keeps the previous selection`() {
        val before = "gemini" to "gemini-2.5-pro"
        assertNull(ModelPickerPresentation.selectionAfterDismiss(null, null))
        assertEquals(before, ModelPickerPresentation.selectionAfterDismiss(before, null))
        assertEquals("openai" to "gpt-4o", ModelPickerPresentation.selectionAfterDismiss(before, "openai" to "gpt-4o"))
    }

    @Test
    fun `unsupported capability lines do not invent a limit`() {
        val lines = ModelPickerPresentation.unsupported(model(reasoning = false, tokens = null), customEndpoint = false)
        assertTrue(lines.any { it.contains("No reasoning-effort control") })
        assertTrue(lines.any { it.contains("Context limit unknown") })
        assertTrue(ModelPickerPresentation.unsupported(model(), false).isEmpty())
    }

    @Test
    fun `an empty search says it is a filter, not a failure`() {
        assertTrue(ModelPickerPresentation.emptySearch("").contains("No models are available"))
        assertTrue(ModelPickerPresentation.emptySearch("gpt").contains("filtered, not empty"))
    }
}

package com.codespace.ide.chat.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT FIVE, STAGE 5.1 — declared capability facts, and a selector that never moves the user. */
class Stage51ProviderCapabilitiesTest {

    @Test
    fun theCatalogueKnowsTheModelsThePickerShips() {
        assertNotNull(ProviderCatalogue.profile("gemini", "gemini-3.8-flash"))
        assertNotNull(ProviderCatalogue.profile("anthropic", "claude-opus-5-5"))
        assertNotNull(ProviderCatalogue.provider("gemini"))
        assertTrue("each known model states both limits", ProviderCatalogue.allModels().all { it.limits.complete })
        assertTrue("limits are positive", ProviderCatalogue.allModels().all {
            (it.limits.contextTokens ?: 0) > 0 && (it.limits.maxOutputTokens ?: 0) > 0
        })
    }

    @Test
    fun anEndpointNobodyCharacterisedIsUnknownRatherThanGuessed() {
        val custom = ProviderCatalogue.resolve("custom", "my-local-llama")
        assertNull("no invented context window", custom.limits.contextTokens)
        assertNull(custom.limits.maxOutputTokens)
        assertFalse(custom.verified)
        assertTrue(custom.capabilities.isEmpty())
        assertEquals("custom/my-local-llama", custom.label)
    }

    @Test
    fun aRequestThatFitsIsSuitableWithNoReasons() {
        val a = ProviderSelector.assess(
            ProviderCatalogue.resolve("gemini", "gemini-3.8-flash"),
            TaskRequirement(estimatedContextTokens = 12_000, needsTools = true),
        )
        assertEquals(Suitability.SUITABLE, a.suitability)
        assertTrue(a.reasons.isEmpty())
        assertTrue(a.usable)
    }

    @Test
    fun aRequestLargerThanTheWindowIsUnusableAndSaysWhy() {
        val a = ProviderSelector.assess(
            ProviderCatalogue.resolve("anthropic", "claude-opus-5-5"),
            TaskRequirement(estimatedContextTokens = 500_000),
        )
        assertEquals(Suitability.UNSUITABLE, a.suitability)
        assertTrue(a.reasons.first(), a.reasons.first().contains("holds 200000"))
        assertFalse(a.usable)
    }

    @Test
    fun aNearlyFullWindowIsFlaggedRiskyNotRefused() {
        val a = ProviderSelector.assess(
            ProviderCatalogue.resolve("anthropic", "claude-opus-5-5"),
            TaskRequirement(estimatedContextTokens = 170_000),
        )
        assertEquals(Suitability.RISKY, a.suitability)
        assertTrue(a.usable)
        assertTrue(a.reasons.first().contains("80%"))
    }

    @Test
    fun toolsOnAnUnknownModelAreRiskyNotProofOfFailure() {
        val a = ProviderSelector.assess(
            ProviderCatalogue.resolve("custom", "my-local-llama"),
            TaskRequirement(estimatedContextTokens = 1_000, needsTools = true),
        )
        assertEquals("an unverified provider is not declared broken", Suitability.RISKY, a.suitability)
        assertTrue(a.usable)
    }

    @Test
    fun aVerifiedGapIsUnusable() {
        // Anthropic states structured output through a tool call, so a request that needs a response
        // format must not be sent there silently.
        val a = ProviderSelector.assess(
            ProviderCatalogue.resolve("anthropic", "claude-opus-5-5"),
            TaskRequirement(estimatedContextTokens = 1_000, needsStructuredOutput = true),
        )
        assertEquals(Suitability.UNSUITABLE, a.suitability)
        assertTrue(a.reasons.first().contains("structured output"))
    }

    @Test
    fun theUserSelectionIsNeverSwitchedEvenWhenItDoesNotFit() {
        val chosen = ProviderCatalogue.resolve("anthropic", "claude-opus-5-5")
        val selection = ProviderSelector.choose(chosen, TaskRequirement(estimatedContextTokens = 900_000))
        assertEquals("chosen stays chosen", chosen.label, selection.chosen.label)
        assertEquals(Suitability.UNSUITABLE, selection.assessment.suitability)
        assertNotNull("an alternative is offered", selection.alternative)
        assertNotNull(selection.alternative!!.profile.limits.contextTokens)
    }

    @Test
    fun aSuitableChoiceNeedsNoAlternative() {
        val selection = ProviderSelector.choose(
            ProviderCatalogue.resolve("gemini", "gemini-3.8-pro"),
            TaskRequirement(estimatedContextTokens = 5_000),
        )
        assertEquals(Suitability.SUITABLE, selection.assessment.suitability)
        assertNull(selection.alternative)
    }

    @Test
    fun theOfferedAlternativeIsOneThatActuallyFits() {
        val selection = ProviderSelector.choose(
            ProviderCatalogue.resolve("anthropic", "claude-opus-5-5"),
            TaskRequirement(estimatedContextTokens = 400_000, needsTools = true),
        )
        val alt = selection.alternative
        assertNotNull(alt)
        assertTrue(alt!!.usable)
        assertTrue(
            "alternative fits the request",
            (alt.profile.limits.contextTokens ?: 0) >= 400_000,
        )
        assertTrue("and can call tools", alt.profile.capabilities.contains(ProviderCapability.TOOL_CALLS))
    }

    @Test
    fun providerRestrictionsAreReportable() {
        val anthropic = ProviderCatalogue.provider("anthropic")!!
        assertTrue(anthropic.restrictions().any { it.contains("structured output") })
        assertEquals(emptyList<String>(), ProviderCatalogue.provider("gemini")!!.restrictions())
    }
}

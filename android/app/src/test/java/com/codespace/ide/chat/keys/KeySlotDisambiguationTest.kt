package com.codespace.ide.chat.keys

import com.codespace.ide.engine.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 (owner report 2026-11-08): "AI key rejected" on every message while Settings > Test listed
 * the models, plus custom endpoint ids that END IN DIGITS (custom_grok_3, custom_grok_custom_2).
 *
 * Two defects, both pinned here with FAKE keys only:
 *
 *  1. SLOT AMBIGUITY. Save writes ai_<PROVIDER.UPPERCASE> (SlotNaming.customStoreKey), so an
 *     endpoint "grok_3" stores its key as ai_CUSTOM_GROK_3. The reader treated a trailing _3 as a
 *     POOL index and attributed that key to provider "custom_grok", so the endpoint looked keyless
 *     and the slot picker pointed at the wrong name.
 *  2. ONE SENTENCE FOR TWO CAUSES. Auth fired for any 401/403 with no provider and no slot named,
 *     and the missing-key path printed its own unrelated sentence, so a provider rejection and a
 *     key that was never sent were indistinguishable.
 *
 * VS Code reference for the classification and the storage shape (fetched 2026-11-08):
 *  - vscode-copilot-chat src/extension/byok/vscode-node/byokContribution.ts: BYOK is a per-provider
 *    registration over a single BYOKStorageService, i.e. keys are addressed by provider identity,
 *    which is the same thing this test asserts (endpoint identity, not slot arithmetic).
 *  - The 401 wording split follows microsoft/vscode's error hydration idea (hydrateBYOKErrorMessages
 *    as cited earlier): the provider's own message is carried and the app's sentence stays separate.
 *    Line anchors for both are UNVERIFIED in this turn: the raw fetch returns a single collapsed
 *    line, so no honest file:line can be quoted for them.
 */
class KeySlotDisambiguationTest {

    private val fakePrimary = "sk-fake-primary-0000000000"
    private val fakePool2 = "sk-fake-pool2-1111111111"

    private fun slots(vararg pairs: Pair<String, String>): Map<String, String> = pairs.toMap()

    @Test
    fun anEndpointIdEndingInDigitsKeepsItsOwnKey() {
        // The owner's exact shape: endpoint "grok_3" -> slot ai_CUSTOM_GROK_3.
        val raw = slots("ai_CUSTOM_GROK_3" to fakePrimary)
        val slots = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok", "grok_3"))
        assertEquals(1, slots.size)
        assertEquals("custom_grok_3", slots[0].providerId)
        assertNull("the trailing _3 is part of the id, not a pool index", slots[0].poolIndex)
        assertTrue(slots[0].hasKey)
    }

    @Test
    fun anEndpointIdWithAnEmbeddedDigitSuffixKeepsItsOwnKey() {
        val raw = slots("ai_CUSTOM_GROK_CUSTOM_2" to fakePrimary)
        val slots = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok", "grok_custom_2"))
        assertEquals("custom_grok_custom_2", slots[0].providerId)
        assertNull(slots[0].poolIndex)
    }

    @Test
    fun aRealPoolSuffixStillReadsAsAPoolSuffix() {
        // No endpoint owns ai_CUSTOM_GROK_2 here except "grok" itself, so it is grok's second key.
        val raw = slots("ai_CUSTOM_GROK" to fakePrimary, "ai_CUSTOM_GROK_2" to fakePool2)
        val slots = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok"))
        val primary = slots.first { it.slotName == "ai_CUSTOM_GROK" }
        val pool = slots.first { it.slotName == "ai_CUSTOM_GROK_2" }
        assertEquals("custom_grok", primary.providerId)
        assertEquals("custom_grok", pool.providerId)
        assertEquals(2, pool.poolIndex)
        assertEquals(2, slots.count { it.hasKey })
    }

    @Test
    fun builtInProviderPoolIsUnchanged() {
        // The built-in path must behave exactly as before: ai_GEMINI_2 is gemini's second key.
        val raw = slots("ai_GEMINI" to fakePrimary, "ai_GEMINI_2" to fakePool2)
        val slots = LegacyKeyShapes.normalizeKeySlots(raw, emptyList())
        assertEquals(listOf("gemini", "gemini"), slots.map { it.providerId })
        assertEquals(listOf(null, 2), slots.map { it.poolIndex })
    }

    @Test
    fun longestEndpointMatchWinsWhenIdsNest() {
        // "grok_3" (longer base) must beat "grok" + pool 3, which was the shipped behaviour.
        val raw = slots("ai_CUSTOM_GROK_3" to fakePrimary)
        val withBoth = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok", "grok_3"))
        assertEquals("custom_grok_3", withBoth[0].providerId)

        // Without the longer endpoint known, the old pool reading still applies - that is the
        // documented ambiguity, and it is why the ids themselves must be known at read time.
        val withoutIt = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok"))
        assertEquals("custom_grok", withoutIt[0].providerId)
        assertEquals(3, withoutIt[0].poolIndex)
    }

    @Test
    fun theMigrationStillSeesEveryEntryAndRewritesNothing() {
        // Read-only guarantee: every ai_ slot in the map appears in the result exactly once, the
        // slotName is the ORIGINAL string, and the map itself is untouched.
        val raw = slots(
            "ai_GEMINI" to fakePrimary,
            "ai_CUSTOM_GROK_3" to fakePool2,
            "ai_CUSTOM_EMPTY_2" to "",
            "proj_abc" to "not-a-key",
            "ai_active" to "gemini",
        )
        val before = raw.toMap()
        val slots = LegacyKeyShapes.normalizeKeySlots(raw, listOf("grok_3", "empty_2"))
        println("[keys] resolved: " + slots.joinToString { it.slotName + "->" + it.providerId + "/" + it.poolIndex + " hasKey=" + it.hasKey })
        assertEquals(3, slots.size)
        assertTrue(slots.any { it.slotName == "ai_CUSTOM_EMPTY_2" && !it.hasKey })
        assertEquals(before, raw)
    }

    @Test
    fun aProviderRejectionNamesTheProviderAndTheSlot() {
        val err = ChatError.Auth(
            providerLabel = "custom_grok_3",
            slotName = "ai_CUSTOM_GROK_3",
            detail = "Invalid API Key",
        )
        println("[keys] auth verdict: " + err.userVerdict)
        assertTrue(err.userVerdict.contains("custom_grok_3"))
        assertTrue(err.userVerdict.contains("ai_CUSTOM_GROK_3"))
        assertTrue(err.userVerdict.contains("401"))
        assertTrue(err.userVerdict.contains("Invalid API Key"))
        assertFalse("a rejected key is never fixed by retrying", err.retryable)
    }

    @Test
    fun aMissingKeyIsADifferentSentenceAndNeverSaysRejected() {
        val verdict = ChatError.Auth.missingKeyVerdict("custom_grok_3", "ai_CUSTOM_GROK_3")
        println("[keys] missing verdict: " + verdict)
        assertTrue(verdict.startsWith("No key found for custom_grok_3"))
        assertTrue(verdict.contains("ai_CUSTOM_GROK_3"))
        assertFalse("the local case must not read as a provider rejection", verdict.contains("rejected"))
    }

    @Test
    fun theProviderMessageIsSanitizedBeforeItCanBeShown() {
        // A body that quotes the key must not leak it into a card, a log or a screenshot.
        val body = """{"error":{"message":"Incorrect API key provided: sk-live-abcdef1234567890 - check it"}}"""
        val err = com.codespace.ide.engine.ErrorTaxonomy.classify(
            401, emptyMap(), body, "/v1/chat/completions", providerLabel = "groq", keySlotName = "ai_CUSTOM_GROK_3",
        ) as ChatError.Auth
        println("[keys] sanitized: " + err.userVerdict)
        assertFalse("no key material may survive redaction", err.userVerdict.contains("abcdef1234567890"))
        assertTrue(err.userVerdict.contains("groq"))
    }
}

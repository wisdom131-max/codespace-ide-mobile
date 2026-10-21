package com.codespace.ide.chat.capabilities

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * G-E-b seam tests (advisor GO 2026-10-21, flag OFF by default).
 *
 * The OFF-equivalence rows are the contract the advisor ruled on: with the
 * switch off, supportsImagesFor returns the provider constant EXACTLY for
 * every selection — the send path is byte-identical by construction, and
 * these tests pin that construction against regression.
 */
class ChatCapabilitySeamTest {

    @Before fun setUp() { ChatCapabilityFlags.capabilityTableEnabled = false; ModelCapabilityOverrides.clearAll() }
    @After fun tearDown() { ChatCapabilityFlags.capabilityTableEnabled = false; ModelCapabilityOverrides.clearAll() }

    private fun withFlagOn(body: () -> Unit) {
        ChatCapabilityFlags.capabilityTableEnabled = true
        try { body() } finally { ChatCapabilityFlags.capabilityTableEnabled = false }
    }

    // ── flag OFF: byte-identical equivalence (the advisor's pinned contract) ──

    @Test fun flagOff_returnsProviderConstantExactly_forEverySelection() {
        for (sel in listOf("openai:gpt-4o", "claude:x", "custom_hf:meta-llama/x", "vendor:y:colon-model")) {
            assertEquals(true, ChatCapabilitySeam.supportsImagesFor(sel, true))
            assertEquals(false, ChatCapabilitySeam.supportsImagesFor(sel, false))
        }
    }

    @Test fun flagOff_nullProviderStaysAllowed_todayRule() {
        assertEquals(true, ChatCapabilitySeam.supportsImagesFor("unregistered:model", null))
        assertEquals(true, ChatCapabilitySeam.supportsImagesFor("", null))
    }

    @Test fun flagOff_noOfferAndNoPickerLine_deadUIDoesNotExist() {
        // even for a pair the bundled table KNOWS: while the switch is off
        // there is no offer row and no picker line anywhere
        assertFalse(ChatCapabilitySeam.imagesUnknownFor("openai:gpt-4o", true))
        assertEquals("", ChatCapabilitySeam.pickerLineFor("openai:gpt-4o", true))
    }

    // ── flag ON: resolution chain ──

    @Test fun flagOn_bundledRowBeatsProviderConstant() {
        // "openai"/"gpt-4o" is in the bundled table with acceptsImages=true
        withFlagOn {
            assertTrue(ChatCapabilitySeam.supportsImagesFor("openai:gpt-4o", false))
            assertTrue(ChatCapabilitySeam.supportsImagesFor("openai:gpt-4o", true))
        }
    }

    @Test fun flagOn_unknownPairFallsBackToProviderConstant() {
        withFlagOn {
            assertEquals(true, ChatCapabilitySeam.supportsImagesFor("nobody:never-shipped-model", true))
            assertEquals(false, ChatCapabilitySeam.supportsImagesFor("nobody:never-shipped-model", false))
        }
    }

    @Test fun flagOn_overrideWinsOverBundled() {
        withFlagOn {
            // bundled says gpt-4o accepts images; user override says no
            ChatCapabilitySeam.setImagesOverride("openai:gpt-4o", false)
            assertFalse(ChatCapabilitySeam.supportsImagesFor("openai:gpt-4o", true))
            // clearing the override falls back to the bundled value again
            ChatCapabilitySeam.setImagesOverride("openai:gpt-4o", null)
            assertTrue(ChatCapabilitySeam.supportsImagesFor("openai:gpt-4o", false))
        }
    }

    @Test fun flagOn_offerAppearsExactlyForUnknownPairs() {
        withFlagOn {
            // bundled pair: no offer (the chain already knows)
            assertFalse(ChatCapabilitySeam.imagesUnknownFor("openai:gpt-4o", false))
            // unknown pair: offer
            assertTrue(ChatCapabilitySeam.imagesUnknownFor("nobody:never-shipped-model", false))
        }
        // switch off again: never
        assertFalse(ChatCapabilitySeam.imagesUnknownFor("nobody:never-shipped-model", false))
    }

    @Test fun flagOn_pickerLineCarriesResolvedFieldsAndTableDate() {
        withFlagOn {
            val line = ChatCapabilitySeam.pickerLineFor("openai:gpt-4o", false)
            assertTrue(line, line.contains("Images yes"))
            assertTrue(line, line.contains("table"))
        }
    }

    // ── parse helpers + tri-state cycle ──

    @Test fun parseHelpers_matchStoredSelectionShapes() {
        assertEquals("openai", ChatCapabilitySeam.providerIdOf("openai:gpt-4o"))
        assertEquals("gpt-4o", ChatCapabilitySeam.modelIdOf("openai:gpt-4o"))
        assertNull(ChatCapabilitySeam.providerIdOf("no-prefix"))
        assertNull(ChatCapabilitySeam.modelIdOf("openai:"))
        assertNull(ChatCapabilitySeam.providerIdOf(""))
    }

    @Test fun triStateCycle_fullSequence() {
        assertEquals(true, ChatCapabilitySeam.nextImagesOverride(null))
        assertEquals(false, ChatCapabilitySeam.nextImagesOverride(true))
        assertNull(ChatCapabilitySeam.nextImagesOverride(false))
    }
}

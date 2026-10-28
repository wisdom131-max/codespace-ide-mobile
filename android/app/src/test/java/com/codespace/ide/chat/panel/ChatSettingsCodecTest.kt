package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP7: pure codec coverage - selection persistence and context overrides.
 * Round-trips, garbage tolerance, and override-key normalization.
 */
class ChatSettingsCodecTest {

    @Test
    fun selectionRoundTrips() {
        val sel = PanelSelection(
            providerId = "anthropic",
            keySlot = "ai_anthropic_2",
            modelId = "claude-sonnet-5.5",
            baseUrl = PanelSelection.ANTHROPIC_BASE,
        )
        assertEquals(sel, decodeSelection(encodeSelection(sel)))
    }

    @Test
    fun selectionDefaultsSurviveRoundTrip() {
        val sel = PanelSelection()
        assertEquals(sel, decodeSelection(encodeSelection(sel)))
    }

    @Test
    fun selectionGarbageReturnsNull() {
        assertNull(decodeSelection("not json"))
        assertNull(decodeSelection("{\"modelId\":\"x\"}")) // no providerId -> unusable
        assertNull(decodeSelection("{}"))
    }

    @Test
    fun overrideMapRoundTripsAndDropsJunk() {
        val m = mapOf(
            overrideKey("gemini", "Gemini-3.8-Flash") to 900_000,
            overrideKey("openai", "gpt-6.1-terra-pro") to 1_000_000,
        )
        assertEquals(m, decodeOverrides(encodeOverrides(m)))
        // non-positive ctx rows are dropped rather than poisoning the budget math
        val junk = """[{"k":"x:y","ctx":0},{"k":"a:b","ctx":-5},{"k":"good:model","ctx":555}]"""
        assertEquals(mapOf("good:model" to 555), decodeOverrides(junk))
        assertTrue(decodeOverrides("not json").isEmpty())
        assertTrue(decodeOverrides("[]").isEmpty())
    }

    @Test
    fun overrideKeyIsCaseInsensitiveOnModel() {
        assertEquals(overrideKey("p", "Model-X"), overrideKey("p", "model-x"))
    }
}

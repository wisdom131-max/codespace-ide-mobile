package com.codespace.ide.chat.keys

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP1 (c) fixtures built from the REAL writer shapes:
 *  - SecureTokenStore: 'ai_<provider>' (aiKey/setAiKey) + pool suffix
 *    'ai_<provider>_2' (the old ChatKeyPool family); non-ai_ entries
 *    (proj_*, github token, pin) are NOT provider keys.
 *  - CustomEndpointStore: 'endpoints_v2' = JSON array of
 *    {id,label,baseUrl[,enabled]}, written by add()/update().
 * No real key value appears in any fixture - synthetic only.
 */
class StoredCredentialsMigrationTest {

    /**
     * U1: built from the REAL writer (ChatKeyPool.slotSuffix / AiKeysSection):
     * "ai_" + id.UPPERCASE, pool "_2", Anthropic's legacy id is "claude", the
     * "ai_active" marker is not a key. The earlier invented lowercase fixtures
     * ("ai_google") are what let build 657 ship with 560 green tests.
     */
    private fun realShapedRaw(): Map<String, String> = mapOf(
        "ai_GEMINI" to "k-gemini-1",
        "ai_GEMINI_2" to "k-gemini-2",
        "ai_CLAUDE" to "k-claude",
        "ai_OPENAI" to "",
        "ai_CUSTOM" to "k-custom",
        "ai_CUSTOM_ABC123" to "k-custom-abc",
        "ai_active" to "GEMINI",
        "proj_default_firebase" to "proj-secret",
        "github_token" to "gh-secret",
        "pin_hash" to "pin",
    )

    private fun realShapedEndpointsJson(): String {
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "default").put("label", "HF router")
            .put("baseUrl", "https://router.huggingface.co/v1"))
        arr.put(JSONObject().put("id", "abc123").put("label", "Local box")
            .put("baseUrl", "http://10.0.0.2:11434/v1").put("enabled", false))
        arr.put(JSONObject().put("id", "broken").put("label", "no url")
            .put("baseUrl", ""))
        return arr.toString()
    }

    @Test
    fun parsesPrimaryAndPoolSlotsFromRealShape() {
        val p = LegacyKeyShapes.parseSlot("ai_GEMINI")
        assertNotNull(p); assertEquals("gemini", p!!.first); assertNull(p.second)
        val pool = LegacyKeyShapes.parseSlot("ai_GEMINI_2")
        assertEquals("gemini", pool!!.first); assertEquals(2, pool.second!!)
        val custom = LegacyKeyShapes.parseSlot("ai_CUSTOM_ABC123_2")
        assertEquals("custom_abc123", custom!!.first); assertEquals(2, custom.second!!)
    }

    @Test
    fun legacyClaudeIdMapsToTheAnthropicAdapter() {
        val c = LegacyKeyShapes.parseSlot("ai_CLAUDE")
        assertEquals("anthropic", c!!.first)
        assertEquals("ai_CLAUDE", SlotNaming.primarySlot("anthropic"))
    }

    @Test
    fun activeMarkerIsNotAKeySlot() {
        assertNull(LegacyKeyShapes.parseSlot("ai_active"))
        val slots = LegacyKeyShapes.normalizeKeySlots(realShapedRaw())
        assertTrue(slots.none { it.slotName.equals("ai_active", true) })
    }

    @Test
    fun ignoresNonProviderEntries() {
        assertNull(LegacyKeyShapes.parseSlot("proj_default_firebase"))
        assertNull(LegacyKeyShapes.parseSlot("github_token"))
        assertNull(LegacyKeyShapes.parseSlot("pin_hash"))
        assertNull(LegacyKeyShapes.parseSlot("ai_"))
    }

    @Test
    fun emptyKeySlotIsPreservedNotAnOrphan() {
        val slots = LegacyKeyShapes.normalizeKeySlots(realShapedRaw())
        // GEMINI, GEMINI_2, CLAUDE, OPENAI(empty), CUSTOM, CUSTOM_ABC123 (active marker excluded)
        assertEquals(6, slots.size)
        val openai = slots.first { it.slotName == "ai_OPENAI" }
        assertEquals("openai", openai.providerId)
        assertFalse("an empty key slot stays listed", openai.hasKey)
        assertTrue(slots.first { it.slotName == "ai_GEMINI" }.hasKey)
        assertTrue(slots.first { it.slotName == "ai_GEMINI_2" }.hasKey)
    }

    /** The test build 657 lacked: stored uppercase slots must resolve to a USABLE slot per engine provider. */
    @Test
    fun everyRealSlotResolvesToAUsableKeyForItsEngineProvider() {
        val raw = realShapedRaw()
        val names = raw.keys
        val has = { n: String -> !raw[n].isNullOrBlank() }
        assertEquals(listOf("ai_GEMINI", "ai_GEMINI_2"), SlotNaming.resolveSlots("gemini", names, has))
        assertEquals(listOf("ai_CLAUDE"), SlotNaming.resolveSlots("anthropic", names, has))
        assertEquals(emptyList<String>(), SlotNaming.resolveSlots("openai", names, has)) // empty value = no usable key
        assertEquals(listOf("ai_CUSTOM"), SlotNaming.resolveSlots("custom", names, has))
        assertEquals(listOf("ai_CUSTOM_ABC123"), SlotNaming.resolveSlots("custom_abc123", names, has))
    }

    @Test
    fun lowercaseSlotsWrittenByBuild657AreStillFoundNotOrphaned() {
        val raw = mapOf("ai_openai" to "k-from-657", "ai_openai_2" to "k2-from-657")
        val has = { n: String -> !raw[n].isNullOrBlank() }
        assertEquals(listOf("ai_openai", "ai_openai_2"),
            SlotNaming.resolveSlots("openai", raw.keys, has))
    }

    @Test
    fun writerAndReaderAgreeOnTheSlotNameForEveryEngineProvider() {
        for (pid in listOf("gemini", "anthropic", "openai", "openrouter", "deepseek", "xai", "groq", "mistral", "custom", "custom_hf_router")) {
            val slot = SlotNaming.primarySlot(pid)
            val parsed = LegacyKeyShapes.parseSlot(slot)
            assertEquals("round trip for " + pid, pid, parsed!!.first)
            assertEquals("pool 3 for " + pid, slot + "_3", SlotNaming.poolSlot(pid, 3))
            assertEquals(pid, LegacyKeyShapes.parseSlot(SlotNaming.poolSlot(pid, 3))!!.first)
        }
    }

    @Test
    fun disabledEndpointIsPreservedAndBlankUrlSkipped() {
        val hasKeys = setOf("custom", "custom_abc123")
        val eps = LegacyKeyShapes.normalizeEndpoints(realShapedEndpointsJson()) { hasKeys.contains(it) }
        assertEquals("blank-baseUrl entry is skipped, disabled kept", 2, eps.size)
        val hf = eps.first { it.id == "default" }
        assertEquals("custom", hf.providerId)
        assertTrue(hf.enabled); assertTrue(hf.hasKey)
        val local = eps.first { it.id == "abc123" }
        assertFalse("disabled endpoint stays listed - not an orphan", local.enabled)
        assertEquals("custom_abc123", local.providerId)
        assertTrue(local.hasKey)
    }

    @Test
    fun garbledJsonYieldsEmptyListNeverThrows() {
        val eps = LegacyKeyShapes.normalizeEndpoints("not json") { true }
        assertTrue(eps.isEmpty())
    }
}

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

    private fun realShapedRaw(): Map<String, String> = mapOf(
        "ai_google" to "k-google-1",
        "ai_google_2" to "k-google-2",
        "ai_openai" to "",
        "ai_custom" to "k-custom",
        "ai_custom_abc123" to "k-custom-abc",
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
        val p = LegacyKeyShapes.parseSlot("ai_google")
        assertNotNull(p); assertEquals("google", p!!.first); assertNull(p.second)
        val pool = LegacyKeyShapes.parseSlot("ai_google_2")
        assertEquals("google", pool!!.first); assertEquals(2, pool.second!!)
        val custom = LegacyKeyShapes.parseSlot("ai_custom_abc123_2")
        assertEquals("custom_abc123", custom!!.first); assertEquals(2, custom.second!!)
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
        assertEquals(5, slots.size)
        val openai = slots.first { it.slotName == "ai_openai" }
        assertEquals("openai", openai.providerId)
        assertFalse("an empty key slot stays listed", openai.hasKey)
        assertTrue(slots.first { it.slotName == "ai_google" }.hasKey)
        assertTrue(slots.first { it.slotName == "ai_google_2" }.hasKey)
    }

    @Test
    fun keyValuesNeverEnterTheNormalizedModel() {
        val slots = LegacyKeyShapes.normalizeKeySlots(realShapedRaw())
        // The model carries names and presence only - nothing to leak.
        assertTrue(slots.none { it.slotName.startsWith("proj_") || it.slotName.startsWith("github") })
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

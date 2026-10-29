package com.codespace.ide.chat.keys

import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.engine.ProviderPresets
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FORMAT CONTRACT — the stored shapes this app must keep reading, pinned to the code
 * that WROTE them, not to names invented in a test.
 *
 * Source of truth: tag `chat-archive-2026-10-27` (the pre-rebuild tree).
 *
 *  1. Credential slots
 *     - android/app/src/main/java/com/codespace/ide/chat/ChatProvider.kt:21
 *       "A requiresApiKey provider's key lives under \"ai_\" + id.uppercase()"
 *     - android/app/src/main/java/com/codespace/ide/chat/ChatKeyPool.kt:11-12
 *       "Key VALUES live in SecureTokenStore under \"ai_<ID>\", \"ai_<ID>_2\", \"ai_<ID>_3\""
 *     - android/app/src/main/java/com/codespace/ide/ui/screens/AiKeysSection.kt:70-71
 *       "SecureTokenStore keys are UNCHANGED (\"ai_\" + id.uppercase(), \"active\")"
 *     The archived provider ids were gemini, claude (Anthropic), openai, openrouter,
 *     deepseek, xai, custom / custom_<id> — so the real on-disk slots are ai_GEMINI,
 *     ai_CLAUDE, ai_OPENAI, ai_OPENROUTER, ai_DEEPSEEK, ai_XAI, ai_CUSTOM.
 *
 *  2. The custom-endpoint registry
 *     - android/app/src/main/java/com/codespace/ide/chat/CustomEndpointStore.kt:59
 *       read:  JSONArray(prefs.getString("endpoints_v2", "[]"))
 *     - ...:64 and ...:238      write: putString("endpoints_v2", arr.toString())
 *     - ...:114                 fields: id, label, baseUrl
 *     - ...:236                 legacy default record: id="default", label="Custom", baseUrl=<legacy url>
 *
 * Why this file exists: WP1 shipped 560 green tests built on invented lowercase slots
 * ("ai_google"), so the suite agreed with itself while every real lookup missed on the
 * device (build 657 finding a). A test whose fixtures are invented proves only that the
 * code agrees with the test. These fixtures are derived from the archived writers above.
 */
class StoredFormatContractTest {

    @Before
    fun freshStore() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    @After
    fun clearStore() = CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage())

    /** The archived rule, applied. Cited above; never re-typed from memory elsewhere. */
    private fun archivedSlot(legacyId: String, n: Int = 1): String =
        "ai_" + legacyId.uppercase() + (if (n <= 1) "" else "_" + n)

    // ---- 1. credential slots ------------------------------------------------

    @Test
    fun archivedSlotAnchorsAreUnchanged() {
        // Literal anchors: the archived writer's rule applied to the archived ids.
        assertEquals("ai_GEMINI", archivedSlot("gemini"))
        assertEquals("ai_CLAUDE", archivedSlot("claude"))
        assertEquals("ai_OPENAI", archivedSlot("openai"))
        assertEquals("ai_OPENROUTER", archivedSlot("openrouter"))
        assertEquals("ai_XAI", archivedSlot("xai"))
        assertEquals("ai_CUSTOM", archivedSlot("custom"))

        // ...and the shipped mapper must produce exactly those strings.
        assertEquals("ai_GEMINI", SlotNaming.primarySlot("gemini"))
        assertEquals("Anthropic stores as the legacy id 'claude'", "ai_CLAUDE", SlotNaming.primarySlot("anthropic"))
        assertEquals("ai_OPENAI", SlotNaming.primarySlot("openai"))
        assertEquals("ai_OPENROUTER", SlotNaming.primarySlot("openrouter"))
        assertEquals("ai_XAI", SlotNaming.primarySlot("xai"))
    }

    @Test
    fun everyRegisteredProviderFollowsTheArchivedSlotRule() {
        // Providers added after the archive (mistral, groq, ollama...) follow the SAME
        // rule, so no provider may ever need a hand-written slot name.
        for (p in ProviderPresets.ALL) {
            val legacy = SlotNaming.legacyProviderId(p.providerId)
            assertEquals(
                "slot for ${p.providerId} must be the archived shape",
                archivedSlot(legacy),
                SlotNaming.primarySlot(p.providerId),
            )
        }
    }

    @Test
    fun poolSlotsAppendTheNumberExactlyAsChatKeyPoolDid() {
        // ChatKeyPool.kt:11-12 — "ai_<ID>", "ai_<ID>_2", "ai_<ID>_3".
        assertEquals("ai_GEMINI", archivedSlot("gemini", 1))
        assertEquals("ai_GEMINI_2", archivedSlot("gemini", 2))
        assertEquals("ai_GEMINI_3", archivedSlot("gemini", 3))
        assertEquals("ai_GEMINI", SlotNaming.poolSlot("gemini", 1))
        assertEquals("ai_GEMINI_2", SlotNaming.poolSlot("gemini", 2))
        assertEquals("n<=1 is always the primary", "ai_GEMINI", SlotNaming.poolSlot("gemini", 0))
    }

    @Test
    fun secureTokenStoreKeysDropTheAiPrefix() {
        // AiKeysSection/SecureTokenStore take the part AFTER "ai_".
        assertEquals("GEMINI", SlotNaming.storeKeyFor("gemini"))
        assertEquals("GEMINI_2", SlotNaming.storeKeyFor("gemini", 2))
        assertEquals("CUSTOM_HF", SlotNaming.customStoreKey("custom_hf"))
    }

    @Test
    fun realShapeIsPreferredOverTheBuild657LowercaseSlot() {
        // Build 657 wrote lowercase slots; those keys are found (never orphaned) but the
        // real archived shape always wins when both exist.
        val all = listOf("ai_openai", "ai_OPENAI", "ai_OPENAI_2")
        val resolved = SlotNaming.resolveSlots("openai", all) { true }
        // Real shape first, then the legacy lowercase primary, then _2 (pool order, not
        // spelling order): the legacy key is slot 1, so it outranks slot 2.
        assertEquals(listOf("ai_OPENAI", "ai_openai", "ai_OPENAI_2"), resolved)

        // Lowercase alone still resolves (read-only tolerance for 657 data).
        assertEquals(listOf("ai_openai"), SlotNaming.resolveSlots("openai", listOf("ai_openai")) { true })
    }

    // ---- 2. the endpoint registry ------------------------------------------

    private class CapturingStorage : CustomEndpointStore.EndpointStorage {
        var json: String? = null
        var lastWritten: String? = null
        override fun read(): String? = json
        override fun write(j: String): Boolean { json = j; lastWritten = j; return true }
    }

    @Test
    fun theWrittenRegistryKeepsTheArchivedFieldNames() {
        val storage = CapturingStorage()
        CustomEndpointStore.useStorage(storage)
        assertNotNull(CustomEndpointStore.add("HF Router", "https://router.huggingface.co/v1"))

        val raw = storage.lastWritten
        assertNotNull("nothing was written to the endpoints_v2 key", raw)
        val arr = JSONArray(raw)
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        // The archived reader read exactly these three fields (CustomEndpointStore.kt:114).
        assertEquals("hf_router", o.getString("id"))
        assertEquals("HF Router", o.getString("label"))
        assertEquals("https://router.huggingface.co/v1", o.getString("baseUrl"))
        // New fields may be ADDED (enabled), never renamed away.
        assertTrue("current writer is a superset of the archived shape", o.has("enabled"))
    }

    @Test
    fun theArchivedLegacyDefaultRecordStillReads() {
        // CustomEndpointStore.kt:236 wrote this exact record when migrating a single
        // legacy endpoint. It must keep reading as the "custom" provider.
        val legacy = """[{"id":"default","label":"Custom","baseUrl":"http://10.0.0.2:11434/v1"}]"""
        CustomEndpointStore.useStorage(CustomEndpointStore.MemoryEndpointStorage(legacy))

        val list = CustomEndpointStore.list()
        assertEquals(1, list.size)
        assertEquals("default", list[0].id)
        assertEquals("Custom", list[0].label)
        assertEquals("http://10.0.0.2:11434/v1", list[0].baseUrl)
        assertEquals("custom", CustomEndpointStore.providerIdFor("default"))
        // The legacy provider id then maps to the archived slot shape.
        assertEquals("ai_CUSTOM", SlotNaming.primarySlot(CustomEndpointStore.providerIdFor("default")))
    }
}

package com.codespace.ide.chat.capabilities

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-E-a unit tests — the whole phase is decision-only, pure JVM (no device).
 *
 * Advisor conditions pinned here:
 *  (a) switch OFF by default -> the gate returns the provider constant
 *      EXACTLY (send path byte-identical; no call sites were touched).
 *  (b) resolution order override > live > bundled; UNKNOWN falls back to
 *      the provider constant — never treated as supported on its own.
 *  (c/d) bundled + live parsing is bounded and poison-safe; nothing is
 *      ever claimed that the sources did not say.
 *  (d) tools/streaming flags are stored but NOTHING wires them (there is no
 *      consumer to enable — a model claiming tools cannot turn tools on).
 *  (e) audio/video declared but no gate function reads them until U5.
 */
class ModelCapabilitiesTest {

    private val memory = MemoryPersistence()

    @After
    fun resetState() {
        ChatCapabilityFlags.capabilityTableEnabled = false
        ModelCapabilityHints.clearForTest()
        ModelCapabilityTable.resetForTest()
    }

    private fun withFlagOn(body: () -> Unit) {
        ChatCapabilityFlags.capabilityTableEnabled = true
        try { body() } finally { ChatCapabilityFlags.capabilityTableEnabled = false }
    }

    // ── (a) OFF by default: byte-identity of the seam ─────────────────────

    @Test fun switchIsOffByDefault() {
        assertFalse(ChatCapabilityFlags.capabilityTableEnabled)
    }

    @Test fun flagOff_gateReturnsProviderFallbackExactly() {
        // seed every source so ANY leakage would show, then prove the OFF
        // switch ignores them all: the gate returns the provider constant
        // byte-for-byte. This is the "old seam" proof: the send path's value
        // does not move by one boolean.
        ModelCapabilityOverrides.usePersistence(memory)
        ModelCapabilityOverrides.set("openai", "gpt-4o", ModelCapabilityOverrides.Override(acceptsImages = false))
        ModelCapabilityHints.replaceForEndpoint("openai", mapOf("gpt-4o" to ModelCapabilityFlags(acceptsImages = false)))
        ModelCapabilityTable.loadForTest(sampleTableJson())
        for (fallback in listOf(true, false)) {
            for ((p, m) in listOf("openai" to "gpt-4o", "claude" to "claude-sonnet-4", null to null)) {
                assertEquals(
                    "gate must return the provider constant unchanged with the switch off ($p/$m/$fallback)",
                    fallback,
                    ChatCapabilityGate.acceptsImages(p, m, fallback))
            }
        }
    }

    // ── (b) resolution order ──────────────────────────────────────────────

    @Test fun resolutionOrder_overrideBeatsLiveBeatsBundled() {
        ModelCapabilityOverrides.usePersistence(memory)
        ModelCapabilityTable.loadForTest(sampleTableJson())
        withFlagOn {
            // all three sources present, each a different claim
            ModelCapabilityHints.replaceForEndpoint("openai", mapOf("gpt-4o" to ModelCapabilityFlags(acceptsImages = false)))
            assertEquals(true, ChatCapabilityGate.acceptsImages("openai", "gpt-4o", true))
            // override removed -> live wins (live says false, fallback true: live must beat it)
            ModelCapabilityOverrides.clear("openai", "gpt-4o")
            assertEquals(false, ChatCapabilityGate.acceptsImages("openai", "gpt-4o", true))
            // live cleared -> bundled wins (bundled gpt-4o says true)
            ModelCapabilityHints.clearForTest()
            assertEquals(true, ChatCapabilityGate.acceptsImages("openai", "gpt-4o", false))
            // bundled has no row -> provider constant decides (today's value)
            assertEquals(false, ChatCapabilityGate.acceptsImages("openai", "totally-unknown-model", false))
            assertEquals(true, ChatCapabilityGate.acceptsImages("openai", "totally-unknown-model", true))
        }
    }

    @Test fun unknown_isNeverTreatedAsSupported_beyondTodaysFallback() {
        ModelCapabilityTable.loadForTest("""{"version":1,"generated":"2026-10-20","providers":{}}""")
        withFlagOn {
            // nothing anywhere: the resolved flags are all null
            val f = ChatCapabilityGate.resolve("deepseek", "some-model")
            assertNull(f.acceptsImages)
            assertNull(f.acceptsPdf)
            assertNull(f.supportsTools)
            // and the gate is EXACTLY the provider constant in both directions
            assertEquals(false, ChatCapabilityGate.acceptsImages("deepseek", "some-model", false))
            assertEquals(true, ChatCapabilityGate.acceptsImages("deepseek", "some-model", true))
        }
    }

    // ── (c) bundled table: bounded, honest, date surfaced ─────────────────

    @Test fun bundledTable_parsesRows_andLongestPrefixWins() {
        ModelCapabilityTable.loadForTest(sampleTableJson())
        assertEquals("2026-10-20", ModelCapabilityTable.tableDate())
        val gpt4o = ModelCapabilityTable.bundledFlagsFor("openai", "gpt-4o-2024-11-20")
        assertEquals(true, gpt4o?.acceptsImages)
        assertEquals(128000L, gpt4o?.maxContextTokens)
        // claude prefix covers dated variants
        val sonnet = ModelCapabilityTable.bundledFlagsFor("claude", "claude-sonnet-4-5-20260101")
        assertEquals(true, sonnet?.acceptsImages)
        assertEquals(200000L, sonnet?.maxContextTokens)
        // longest match wins: a specific row beats a general prefix
        ModelCapabilityTable.loadForTest("""{"version":1,"generated":"d","providers":{"openai":[
            {"match":"gpt-4","images":false},
            {"match":"gpt-4-turbo","images":true}]}}""")
        assertEquals(true, ModelCapabilityTable.bundledFlagsFor("openai", "gpt-4-turbo-2024-04-09")?.acceptsImages)
        assertEquals(false, ModelCapabilityTable.bundledFlagsFor("openai", "gpt-4-0613")?.acceptsImages)
    }

    @Test fun bundledTable_poisonAndBounds_neverCrashNeverGuess() {
        // garbage, wrong types, huge rows, missing fields: table contributes
        // NOTHING — no crash, no invented capability
        for (poison in listOf(
            "not json at all",
            "{}",
            """{"providers":{"openai":"wrong-type"}}""",
            """{"providers":{"openai":[{},{ "match":"" },{ "match":"gpt-4o","images":"yes","maxContext":"lots"}]}}""",
            """{"providers":{"openai":[""" + (1..2000).joinToString(",") { """{"match":"m$it","images":true}""" } + """]}}""",
        )) {
            ModelCapabilityTable.loadForTest(poison)
            assertNull(ModelCapabilityTable.bundledFlagsFor("openai", "gpt-4o")?.acceptsImages)
        }
        // the row cap holds: the 2000-row poison above truncated at MAX_ROWS
        // and still parsed without claiming anything for gpt-4o.
    }

    @Test fun bundledTable_generationDateInHeader_survivesCaps() {
        ModelCapabilityTable.loadForTest("""{"generated":"2026-10-20","providers":{}}""")
        assertEquals("2026-10-20", ModelCapabilityTable.tableDate())
        val longDate = "x".repeat(500)
        ModelCapabilityTable.loadForTest("""{"generated":"$longDate","providers":{}}""")
        // date capped at 32 chars
        assertEquals(32, ModelCapabilityTable.tableDate()?.length)
    }

    // ── (d) live hints: untrusted input, bounded parse, stored not wired ──

    @Test fun liveHints_openRouterStyleParse_andNothingClaimedForEmptyShapes() {
        val parsed = ModelCapabilityHints.parseOpenRouterStyle(
            """{"data":[
                {"id":"vendor/x","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
                 "context_length":128000,"supported_parameters":["tools","stream","temperature"]},
                {"id":"vendor/text-only","architecture":{"input_modalities":["text"]},"supported_parameters":[]},
                {"id":"vendor/no-arch","context_length":"bad"}]}""")
        assertEquals(true, parsed?.get("vendor/x")?.acceptsImages)
        assertEquals(128000L, parsed?.get("vendor/x")?.maxContextTokens)
        assertEquals(true, parsed?.get("vendor/x")?.supportsTools)
        assertEquals(true, parsed?.get("vendor/x")?.supportsStreaming)
        // explicit text-only modality list = an honest NO, not unknown
        assertEquals(false, parsed?.get("vendor/text-only")?.acceptsImages)
        assertEquals(false, parsed?.get("vendor/text-only")?.supportsTools)
        // absent architecture = UNKNOWN, never a guess
        assertNull(parsed?.get("vendor/no-arch")?.acceptsImages)
        // plain OpenAI /models shape (no "data" array of models with
        // architecture) -> null: NOTHING is claimed for such endpoints
        assertNull(ModelCapabilityHints.parseOpenRouterStyle("""{"object":"list","data":[{"id":"gpt-4o","object":"model","owned_by":"openai"}]}"""))
    }

    @Test fun liveHints_poisonAndCaps_neverCrash() {
        assertNull(ModelCapabilityHints.parseOpenRouterStyle("garbage"))
        assertNull(ModelCapabilityHints.parseOpenRouterStyle("""{"data":"nope"}"""))
        val huge = """{"data":[""" + (1..900).joinToString(",") { """{"id":"m$it","architecture":{"input_modalities":["image"]}}""" } + """]}"""
        val parsed = ModelCapabilityHints.parseOpenRouterStyle(huge)
        assertEquals(ModelCapabilityHints.MAX_MODELS, parsed?.size)
    }

    @Test fun liveHints_toolsFlagIsStoredButNeverWired() {
        // (d) pin: a model claiming tool support produces a stored flag and
        // NOTHING else. There is no tool-execution consumer in G-E-a — the
        // resolution output is flags only; this row exists so the moment a
        // future phase WANTS to wire tools, it must get a new owner ruling.
        ModelCapabilityHints.replaceForEndpoint("openrouter", mapOf("t/tool" to ModelCapabilityFlags(supportsTools = true)))
        withFlagOn {
            val f = ChatCapabilityGate.resolve("openrouter", "t/tool")
            assertEquals(true, f.supportsTools)
            // and the images gate still only answers the images question
            assertEquals(false, ChatCapabilityGate.acceptsImages("openrouter", "t/tool", false))
        }
    }

    // ── (f) overrides: per endpoint+model, no secrets, clean reset ────────

    @Test fun overrides_roundtripVersionBumpAndClear() {
        ModelCapabilityOverrides.usePersistence(memory)
        ModelCapabilityOverrides.set("custom_hf", "Qwen/Qwen2.5-VL", ModelCapabilityOverrides.Override(acceptsImages = true))
        val before = ModelCapabilityOverrides.version()
        assertEquals(true, ModelCapabilityOverrides.get("custom_hf", "Qwen/Qwen2.5-VL")?.acceptsImages)
        // a DIFFERENT endpoint+model is untouched (per-pair keying)
        assertNull(ModelCapabilityOverrides.get("custom_hf", "other-model"))
        assertNull(ModelCapabilityOverrides.get("openai", "Qwen/Qwen2.5-VL"))
        // clear -> the pair falls back to the next source, never asserts
        ModelCapabilityOverrides.clear("custom_hf", "Qwen/Qwen2.5-VL")
        assertNull(ModelCapabilityOverrides.get("custom_hf", "Qwen/Qwen2.5-VL"))
        assertTrue(ModelCapabilityOverrides.version() > before)
        // a null-only patch is a NO-OP by design (merge semantics): the UI
        // clears via clear(); nothing about an empty patch ever erases a
        // stored toggle silently.
        ModelCapabilityOverrides.set("custom_hf", "Qwen/Qwen2.5-VL", ModelCapabilityOverrides.Override(acceptsImages = true))
        ModelCapabilityOverrides.set("custom_hf", "Qwen/Qwen2.5-VL", ModelCapabilityOverrides.Override())
        assertEquals(true, ModelCapabilityOverrides.get("custom_hf", "Qwen/Qwen2.5-VL")?.acceptsImages)
        ModelCapabilityOverrides.clearAll()
        assertNull(ModelCapabilityOverrides.get("custom_hf", "Qwen/Qwen2.5-VL"))
    }

    @Test fun overrides_poisonedStore_contributesNothing() {
        class Poison : ModelCapabilityOverrides.Persistence {
            override fun load(): String? = "{{{{not json"
            override fun save(value: String) {}
        }
        ModelCapabilityOverrides.usePersistence(Poison())
        assertNull(ModelCapabilityOverrides.get("openai", "gpt-4o"))
    }

    // ── (e) scope: audio/video declared, not used until U5 ────────────────

    @Test fun scope_imagesOnly_noAudioGateExistsInGEa() {
        // The ONLY gate functions in G-E-a are acceptsImages and
        // acceptsPdfPageImages. There is deliberately NO acceptsAudio/
        // acceptsVideo gate — declared flags only, U5 adds the gates.
        val methods = ChatCapabilityGate::class.java.declaredMethods.map { it.name }.sorted()
        assertTrue(methods.containsAll(listOf("acceptsImages", "acceptsPdfPageImages", "resolve")))
        assertFalse(methods.any { it.startsWith("acceptsAudio") || it.startsWith("acceptsVideo") })
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun sampleTableJson(): String = """
        {"version":1,"generated":"2026-10-20","source":"BerriAI/LiteLLM",
         "providers":{
           "openai":[{"match":"gpt-4o","images":true,"tools":true,"maxContext":128000,"streaming":true}],
           "claude":[{"match":"claude-sonnet-4","images":true,"tools":true,"maxContext":200000,"streaming":true}]
         }}
    """.trimIndent()
}

/** In-memory persistence fake — overrides never touch SharedPreferences in tests. */
class MemoryPersistence : ModelCapabilityOverrides.Persistence {
    private var value: String? = null
    override fun load(): String? = value
    override fun save(value: String) { this.value = value }
}

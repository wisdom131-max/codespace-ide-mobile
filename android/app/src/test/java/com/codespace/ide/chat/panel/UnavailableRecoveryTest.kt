package com.codespace.ide.chat.panel

import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Check 3 (2026-10-30, verification only): the WHOLE recovery path, end to end, without network.
 *
 * 1 a valid selection exists -> 2 storage becomes unreadable -> 3 Unavailable -> 4 identity intact
 * -> 5 storage readable again -> 6 retry resolves -> 7 same pair comes back Ready -> 8 the request
 * pipeline sends with that pair, with no second user choice in between.
 *
 * No production code is exercised through a UI: the resolver, the reader seams and the real
 * ChatSession transport path are the same objects the panel uses.
 */
class UnavailableRecoveryTest {

    private class Recording : ChatTransport {
        data class Call(val url: String, val headers: Map<String, String>, val payload: String)
        val calls = ArrayList<Call>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls.add(Call(url, headers, payloadJson))
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    /** A storage whose document read FAILS, while its row reads still behave normally. */
    private class FailingRead(private val inner: CustomEndpointStore.MemoryEndpointStorage) :
        CustomEndpointStore.EndpointStorage by inner {
        override fun read(): String? = throw IllegalStateException("storage unavailable")
    }

    private val good = CustomEndpointStore.MemoryEndpointStorage()

    @After
    fun tearDown() {
        CustomEndpointStore.useStorage(good)
    }

    private val stored = PanelSelection(
        providerId = "custom_box",
        keySlot = "ai_CUSTOM_BOX",
        modelId = "model-beta",
        baseUrl = "https://box.example/v1",
    )

    private fun seed() {
        good.json = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("id", "box"); put("label", "Box")
                put("baseUrl", "https://box.example/v1"); put("enabled", true)
            })
        }.toString()
        good.rows["manual_box"] = "model-alpha\nmodel-beta"
        CustomEndpointStore.useStorage(good)
    }

    private fun reader() = StoredCredentialsReader(
        { mapOf("ai_CUSTOM_BOX" to "secret-box") },
        { slot -> if (slot == "ai_CUSTOM_BOX") "secret-box" else null },
        CustomEndpointStore,
    )

    private fun resolveNow(): ResolvedSelection {
        val read = reader().readSnapshot()
        return SelectionResolver.resolve(
            stored,
            configuredProvidersFrom(read.snapshot),
            keySlotFor = { "ai_CUSTOM_BOX" },
            settingsReadable = read.readable,
        )
    }

    @Test
    fun unreadableThenRecoveredSelectionSendsWithoutASecondChoice() {
        seed()

        // 1-3: readable start, then a failing read reports Unavailable (not NeedsSetup).
        assertTrue("sanity: the seeded pair resolves while storage is readable",
            resolveNow() is ResolvedSelection.Ready)
        CustomEndpointStore.useStorage(FailingRead(good))
        val broken = resolveNow()
        assertTrue("2-3: a failed read must surface as Unavailable", broken is ResolvedSelection.Unavailable)

        // 4: identity is untouched by the failure.
        val kept = (broken as ResolvedSelection.Unavailable).selection
        assertEquals("provider survives", "custom_box", kept.providerId)
        assertEquals("model survives", "model-beta", kept.modelId)
        assertEquals("key slot survives", "ai_CUSTOM_BOX", kept.keySlot)
        assertEquals("nothing was invented", "https://box.example/v1", kept.baseUrl)

        // 5-7: storage readable again, retry re-reads and returns the SAME pair.
        CustomEndpointStore.useStorage(good)
        val recovered = resolveNow()
        assertTrue("7: recovery returns Ready", recovered is ResolvedSelection.Ready)
        val ready = (recovered as ResolvedSelection.Ready).selection
        assertEquals("same provider", kept.providerId, ready.providerId)
        assertEquals("same model", kept.modelId, ready.modelId)
        assertEquals("same slot", kept.keySlot, ready.keySlot)

        // 8: the real send path uses that pair with no further user action.
        val t = Recording()
        val session = ChatSession(
            "proj",
            { slot -> reader().keyValue(slot) },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { t },
            baseUrlForProvider = { pid, fallback ->
                SelectionResolver.currentBaseUrl(
                    pid, fallback, configuredProvidersFrom(reader().readSnapshot().snapshot),
                )
            },
        )
        session.select(ready)
        session.send("hi")
        runBlocking { var spins = 0; while (session.ui.value.streaming && spins < 300) { delay(10); spins++ } }

        assertEquals("exactly one attempt", 1, t.calls.size)
        assertEquals("current endpoint URL, built from the recovered config",
            "https://box.example/v1/chat/completions", t.calls[0].url)
        assertEquals("the stored key is used for the kept slot",
            "Bearer secret-box", t.calls[0].headers["Authorization"])
        assertTrue("the kept model id is serialized",
            t.calls[0].payload.contains("\"model\":\"model-beta\""))
    }

    @Test
    fun theFailureNeverBecomesNeedsSetupWhenSomethingIsConfigured() {
        seed()
        CustomEndpointStore.useStorage(FailingRead(good))
        val broken = resolveNow()
        assertTrue("Unavailable, never NeedsSetup, while storage is unreadable",
            broken is ResolvedSelection.Unavailable)
    }
}

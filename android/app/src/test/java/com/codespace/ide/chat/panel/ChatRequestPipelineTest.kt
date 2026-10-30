package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last mile of the audit: what actually goes on the wire for a custom endpoint.
 *
 * These assert the URL, the auth header and the serialized model id for the SAME endpoint with
 * several model ids, plus endpoint isolation and the stale-URL case (audit defect D).
 */
class ChatRequestPipelineTest {

    private class Recording : ChatTransport {
        data class Call(val url: String, val headers: Map<String, String>, val payload: String)
        val calls = ArrayList<Call>()

        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls.add(Call(url, headers, payloadJson))
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private fun session(
        t: ChatTransport,
        base: (String, String) -> String = { _, fb -> fb },
    ) = ChatSession(
        "proj",
        { slot -> "key-" + slot },
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        { t },
        baseUrlForProvider = base,
    )

    private fun settle(s: ChatSession) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < 300) { delay(10); spins++ }
        Unit
    }

    private fun selection(model: String, base: String = "https://box.example/v1") =
        PanelSelection(
            providerId = "custom_box",
            keySlot = "ai_CUSTOM_BOX",
            modelId = model,
            baseUrl = base,
        )

    @Test
    fun theSelectedModelReachesTheUrlHeaderAndBody() {
        val t = Recording()
        val s = session(t)
        s.select(selection("model-beta"))
        s.send("hi")
        settle(s)
        assertEquals("one wire attempt", 1, t.calls.size)
        assertEquals("https://box.example/v1/chat/completions", t.calls[0].url)
        assertEquals("Bearer key-ai_CUSTOM_BOX", t.calls[0].headers["Authorization"])
        assertTrue("the exact model id is serialized",
            t.calls[0].payload.contains("\"model\":\"model-beta\""))
        assertFalse("the key must never ride the body", t.calls[0].payload.contains("key-ai_CUSTOM_BOX"))
    }

    @Test
    fun switchingModelsUnderOneEndpointChangesOnlyTheModelId() {
        val t = Recording()
        val s = session(t)
        s.select(selection("model-alpha"))
        s.send("first")
        settle(s)
        s.select(selection("model-gamma"))
        s.send("second")
        settle(s)
        assertEquals(2, t.calls.size)
        assertTrue(t.calls[0].payload.contains("\"model\":\"model-alpha\""))
        assertTrue(t.calls[1].payload.contains("\"model\":\"model-gamma\""))
        assertEquals("same endpoint, same URL", t.calls[0].url, t.calls[1].url)
        assertEquals("same endpoint, same key slot",
            t.calls[0].headers["Authorization"], t.calls[1].headers["Authorization"])
    }

    @Test
    fun aStaleUrlInTheSelectionIsIgnored() {
        val t = Recording()
        // The store now says box2; the persisted selection still says box (an endpoint edit).
        val s = session(t) { _, _ -> "https://box2.example/v1" }
        s.select(selection("model-beta", base = "https://box.example/v1"))
        s.send("hi")
        settle(s)
        assertEquals("https://box2.example/v1/chat/completions", t.calls[0].url)
        assertFalse(t.calls[0].url.contains("box.example"))
    }

    @Test
    fun twoEndpointsKeepTheirOwnCredentials() {
        val t = Recording()
        val s = session(t)
        s.select(PanelSelection(
            providerId = "custom_alpha", keySlot = "ai_CUSTOM_ALPHA",
            modelId = "alpha-model", baseUrl = "https://alpha.example/v1",
        ))
        s.send("one")
        settle(s)
        s.select(PanelSelection(
            providerId = "custom_beta", keySlot = "ai_CUSTOM_BETA",
            modelId = "beta-model", baseUrl = "https://beta.example/v1",
        ))
        s.send("two")
        settle(s)
        assertEquals("Bearer key-ai_CUSTOM_ALPHA", t.calls[0].headers["Authorization"])
        assertEquals("Bearer key-ai_CUSTOM_BETA", t.calls[1].headers["Authorization"])
        assertFalse("alpha's key must not be reused for beta",
            t.calls[1].headers["Authorization"]!!.contains("ALPHA"))
        assertEquals("https://alpha.example/v1/chat/completions", t.calls[0].url)
        assertEquals("https://beta.example/v1/chat/completions", t.calls[1].url)
    }

    @Test
    fun aBaseThatIsAlreadyAFullPathIsNotDoubled() {
        val t = Recording()
        val s = session(t)
        s.select(selection("model-beta", base = "https://box.example/v1/chat/completions"))
        s.send("hi")
        settle(s)
        assertEquals("https://box.example/v1/chat/completions", t.calls[0].url)
        assertFalse(t.calls[0].url.endsWith("/chat/completions/chat/completions"))
    }
}

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device finding 2026-11-04 #6/#12 — switching key or provider must not erase the conversation.
 *
 * The engine is the only stateful part of a chat (the wire window). Selecting a different
 * provider builds a new one, and before this fix that new engine started empty: the owner saw
 * their whole conversation on screen and was told the app had no memory of it.
 *
 * These tests assert on the PAYLOAD, not the UI: what matters is that the model on the other end
 * really receives the earlier turns.
 */
class ChatSessionProviderSwitchTest {

    /** Records every payload and always answers with a fixed reply. */
    private class Capture(private val reply: String) : ChatTransport {
        val payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"" + reply + "\"}}]}\n")
            emit("data: [DONE]\n")
        }
    }

    private fun session(a: Capture, b: Capture) = ChatSession(
        projectId = "p",
        keyFor = { "k" },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        transportFactory = { if (switched) b else a },
        baseUrlForProvider = { _, fb -> fb },
        poolSlotsFor = { emptyList() },
        failoverOptIn = { false },
        appContext = null,
    )

    private var switched = false

    /** Waits for the turn to finish AND for the answer row to stop growing (Unconfined race). */
    private fun settle(s: ChatSession) = runBlocking {
        var spins = 0
        var last = -1
        while (spins < 400) {
            val st = s.ui.value
            val len = st.rows.filterIsInstance<com.codespace.ide.chat.ui.ChatRowModel.AssistantStream>().sumOf { it.text.length }
            if (!st.streaming && len > 0 && len == last) return@runBlocking
            last = len
            delay(10)
            spins++
        }
    }

    @Test
    fun `switching provider keeps the earlier turns in the new request`() {
        switched = false
        val openai = Capture("first answer")
        val anthropic = Capture("second answer")
        val s = session(openai, anthropic)
        s.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI"))
        s.send("my project is called WireTest")
        settle(s)
        assertEquals(1, openai.payloads.size)

        switched = true
        s.select(PanelSelection(providerId = "anthropic", modelId = "claude-x", keySlot = "ai_ANTHROPIC"))
        s.send("what is my project called?")

        settle(s)
        assertEquals("the new provider was asked once", 1, anthropic.payloads.size)
        val body = anthropic.payloads.first()
        assertTrue("the earlier question travels with the new provider", body.contains("WireTest"))
        assertTrue("earlier answer missing in: " + body.substringAfter("messages\":", "").take(400), body.contains("first answer"))
    }

    @Test
    fun `switching only the key slot does not re-send the conversation`() {
        switched = false
        val openai = Capture("first answer")
        val s = session(openai, Capture("unused"))
        s.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI"))
        s.send("hello there")
        settle(s)

        s.select(PanelSelection(providerId = "openai", modelId = "gpt-x", keySlot = "ai_OPENAI_2"))
        val body = openai.payloads.last()
        assertTrue("same wire, so the first payload is unchanged", body.contains("hello there"))
        assertEquals("the panel did not rebuild the engine", 1, s.ui.value.rows.count {
            it is com.codespace.ide.chat.ui.ChatRowModel.User
        })
    }
}

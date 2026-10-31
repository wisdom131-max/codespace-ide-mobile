package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEMORY CONTINUITY (2026-10-31).
 *
 * The reported defect: chat, close the panel, reopen it, ask about the same thing, and the
 * assistant behaved as though the conversation had never happened. The rows were restored to
 * the SCREEN, but the engine was new and empty, so the request carried no history at all.
 *
 * These tests assert on the BYTES that would go to the provider, because "the screen shows the
 * old messages" is exactly the evidence that used to look convincing while the model stayed
 * blind.
 */
class HistoryRestoreTest {

    private class CapturingTransport : ChatTransport {
        var payload: String? = null
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            payload = payloadJson
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            emit("data: [DONE]")
        }
    }

    private fun config() = EngineConfig(
        modelId = "gpt-test",
        systemInstruction = "sys",
        providerId = "openai",
        baseUrl = "http://127.0.0.1:1",
        apiKey = "k",
    )

    private fun msg(role: ChatRole, text: String) =
        ChatMessage(id = "r-" + text.hashCode(), role = role, text = text, state = MessageState.DONE)

    @Test
    fun `a restored conversation rides the next request`() = runTest {
        val transport = CapturingTransport()
        val engine = ChatEngineImpl("restored", transport)
        engine.restoreHistory(
            listOf(
                msg(ChatRole.USER, "we decided to use clip rendering"),
                msg(ChatRole.ASSISTANT, "noted, clip rendering it is"),
            ),
        )
        engine.send("what did we decide?", emptyList(), config()).toList()
        val body = transport.payload ?: error("nothing was sent")
        assertTrue("the earlier question must be on the wire", body.contains("we decided to use clip rendering"))
        assertTrue("the earlier answer must be on the wire", body.contains("noted, clip rendering it is"))
        assertTrue("and so must this turn", body.contains("what did we decide?"))
    }

    @Test
    fun `restoring replaces instead of doubling the conversation`() = runTest {
        val transport = CapturingTransport()
        val engine = ChatEngineImpl("restored", transport)
        val history = listOf(
            msg(ChatRole.USER, "first question"),
            msg(ChatRole.ASSISTANT, "first answer"),
        )
        engine.restoreHistory(history)
        engine.restoreHistory(history)
        engine.send("second question", emptyList(), config()).toList()
        val body = transport.payload ?: error("nothing was sent")
        assertEquals(
            "a double restore must not send the turn twice",
            1,
            body.split("first question").size - 1,
        )
    }

    @Test
    fun `an unseeded engine still works and carries only the new turn`() = runTest {
        val transport = CapturingTransport()
        val engine = ChatEngineImpl("fresh", transport)
        engine.send("hello", emptyList(), config()).toList()
        val body = transport.payload ?: error("nothing was sent")
        assertTrue(body.contains("hello"))
        assertFalse("no invented history", body.contains("we decided"))
    }

    @Test
    fun `a restored engine keeps appending its own turns`() = runTest {
        val transport = CapturingTransport()
        val engine = ChatEngineImpl("restored", transport)
        engine.restoreHistory(listOf(msg(ChatRole.USER, "older turn")))
        engine.send("newer turn", emptyList(), config()).toList()
        val after = engine.transcript().map { it.text }
        assertEquals(listOf("older turn", "newer turn", "ok"), after)
    }
}

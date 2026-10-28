package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2 (d-min) JVM tests: engine-event -> ChatRowModel mapping through the
 * session (rows, streaming flags, over-budget "Send anyway?", cancel keeps
 * partial text, no-key honest card). No network: a scripted ChatTransport.
 */
class ChatSessionMappingTest {

    private class ScriptedTransport(private val chunks: List<String>) : ChatTransport {
        var payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
            payloads.add(payloadJson)
            return flowOf(*chunks.toTypedArray())
        }
    }

    private class NeverEndingTransport : ChatTransport {
        var payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            payloads.add(payloadJson)
            emit("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"par\"}]}]}}\n\n")
            delay(60_000)
        }
    }

    private val geminiChunks = listOf(
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}]}}\n\n",
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo!\"}]}]}}\n\n",
        "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
    )

    private fun session(transport: ChatTransport, key: String? = "k-test") =
        ChatSession("proj", { if (it == "ai_google") key else null },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { transport })

    private fun awaitIdle(s: ChatSession) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < 400) { delay(10); spins++ }
    }

    @Test
    fun mapsDeltasToStreamRowAndCompletes() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t)
        s.send("hi")
        awaitIdle(s)
        val rows = s.ui.value.rows
        assertEquals(2, rows.size)
        assertTrue(rows[0] is ChatRowModel.User)
        val done = rows[1] as ChatRowModel.AssistantStream
        assertEquals("Hello!", done.text)
        assertFalse(done.cursorOn)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun cancelKeepsPartialTextWithCursorOn() {
        val t = NeverEndingTransport()
        val s = session(t)
        s.send("hi")
        runBlocking {
            var spins = 0
            while (s.ui.value.firstDeltaMs == 0L && spins < 400) { delay(10); spins++ }
        }
        s.cancel()
        awaitIdle(s)
        val last = s.ui.value.rows.last() as ChatRowModel.AssistantStream
        assertEquals("par", last.text)
        assertTrue("cancelled partial keeps the live cursor", last.cursorOn)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun overBudgetCardThenSendAnywayOverridesOnce() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t)
        s.send("x".repeat(60_000))
        awaitIdle(s)
        val over = s.ui.value.rows.last()
        assertTrue("over-budget shows the Send-anyway card", over is ChatRowModel.OverBudget)
        s.sendAnyway()
        awaitIdle(s)
        assertTrue("override reached the transport", t.payloads.isNotEmpty() && t.payloads.last().contains("x"))
        val last = s.ui.value.rows.last() as ChatRowModel.AssistantStream
        assertEquals("Hello!", last.text)
    }

    @Test
    fun missingKeyShowsHonestCardWithoutNetwork() {
        val t = ScriptedTransport(geminiChunks)
        val s = session(t, key = null)
        s.send("hi")
        awaitIdle(s)
        val last = s.ui.value.rows.last() as ChatRowModel.Failed
        assertTrue(last.verdict.startsWith("No key stored"))
        assertTrue("no network without a key", t.payloads.isEmpty())
    }
}

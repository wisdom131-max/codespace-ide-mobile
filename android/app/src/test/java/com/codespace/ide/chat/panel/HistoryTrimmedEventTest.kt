package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatEngineImpl
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.EngineConfig
import com.codespace.ide.engine.EngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 5 — silent history trimming.
 *
 * The engine has always narrowed the outgoing window (HistoryBudget), but nothing told the
 * UI, so the TrimmedHistory row could never appear and the model quietly answered about a
 * conversation it could no longer see.
 */
class HistoryTrimmedEventTest {

    private class Transport : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> =
            flowOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
    }

    private fun config(historyMode: String) = EngineConfig(
        modelId = "gpt-x",
        providerId = "openai",
        baseUrl = "https://example.invalid/v1/chat/completions",
        apiKey = "k",
        maxPayloadChars = 400_000,
    ).copy(historyMode = historyMode)

    private fun send(engine: ChatEngineImpl, text: String, mode: String): List<EngineEvent> = runBlocking {
        engine.send(text, config(mode)).toList()
    }

    @Test
    fun aFirstMessageHasNothingToTrim() {
        val engine = ChatEngineImpl("s", Transport())
        val events = send(engine, "hello", "COMPACT")
        assertTrue(events.none { it is EngineEvent.HistoryTrimmed })
    }

    @Test
    fun droppingOldMessagesIsAnnouncedWithTheCount() {
        val engine = ChatEngineImpl("s", Transport())
        // COMPACT keeps 6 messages. Eight completed exchanges => the window must drop some.
        repeat(8) { i -> send(engine, "message $i", "COMPACT") }
        val events = send(engine, "the ninth", "COMPACT")
        val trimmed = events.filterIsInstance<EngineEvent.HistoryTrimmed>().single()
        assertTrue("the dropped count must be real", trimmed.droppedCount > 0)
        assertEquals("announced before the answer, not after", 0, events.indexOf(trimmed))
    }

    @Test
    fun aWiderWindowTrimsLess() {
        val compact = ChatEngineImpl("a", Transport())
        val full = ChatEngineImpl("b", Transport())
        repeat(8) { i ->
            send(compact, "m$i", "COMPACT")
            send(full, "m$i", "FULL")
        }
        val compactTrim = send(compact, "last", "COMPACT")
            .filterIsInstance<EngineEvent.HistoryTrimmed>().singleOrNull()?.droppedCount ?: 0
        val fullTrim = send(full, "last", "FULL")
            .filterIsInstance<EngineEvent.HistoryTrimmed>().singleOrNull()?.droppedCount ?: 0
        assertTrue("FULL keeps 24 messages, COMPACT 6: it cannot trim more", fullTrim < compactTrim)
    }

    @Test
    fun theTrimmedEventReachesTheTranscriptRow() {
        // The mapping the session performs: a positive count becomes the honest row.
        val rows = listOf<ChatRowModel>(ChatRowModel.TrimmedHistory(3))
        assertEquals(3, (rows[0] as ChatRowModel.TrimmedHistory).droppedCount)
    }
}

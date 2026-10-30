package com.codespace.ide.chat.panel

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptLoad
import com.codespace.ide.chat.TranscriptSink
import com.codespace.ide.chat.ui.ChatRowModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 — switching away from a session that is still streaming, and deleting one.
 *
 * Both are cases where the honest thing to do is easy to get wrong in a way no screen shows:
 * a stopped reply that is saved as finished, and a deleted conversation whose pending save
 * writes it back. An Unconfined scope makes the session's restore and its row collector run
 * synchronously, so these are real assertions rather than "wait a moment and hope".
 */
class ChatSessionInterruptTest {

    private class RecordingSink : TranscriptSink {
        val saves = ArrayList<List<StoredMessage>>()
        var loads = 0

        override suspend fun load(projectId: String, conversationId: String): TranscriptLoad {
            loads++
            return TranscriptLoad()
        }

        override fun save(
            projectId: String,
            conversationId: String,
            messages: List<StoredMessage>,
            onError: (String) -> Unit,
        ) {
            saves.add(messages)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var sink: RecordingSink

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun session(conversationId: String = "s_test"): ChatSession {
        sink = RecordingSink()
        return ChatSession(
            projectId = "p",
            keyFor = { null },
            scope = scope,
            conversationId = conversationId,
            transcripts = sink,
        )
    }

    private fun midStream(session: ChatSession) {
        session.ui.value = session.ui.value.copy(
            rows = listOf(
                ChatRowModel.User("write the parser"),
                ChatRowModel.AssistantStream("partial ans", cursorOn = false),
            ),
            streaming = true,
            lastUserText = "write the parser",
        )
    }

    /** The assistant message in the most recent save, or null. */
    private fun lastAssistant(): StoredMessage? =
        sink.saves.lastOrNull()?.lastOrNull { it.role == "assistant" }

    @Test
    fun `switching stops the stream and keeps the partial reply marked incomplete`() {
        val s = session()
        midStream(s)

        val stopped = s.stopForSwitch()

        assertTrue("a running stream must be reported as stopped", stopped)
        assertFalse("the session must not keep looking busy", s.ui.value.streaming)
        val assistant = lastAssistant()
        assertEquals("partial ans", assistant?.text)
        assertEquals("a cut off reply must not be stored as finished", false, assistant?.complete)
    }

    @Test
    fun `the interruption survives later saves until a new turn starts`() {
        val s = session()
        midStream(s)
        s.stopForSwitch()

        // A later, unrelated state change must not "fix" the reply into a complete one.
        s.ui.value = s.ui.value.copy(lastUserText = "still here")
        assertEquals(false, lastAssistant()?.complete)
    }

    @Test
    fun `a new turn clears the interruption marker`() {
        val s = session()
        midStream(s)
        s.stopForSwitch()

        // clearConversation is the deterministic stand-in for "a new turn": it resets the same
        // marker sendCore resets.
        s.clearConversation()
        s.ui.value = s.ui.value.copy(
            rows = listOf(
                ChatRowModel.User("second question"),
                ChatRowModel.AssistantStream("full answer", cursorOn = false),
            ),
        )
        assertEquals("a finished reply in a new turn is complete", true, lastAssistant()?.complete)
    }

    @Test
    fun `switching a session that is not streaming does nothing`() {
        val s = session()
        s.ui.value = s.ui.value.copy(rows = listOf(ChatRowModel.User("idle")))
        val before = sink.saves.size

        assertFalse(s.stopForSwitch())
        assertEquals("nothing to interrupt, nothing to write", before, sink.saves.size)
    }

    @Test
    fun `a deleted session cannot be recreated by a late save`() {
        val s = session()
        s.ui.value = s.ui.value.copy(rows = listOf(ChatRowModel.User("first")))
        val before = sink.saves.size
        assertTrue("precondition: the session saves while alive", before > 0)

        s.dispose()

        // The exact scenario: something still holds the object and changes its rows after the
        // delete. Without the guard this write recreates the transcript file.
        s.ui.value = s.ui.value.copy(rows = listOf(ChatRowModel.User("first"), ChatRowModel.User("late")))
        assertEquals("a disposed session must never write again", before, sink.saves.size)
    }

    @Test
    fun `a session carries the conversation id it was built with`() {
        assertEquals("s_alpha", session("s_alpha").conversationId)
    }
}

package com.codespace.ide.chat.panel

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptLoad
import com.codespace.ide.chat.TranscriptSink
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 4 — the session's half of persistence.
 *
 * A recording sink on an Unconfined scope makes the restore synchronous, so these are
 * real assertions about the wiring rather than "wait a bit and hope": what gets restored,
 * what gets saved, and - most importantly - that nothing is saved before the restore has
 * decided what the conversation is.
 */
class ChatSessionPersistenceTest {

    private class RecordingSink(
        private val toLoad: TranscriptLoad = TranscriptLoad(),
    ) : TranscriptSink {
        val saves = ArrayList<List<StoredMessage>>()
        var loads = 0
        var errorToReport: String? = null

        override suspend fun load(projectId: String, conversationId: String): TranscriptLoad {
            loads++
            return toLoad
        }

        override fun save(
            projectId: String,
            conversationId: String,
            messages: List<StoredMessage>,
            onError: (String) -> Unit,
        ) {
            saves.add(messages)
            errorToReport?.let(onError)
        }
    }

    private class OkTransport : ChatTransport {
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> =
            flowOf("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}]}}\n\n")
    }

    private fun session(sink: TranscriptSink?) = ChatSession(
        "proj",
        { "k-test" },
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        { OkTransport() },
        transcripts = sink,
    )

    @Test
    fun aStoredConversationIsRestoredOnConstruction() {
        val sink = RecordingSink(TranscriptLoad(listOf(
            StoredMessage("user", "from before", true, 1L),
            StoredMessage("assistant", "and the answer", true, 2L),
        )))
        val s = session(sink)
        val rows = s.ui.value.rows
        assertEquals(2, rows.size)
        assertEquals("from before", (rows[0] as ChatRowModel.User).text)
        assertEquals("and the answer", (rows[1] as ChatRowModel.AssistantStream).text)
        assertEquals(1, sink.loads)
    }

    @Test
    fun anInterruptedStoredReplyIsRestoredWithAnExplanation() {
        val sink = RecordingSink(TranscriptLoad(listOf(
            StoredMessage("user", "q", true, 1L),
            StoredMessage("assistant", "half", false, 2L),
        )))
        val s = session(sink)
        val rows = s.ui.value.rows
        assertEquals(3, rows.size)
        assertEquals("half", (rows[1] as ChatRowModel.AssistantStream).text)
        assertTrue(rows[2] is ChatRowModel.InfoNote)
    }

    @Test
    fun aLoadProblemIsSurfacedAndTheSessionStillWorks() {
        val sink = RecordingSink(TranscriptLoad(emptyList(), "Could not read the saved conversation: EACCES"))
        val s = session(sink)
        val rows = s.ui.value.rows
        assertEquals(1, rows.size)
        assertTrue((rows[0] as ChatRowModel.InfoNote).text.contains("Could not read"))
        // And it is usable: a send still works with a broken store behind it.
        s.send("still works")
        assertFalse("a storage failure must not brick the chat", s.ui.value.rows.isEmpty())
    }

    @Test
    fun nothingIsSavedBeforeTheRestoreHasDecidedWhatTheConversationIs() {
        val sink = RecordingSink(TranscriptLoad(listOf(StoredMessage("user", "old", true, 1L))))
        session(sink)
        // Unconfined means the restore ran during construction; the save that follows it is
        // the normalised transcript, and it must not be an EMPTY one that wipes history.
        assertTrue("the first save must not be empty", sink.saves.isNotEmpty())
        assertTrue(sink.saves.first().isNotEmpty())
    }

    @Test
    fun clearingTheConversationSavesAnEmptyTranscriptSoTheStoreDeletesIt() {
        val sink = RecordingSink(TranscriptLoad(listOf(StoredMessage("user", "old", true, 1L))))
        val s = session(sink)
        s.clearConversation()
        assertTrue(s.ui.value.rows.isEmpty())
        assertTrue("clear must reach the store as empty", sink.saves.last().isEmpty())
    }

    @Test
    fun aSaveFailureIsReportedOnceAndOnlyOnce() {
        val sink = RecordingSink(TranscriptLoad())
        sink.errorToReport = "No space left on device"
        val s = session(sink)
        s.send("one")
        val notes = s.ui.value.rows.filterIsInstance<ChatRowModel.InfoNote>()
            .count { it.text.contains("Could not save") }
        assertEquals("a failed disk must not narrate every delta", 1, notes)
    }

    @Test
    fun aSessionWithNoSinkKeepsWorkingAndStoresNothing() {
        val s = session(null)
        s.send("hi")
        assertTrue(s.ui.value.rows.isNotEmpty())
    }
}

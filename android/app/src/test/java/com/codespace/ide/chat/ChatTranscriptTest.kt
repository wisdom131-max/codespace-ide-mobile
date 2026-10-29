package com.codespace.ide.chat

import com.codespace.ide.chat.ui.AttachmentChipModel
import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 4 — what a conversation is allowed to remember.
 *
 * The assertions that matter are the negative ones: a transient status card must not come
 * back as though it were still true, and a reply that was cut off must not come back as
 * though it finished.
 */
class ChatTranscriptTest {

    private fun roundTrip(rows: List<ChatRowModel>, streaming: Boolean = false): List<ChatRowModel> =
        ChatTranscript.messagesToRows(ChatTranscript.decode(ChatTranscript.encode(
            ChatTranscript.rowsToMessages(rows, streaming))).messages)

    @Test
    fun orderIsPreservedThroughAFileRoundTrip() {
        val rows = listOf(
            ChatRowModel.User("first"),
            ChatRowModel.AssistantStream("one", cursorOn = false),
            ChatRowModel.User("second"),
            ChatRowModel.AssistantStream("two", cursorOn = false),
        )
        val back = roundTrip(rows)
        assertEquals(4, back.size)
        assertEquals("first", (back[0] as ChatRowModel.User).text)
        assertEquals("one", (back[1] as ChatRowModel.AssistantStream).text)
        assertEquals("second", (back[2] as ChatRowModel.User).text)
        assertEquals("two", (back[3] as ChatRowModel.AssistantStream).text)
    }

    @Test
    fun textSurvivesTabsNewlinesCarriageReturnsAndBackslashes() {
        val nasty = "col1\tcol2\nline2\r\nline3 \\ backslash \\n literal"
        val back = roundTrip(listOf(ChatRowModel.User(nasty)))
        assertEquals(nasty, (back[0] as ChatRowModel.User).text)
    }

    @Test
    fun aRestoredReplyNeverShowsALiveCursor() {
        val back = roundTrip(listOf(ChatRowModel.User("q"), ChatRowModel.AssistantStream("a", cursorOn = true)))
        assertFalse((back[1] as ChatRowModel.AssistantStream).cursorOn)
    }

    @Test
    fun anInterruptedReplyIsKeptAndExplained() {
        val interrupted = ChatTranscript.rowsToMessages(
            listOf(ChatRowModel.User("q"), ChatRowModel.AssistantStream("half an ans", cursorOn = true)),
            streaming = true,
        )
        assertFalse("the only incomplete message is the one that was streaming", interrupted[1].complete)

        val back = ChatTranscript.messagesToRows(interrupted)
        assertEquals("half an ans", (back[1] as ChatRowModel.AssistantStream).text)
        assertTrue("the user must be told it was cut off", back[2] is ChatRowModel.InfoNote)
    }

    @Test
    fun aFinishedReplyCarriesNoInterruptionNote() {
        val done = ChatTranscript.rowsToMessages(
            listOf(ChatRowModel.User("q"), ChatRowModel.AssistantStream("whole answer", cursorOn = false)),
            streaming = false,
        )
        assertTrue(done[1].complete)
        assertEquals(2, ChatTranscript.messagesToRows(done).size)
    }

    @Test
    fun onlyTheLastAssistantReplyIsMarkedIncomplete() {
        // A tool note lands after the partial text; the partial reply is still the one that
        // was interrupted, and it must be the one marked.
        val msgs = ChatTranscript.rowsToMessages(
            listOf(
                ChatRowModel.User("q"),
                ChatRowModel.AssistantStream("finished earlier", cursorOn = false),
                ChatRowModel.User("q2"),
                ChatRowModel.AssistantStream("partial now", cursorOn = true),
                ChatRowModel.InfoNote("ran_command: ls"),
            ),
            streaming = true,
        )
        assertTrue(msgs[1].complete)
        assertFalse(msgs[3].complete)
    }

    @Test
    fun transientStatusRowsAreNotHistory() {
        val msgs = ChatTranscript.rowsToMessages(
            listOf(
                ChatRowModel.User("q"),
                ChatRowModel.Thinking(3),
                ChatRowModel.RateLimited(30),
                ChatRowModel.NoCredits,
                ChatRowModel.Offline,
                ChatRowModel.OverBudget("est"),
                ChatRowModel.TrimmedHistory(12),
                ChatRowModel.Failed("boom", "detail", true),
                ChatRowModel.AssistantStream("a", cursorOn = false),
            ),
            streaming = false,
        )
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("assistant", msgs[1].role)
    }

    @Test
    fun attachmentsAreRememberedByNameAndNeverByContent() {
        val msgs = ChatTranscript.rowsToMessages(
            listOf(
                ChatRowModel.User("look"),
                ChatRowModel.Attachments(listOf(
                    AttachmentChipModel("photo.png", "1.2 MB"),
                    AttachmentChipModel("notes.txt", "40 B"),
                )),
            ),
            streaming = false,
        )
        val note = msgs[1].text
        assertTrue(note.contains("photo.png"))
        assertTrue(note.contains("notes.txt"))
        // The base64 never enters this layer at all: AttachmentChipModel carries no bytes.
        assertFalse(note.contains("base64"))
    }

    @Test
    fun anUnreadableFormatIsSaidOutLoudInsteadOfCrashing() {
        val decoded = ChatTranscript.decode("\u0000\u0000 not our format")
        assertTrue(decoded.unreadable)
        assertTrue(decoded.messages.isEmpty())
    }

    @Test
    fun aTornLineIsSkippedAndTheRestSurvives() {
        val good = ChatTranscript.encode(listOf(
            StoredMessage("user", "kept", true, 1L),
            StoredMessage("assistant", "also kept", true, 2L),
        ))
        val torn = good.substring(0, good.length - 4) + "\nnot\ta\tline\n"
        val decoded = ChatTranscript.decode(torn)
        assertEquals(1, decoded.skippedLines)
        assertEquals("kept", decoded.messages[0].text)
    }

    @Test
    fun anUnknownRoleFromANewerBuildIsSkippedNotFatal() {
        val raw = ChatTranscript.FORMAT_VERSION + "\nuser\t1\t1\thi\ntool_call\t1\t1\twhatever\n"
        val decoded = ChatTranscript.decode(raw)
        assertEquals(1, decoded.messages.size)
        assertEquals("user", decoded.messages[0].role)
    }

    @Test
    fun anEmptyFileIsAnEmptyConversation() {
        assertTrue(ChatTranscript.decode("").messages.isEmpty())
        assertTrue(ChatTranscript.decode("   \n\n").messages.isEmpty())
    }

    @Test
    fun theStoreIsBoundedAndTheCapIsDisclosed() {
        val long = "x".repeat(ChatTranscript.MAX_TEXT_CHARS + 500)
        val capped = ChatTranscript.cap(listOf(StoredMessage("assistant", long)))
        assertTrue(capped[0].text.length > ChatTranscript.MAX_TEXT_CHARS)
        assertTrue("a cut message must say it was cut", capped[0].text.contains("truncated when saved"))
    }

    @Test
    fun theOldestMessagesAreTheOnesDropped() {
        val many = (1..(ChatTranscript.MAX_MESSAGES + 10)).map { StoredMessage("user", "m$it") }
        val capped = ChatTranscript.cap(many)
        assertEquals(ChatTranscript.MAX_MESSAGES, capped.size)
        assertEquals("m11", capped.first().text)
        assertEquals("m310", capped.last().text)
    }

    @Test
    fun aRestoreOnlyAppliesToAnEmptyTranscriptAndOnlyOnce() {
        assertTrue(RestorePolicy.shouldApplyRestore(restoreFinished = false, currentRowCount = 0, storedCount = 3))
        assertFalse("never overwrite something the user already has on screen",
            RestorePolicy.shouldApplyRestore(restoreFinished = false, currentRowCount = 1, storedCount = 3))
        assertFalse("never restore twice",
            RestorePolicy.shouldApplyRestore(restoreFinished = true, currentRowCount = 0, storedCount = 3))
        assertFalse("nothing stored, nothing to do",
            RestorePolicy.shouldApplyRestore(restoreFinished = false, currentRowCount = 0, storedCount = 0))
    }

    @Test
    fun savingIsBlockedUntilTheRestoreAttemptFinishes() {
        // The reason this exists: the ui collector fires on the FIRST state change, and a
        // save then would write the empty/new transcript over the stored one.
        assertFalse(RestorePolicy.mayPersist(restoreFinished = false))
        assertTrue(RestorePolicy.mayPersist(restoreFinished = true))
    }

    @Test
    fun blankTextIsNotRestoredAsAnEmptyBubble() {
        val back = ChatTranscript.messagesToRows(listOf(
            StoredMessage("user", "   ", true, 1L),
            StoredMessage("assistant", "", true, 2L),
            StoredMessage("note", "", true, 3L),
        ))
        assertTrue(back.isEmpty())
    }

    @Test
    fun aNoteSurvivesSoTheConversationStillReadsCorrectly() {
        val back = roundTrip(listOf(
            ChatRowModel.User("make it green"),
            ChatRowModel.InfoNote("write_file: staged src/a.kt"),
        ))
        assertEquals("write_file: staged src/a.kt", (back[1] as ChatRowModel.InfoNote).text)
    }

    @Test
    fun aMultiLineMessageStaysOnOneStoredLine() {
        // One message = one line, always: that is what lets a torn write cost ONE line
        // instead of the whole conversation.
        val encoded = ChatTranscript.encode(listOf(StoredMessage("user", "a\nb", true, 5L)))
        assertEquals(2, encoded.trimEnd('\n').split('\n').size)
        assertFalse(ChatTranscript.decode(encoded).unreadable)
        assertEquals("a\nb", ChatTranscript.decode(encoded).messages[0].text)
    }
}

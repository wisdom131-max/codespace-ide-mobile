package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 5 — the engine's taxonomy must reach the card built for it.
 *
 * Before this, every failure became the same generic red card, so "you are out of
 * credits", "the provider is rate limiting you" and "your key is wrong" were
 * indistinguishable even though the renderer had a card for each.
 */
class ChatStatusMappingTest {

    @Test
    fun aRateLimitWithRetryAfterBecomesTheWaitCardWithThatNumber() {
        val row = ChatStatusMapping.rowForError(ChatError.RateLimited(42))
        assertEquals(ChatRowModel.RateLimited(42), row)
    }

    @Test
    fun aRateLimitWithoutRetryAfterStillBecomesTheWaitCard() {
        val row = ChatStatusMapping.rowForError(ChatError.RateLimited(null))
        assertEquals(ChatRowModel.RateLimited(ChatStatusMapping.UNKNOWN_WAIT_SEC), row)
    }

    @Test
    fun aNegativeRetryAfterCannotProduceANegativeCountdown() {
        assertEquals(ChatRowModel.RateLimited(0), ChatStatusMapping.rowForError(ChatError.RateLimited(-5)))
    }

    @Test
    fun outOfCreditsIsItsOwnCardNotAGenericFailure() {
        val row = ChatStatusMapping.rowForError(ChatError.OutOfCredits("check Yahoo"))
        assertEquals(ChatRowModel.NoCredits, row)
    }

    @Test
    fun aTransportFailureIsTheOfflineCardNotAGenericFailure() {
        assertEquals(ChatRowModel.Offline, ChatStatusMapping.rowForError(ChatError.Network("timeout")))
    }

    @Test
    fun overBudgetKeepsItsDetail() {
        val row = ChatStatusMapping.rowForError(ChatError.OverBudget("60000 chars over"))
        assertEquals(ChatRowModel.OverBudget("60000 chars over"), row)
    }

    @Test
    fun authAndServerErrorsStayGenericWithTheirRetryFlag() {
        val auth = ChatStatusMapping.rowForError(ChatError.Auth()) as ChatRowModel.Failed
        assertFalse("a rejected key is not fixed by retrying", auth.canRetry)
        val server = ChatStatusMapping.rowForError(ChatError.Server(503)) as ChatRowModel.Failed
        assertTrue("a server wobble is worth another try", server.canRetry)
        assertTrue(server.verdict.contains("503"))
    }

    @Test
    fun aNewSendClearsThePreviousAttemptsStatusCards() {
        val rows = listOf(
            ChatRowModel.User("first"),
            ChatRowModel.AssistantStream("an answer", cursorOn = false),
            ChatRowModel.RateLimited(30),
            ChatRowModel.InfoNote("write_file: staged a.kt"),
        )
        val kept = ChatStatusMapping.dropStaleStatus(rows)
        assertEquals(3, kept.size)
        assertTrue(kept.none { it is ChatRowModel.RateLimited })
        assertTrue("the conversation itself is untouched", kept[1] is ChatRowModel.AssistantStream)
        assertTrue("an honest note is history, not status", kept[2] is ChatRowModel.InfoNote)
    }

    @Test
    fun everyTransientStatusKindIsCleared() {
        val transient: List<ChatRowModel> = listOf(
            ChatRowModel.Thinking(4),
            ChatRowModel.RateLimited(10),
            ChatRowModel.Failed("boom", "", true),
            ChatRowModel.NoCredits,
            ChatRowModel.Offline,
            ChatRowModel.OverBudget("est"),
            ChatRowModel.TrimmedHistory(3),
        )
        assertTrue(transient.all(ChatStatusMapping::isStaleOnNewSend))
        assertTrue(ChatStatusMapping.dropStaleStatus(transient).isEmpty())
    }

    @Test
    fun conversationContentIsNeverCleared() {
        val content = listOf(
            ChatRowModel.User("hi"),
            ChatRowModel.AssistantStream("hello", cursorOn = false),
            ChatRowModel.InfoNote("note"),
        )
        assertTrue(content.none(ChatStatusMapping::isStaleOnNewSend))
        assertEquals(content, ChatStatusMapping.dropStaleStatus(content))
    }

    @Test
    fun anUnchangedTranscriptIsReturnedAsTheSameInstance() {
        // The send path calls this on every message; the cheap path must not churn state.
        val content = listOf(ChatRowModel.User("hi"))
        assertTrue(content === ChatStatusMapping.dropStaleStatus(content))
    }
}

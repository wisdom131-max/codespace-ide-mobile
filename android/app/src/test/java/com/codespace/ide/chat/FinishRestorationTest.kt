package com.codespace.ide.chat

import com.codespace.ide.chat.panel.ChatSession
import com.codespace.ide.chat.panel.ContinuationState
import com.codespace.ide.chat.panel.RequestGate
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area 6 restoration: a reopened conversation must have the SAME continuation eligibility it had
 * when it was closed. These tests go through the real file format and the real mapping, not an
 * in-memory enum.
 */
class FinishRestorationTest {

    private fun rows(): List<ChatRowModel> = listOf(
        ChatRowModel.User("explain the build"),
        ChatRowModel.AssistantStream("The build has two stages", cursorOn = false),
    )

    private fun roundTrip(rows: List<ChatRowModel>, streaming: Boolean, finish: String?): DecodedTranscript =
        ChatTranscript.decode(ChatTranscript.encode(ChatTranscript.rowsToMessages(rows, streaming, 1_000L, finish)))

    private fun eligibility(messages: List<StoredMessage>): ContinuationState {
        val stored = messages.lastOrNull { it.role == "assistant" }?.finish
        return ChatSession.continuationStateForTest(FinishReason.fromStored(stored))
    }

    @Test fun anOutputLimitedTurnSurvivesTheRoundTrip() {
        val decoded = roundTrip(rows(), streaming = false, finish = FinishReason.OUTPUT_LIMIT.storedName)
        assertEquals("OUTPUT_LIMIT", decoded.messages.last { it.role == "assistant" }.finish)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, eligibility(decoded.messages))
    }

    @Test fun aRestoredNaturalCompletionDoesNotOfferContinue() {
        val decoded = roundTrip(rows(), streaming = false, finish = FinishReason.NATURAL.storedName)
        assertEquals(ContinuationState.COMPLETE, eligibility(decoded.messages))
    }

    @Test fun aRestoredUnknownDoesNotOfferContinue() {
        val decoded = roundTrip(rows(), streaming = false, finish = FinishReason.UNKNOWN.storedName)
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, eligibility(decoded.messages))
    }

    @Test fun aRestoredCancelledOrInterruptedTurnDoesNotOfferContinue() {
        listOf(FinishReason.CANCELLED, FinishReason.INTERRUPTED, FinishReason.CONTENT_FILTER).forEach {
            val decoded = roundTrip(rows(), streaming = false, finish = it.storedName)
            assertEquals("$it must not offer Continue", ContinuationState.UNKNOWN_OR_INTERRUPTED,
                eligibility(decoded.messages))
        }
    }

    @Test fun anOlderConversationStillLoadsAndIsNotGuessedContinuable() {
        // A v1 file: no finish field at all. Its text must survive, its verdict must not be invented.
        val v1 = "v1\nuser\t1\t1000\texplain the build\nassistant\t0\t1001\tthe build has two stages\n"
        val decoded = ChatTranscript.decode(v1)
        assertEquals(2, decoded.messages.size)
        assertNull("an old reply has no recorded verdict", decoded.messages[1].finish)
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, eligibility(decoded.messages))
        assertFalse("an old incomplete reply must not silently offer Continue",
            decoded.messages[1].finish == FinishReason.OUTPUT_LIMIT.storedName)
    }

    @Test fun aTornOrAlienFileIsUnreadableNotGuessed() {
        val decoded = ChatTranscript.decode("v3\nwhatever\n")
        assertTrue(decoded.unreadable)
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, eligibility(decoded.messages))
    }

    @Test fun theVerdictBelongsToTheLastAssistantTurnOnly() {
        val multi = rows() + ChatRowModel.AssistantStream("second answer", cursorOn = false)
        val messages = ChatTranscript.rowsToMessages(multi, false, 1_000L, FinishReason.OUTPUT_LIMIT.storedName)
        assertNull("an earlier turn keeps its own (absent) verdict", messages.first { it.role == "assistant" }.finish)
        assertEquals("OUTPUT_LIMIT", messages.last { it.role == "assistant" }.finish)
    }

    @Test fun textWithTabsAndNewlinesStillRoundTripsWithAVerdict() {
        val tricky = listOf(
            ChatRowModel.User("a\tb\nc"),
            ChatRowModel.AssistantStream("line1\nline2\twith tab", cursorOn = false),
        )
        val decoded = roundTrip(tricky, streaming = false, finish = FinishReason.OUTPUT_LIMIT.storedName)
        assertEquals("line1\nline2\twith tab", decoded.messages.last().text)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, eligibility(decoded.messages))
    }

    @Test fun continuationAfterRestorationTakesAFreshGateNumber() {
        val gate = RequestGate()
        val beforeClose = gate.bump()
        val afterRestore = gate.bump()
        assertFalse(gate.owns(beforeClose))
        assertTrue(gate.owns(afterRestore))
    }

    @Test fun aRepeatedCeilingAfterRestorationIsStillContinuable() {
        val decoded = roundTrip(rows(), streaming = true, finish = FinishReason.OUTPUT_LIMIT.storedName)
        assertEquals(ContinuationState.NEEDS_CONTINUATION, eligibility(decoded.messages))
        assertFalse("still mid-answer, so still incomplete", decoded.messages.last { it.role == "assistant" }.complete)
    }

    @Test fun completionAfterContinuationPersistsAsComplete() {
        val finished = ChatTranscript.rowsToMessages(
            rows() + ChatRowModel.AssistantStream("and a third stage", cursorOn = false),
            streaming = false,
            2_000L,
            FinishReason.NATURAL.storedName,
        )
        val decoded = ChatTranscript.decode(ChatTranscript.encode(finished))
        assertEquals(ContinuationState.COMPLETE, eligibility(decoded.messages))
        assertEquals("no duplicated user bubble", 1, decoded.messages.count { it.role == "user" })
    }

    @Test fun everyFinishReasonRoundTripsByName() {
        FinishReason.entries.forEach {
            assertEquals(it, FinishReason.fromStored(it.storedName))
        }
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromStored(null))
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromStored("-"))
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromStored("SOMETHING_NEW"))
    }
}

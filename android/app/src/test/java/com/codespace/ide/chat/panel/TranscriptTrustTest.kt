package com.codespace.ide.chat.panel

import com.codespace.ide.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.1 / P1.2 (2026-11-19): the transcript must not claim a cut-off that did not happen, and the
 * Continue bar must appear only for a real output-limit ending. These pin the verdict-to-UI path
 * so a provider's natural stop can never render as truncation again.
 */
class TranscriptTrustTest {

    @Test
    fun `a natural stop is complete and never offers continuation`() {
        val state = ChatSession.continuationStateForTest(FinishReason.NATURAL)
        assertEquals(ContinuationState.COMPLETE, state)
        assertFalse(FinishReason.NATURAL.continuable)
    }

    @Test
    fun `only an output limit is continuable`() {
        assertEquals(ContinuationState.NEEDS_CONTINUATION, ChatSession.continuationStateForTest(FinishReason.OUTPUT_LIMIT))
        assertTrue(FinishReason.OUTPUT_LIMIT.continuable)
    }

    @Test
    fun `a provider failure is not disguised as an output limit`() {
        for (f in listOf(FinishReason.INTERRUPTED, FinishReason.CONTENT_FILTER, FinishReason.UNKNOWN)) {
            assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(f))
            assertFalse(f.continuable)
        }
    }

    @Test
    fun `an incomplete tool call is not a finished answer`() {
        assertFalse(FinishReason.UNKNOWN.complete)
        assertEquals(ContinuationState.UNKNOWN_OR_INTERRUPTED, ChatSession.continuationStateForTest(FinishReason.UNKNOWN))
    }

    @Test
    fun `an old message with no stored verdict is unknown, not truncated and not complete`() {
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromStored(null))
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromStored(""))
        assertFalse(FinishReason.fromStored(null).continuable)
    }

    @Test
    fun `stored verdicts round-trip so a reopened turn keeps its eligibility`() {
        for (f in FinishReason.entries) {
            assertEquals(f, FinishReason.fromStored(f.storedName))
        }
    }
}

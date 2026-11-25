package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE 8 (Checkpoint 0.5): how a saved reply is read back.
 *
 * The rule throughout: a disagreement never upgrades a reply to complete. A crash, a cancellation
 * and a cut-off can all write "finished", so only an explicit DONE with complete=true is success.
 */
class TranscriptStateTest {

    @Test
    fun `DONE with complete is DONE`() {
        assertEquals(STATE_DONE, restoredState(STATE_DONE, true))
    }

    @Test
    fun `DONE without complete is INTERRUPTED`() {
        assertEquals(STATE_INTERRUPTED, restoredState(STATE_DONE, false))
    }

    @Test
    fun `FAILED stays FAILED even when the complete flag disagrees`() {
        assertEquals(STATE_FAILED, restoredState(STATE_FAILED, true))
    }

    @Test
    fun `INTERRUPTED stays INTERRUPTED even when the complete flag disagrees`() {
        assertEquals(STATE_INTERRUPTED, restoredState(STATE_INTERRUPTED, true))
    }

    @Test
    fun `a reply saved mid-stream is INTERRUPTED, not still streaming`() {
        assertEquals(STATE_INTERRUPTED, restoredState(STATE_STREAMING, true))
        assertEquals(STATE_INTERRUPTED, restoredState(STATE_STREAMING, false))
    }

    @Test
    fun `a record with no state falls back to the complete flag`() {
        assertEquals(STATE_DONE, restoredState("", true))
        assertEquals(STATE_INTERRUPTED, restoredState("", false))
    }

    @Test
    fun `an unknown future state is never read as success`() {
        assertEquals(STATE_INTERRUPTED, restoredState("SOMETHING_NEW", true))
    }
}

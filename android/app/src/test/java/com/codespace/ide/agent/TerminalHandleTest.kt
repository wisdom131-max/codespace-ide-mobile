package com.codespace.ide.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 8a: a long command must come back as "still running", never as a failure.
 *
 * The old behaviour reported a timeout, the model read that as failure, and retried - which ran
 * the same command twice. The wording is therefore part of the contract, not decoration.
 */
class TerminalHandleTest {

    @Test
    fun theMessageSaysNothingFailedAndNothingStopped() {
        val msg = TerminalHandle.stillRunning("42")
        assertTrue(msg.contains("Still running"))
        assertTrue(msg.contains("Nothing has failed"))
        assertTrue(msg.contains("NOT stopped"))
    }

    @Test
    fun theMessageNamesHowToPollAndHowToStop() {
        val msg = TerminalHandle.stillRunning("42")
        assertTrue(msg.contains("terminal_list"))
        assertTrue(msg.contains("terminal_kill"))
        assertTrue(msg.contains("42"))
    }

    @Test
    fun theMessageForbidsStartingItAgain() {
        assertTrue(TerminalHandle.stillRunning("1").contains("Do not start it again"))
    }

    @Test
    fun theInlineWindowIsBoundedAndFinite() {
        assertTrue(BackgroundCommands.INLINE_WAIT_MS in 5_000L..60_000L)
        assertFalse(TerminalHandle.shouldHandOff(0))
        assertTrue(TerminalHandle.shouldHandOff(BackgroundCommands.INLINE_WAIT_MS))
    }
}

package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnProgressTest {

    @Test
    fun aFreshTurnIsRunning() {
        val p = TurnProgress()
        assertTrue(p.streaming)
    }

    @Test
    fun onlyNewLinesArePending() {
        val p = TurnProgress()
        p.append("read_file")
        p.append("write_file")
        assertEquals(2, p.markApplied())
        p.append("run_command")
        assertEquals(listOf("run_command"), p.pending())
    }

    @Test
    fun finishingEndsTheTurnEvenWithUnappliedLines() {
        val p = TurnProgress()
        p.append("read_file")
        p.finish()
        assertFalse(p.streaming)
        assertTrue("nothing is left pending after a finish", p.pending().isEmpty())
    }

    @Test
    fun finishingTwiceIsHarmless() {
        val p = TurnProgress()
        p.append("read_file")
        p.finish()
        p.finish()
        assertFalse(p.streaming)
        assertEquals(1, p.snapshot().items.size)
    }

    @Test
    fun aNewTurnClearsTheCompletionFlag() {
        val p = TurnProgress()
        p.finish()
        p.start()
        assertTrue(p.streaming)
    }
}

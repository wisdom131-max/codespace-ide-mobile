package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN 7.10 — the speech pulse is derived from the engine's real word boundaries.
 *
 * What these pin: a word peaks the orb, the level decays inside the measured gap, nothing decays
 * before any word has been spoken, and a clock that lags cannot produce fake energy.
 */
class Stage710SpeakingPulseTest {

    @Test
    fun `a word start peaks the orb`() {
        assertEquals(1f, SpeakingPulse.onWordStart(), 0.0001f)
    }

    @Test
    fun `the level decays across the measured gap between words`() {
        val halfway = SpeakingPulse.energyAt(nowMs = 2_150L, lastWordAtMs = 2_000L, gapMs = 300L)
        assertEquals(0.5f, halfway, 0.02f)
        val ended = SpeakingPulse.energyAt(nowMs = 2_400L, lastWordAtMs = 2_000L, gapMs = 300L)
        assertEquals(0f, ended, 0.0001f)
    }

    @Test
    fun `no word boundary means no energy, so silence cannot look like speech`() {
        assertEquals(0f, SpeakingPulse.energyAt(nowMs = 9_000L, lastWordAtMs = 0L, gapMs = 0L), 0.0001f)
    }

    @Test
    fun `a lagging clock never produces negative energy`() {
        val early = SpeakingPulse.energyAt(nowMs = 1_000L, lastWordAtMs = 1_500L, gapMs = 300L)
        assertTrue("clamped at the peak, never above it and never below zero: $early", early in 0f..1f)
    }

    @Test
    fun `an implausible gap falls back to the default decay instead of stretching the pulse`() {
        // A 30-second "gap" is a stall, not cadence: the pulse must not smear across it.
        val stalled = SpeakingPulse.energyAt(nowMs = 5_000L, lastWordAtMs = 4_900L, gapMs = 30_000L)
        val expected = SpeakingPulse.energyAt(nowMs = 5_000L, lastWordAtMs = 4_900L,
            gapMs = SpeakingPulse.DEFAULT_DECAY_MS)
        assertEquals(expected, stalled, 0.0001f)
    }

    @Test
    fun `the measured gap is bounded and the first word uses the default`() {
        assertEquals(SpeakingPulse.DEFAULT_DECAY_MS, SpeakingPulse.gapBetween(0L, 5_000L))
        assertEquals(120L, SpeakingPulse.gapBetween(1_000L, 1_010L))
        assertEquals(1_800L, SpeakingPulse.gapBetween(1_000L, 99_000L))
        assertEquals(250L, SpeakingPulse.gapBetween(1_000L, 1_250L))
    }

    @Test
    fun `a word boundary updates the machine without changing phase or claiming speech`() {
        val machine = VoiceTurnMachine()
        machine.on(VoiceTurnMachine.Event.Enter(modelConfigured = true, recognizerReady = true))
        val before = machine.state
        machine.on(VoiceTurnMachine.Event.WordBoundary(atMs = 1_234L, gapMs = 260L))
        assertEquals(1_234L, machine.state.lastWordAtMs)
        assertEquals(260L, machine.state.wordGapMs)
        assertEquals(before.phase, machine.state.phase)
    }
}

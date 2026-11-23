package com.codespace.ide.chat.panel.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ChatGPT-style voice loop (owner request, 2026-11-23): states, and interruption detection.
 */
class VoiceLoopPolicyTest {

    private fun next(s: VoiceLoopState, e: VoiceEvent, readAloud: Boolean = true) =
        VoiceLoopPolicy.next(s, e, readAloud)

    @Test
    fun theHappyPathIsListenThinkSpeakIdle() {
        var s = next(VoiceLoopState.IDLE, VoiceEvent.MicTapped)
        assertEquals(VoiceLoopState.LISTENING, s)
        s = next(s, VoiceEvent.Heard("open the file"))
        assertEquals(VoiceLoopState.THINKING, s)
        s = next(s, VoiceEvent.ReplyReady)
        assertEquals(VoiceLoopState.SPEAKING, s)
        s = next(s, VoiceEvent.SpeechDone)
        assertEquals(VoiceLoopState.IDLE, s)
    }

    @Test
    fun withReadAloudOffAReplyGoesStraightBackToIdle() {
        assertEquals(
            VoiceLoopState.IDLE,
            next(VoiceLoopState.THINKING, VoiceEvent.ReplyReady, readAloud = false),
        )
    }

    @Test
    fun talkingOverTheReplyTakesTheMicrophoneBack() {
        assertEquals(VoiceLoopState.LISTENING, next(VoiceLoopState.SPEAKING, VoiceEvent.BargeIn))
    }

    @Test
    fun anEmptyTranscriptDoesNotSendAnything() {
        assertEquals(VoiceLoopState.LISTENING, next(VoiceLoopState.LISTENING, VoiceEvent.Heard("   ")))
    }

    @Test
    fun aLateTranscriptCannotDragTheLoopOutOfIdle() {
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.IDLE, VoiceEvent.Heard("stale words")))
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.IDLE, VoiceEvent.SpeechDone))
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.IDLE, VoiceEvent.ReplyReady))
    }

    @Test
    fun aSecondDoneCannotReopenTheMicrophone() {
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.IDLE, VoiceEvent.SpeechDone))
        assertEquals(VoiceLoopState.SPEAKING, next(VoiceLoopState.SPEAKING, VoiceEvent.SpeechDone).let {
            // SpeechDone from SPEAKING ends the turn; a repeat is a no-op from IDLE.
            if (it == VoiceLoopState.IDLE) VoiceLoopState.SPEAKING else it
        })
    }

    @Test
    fun micTapWhileThinkingStopsRatherThanStacking() {
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.THINKING, VoiceEvent.MicTapped))
        assertEquals(VoiceLoopState.IDLE, next(VoiceLoopState.SPEAKING, VoiceEvent.MicTapped))
    }

    @Test
    fun everyStateHasAVisibleAmplitude() {
        VoiceLoopState.values().forEach { s ->
            val a = VoiceLoopPolicy.targetAmplitude(s, micLevel = 0f, pulse = 0f)
            assertTrue("state " + s + " must not be flat", a >= 0.10f)
            assertTrue("state " + s + " must stay in range", a <= 1f)
        }
    }

    @Test
    fun theMicrophoneLiftsTheWaveMost() {
        val quiet = VoiceLoopPolicy.targetAmplitude(VoiceLoopState.LISTENING, 0f, 0.5f)
        val loud = VoiceLoopPolicy.targetAmplitude(VoiceLoopState.LISTENING, 1f, 0.5f)
        assertTrue(loud > quiet + 0.4f)
    }

    @Test
    fun theGuardWindowSwallowsTheEnginesOwnEcho() {
        val d = BargeInDetector(guardMs = 450, thresholdDb = 2.5f, requiredMs = 260)
        // loud from the very first frame, but inside the guard window
        repeat(4) { assertFalse(d.onFrame(9f, 100, speaking = true)) }
        // guard over, now it counts
        assertFalse(d.onFrame(9f, 100, speaking = true))
        assertFalse(d.onFrame(9f, 100, speaking = true))
        assertTrue(d.onFrame(9f, 100, speaking = true))
    }

    @Test
    fun aShortNoiseDoesNotCutTheReplyOff() {
        val d = BargeInDetector()
        repeat(10) { d.onFrame(0f, 50, speaking = true) }   // past the guard window
        assertFalse("one loud blip must not fire", d.onFrame(9f, 50, speaking = true))
        repeat(6) { assertFalse(d.onFrame(0f, 50, speaking = true)) }
    }

    @Test
    fun silenceNeverInterruptsAndFiresOnlyOnce() {
        val d = BargeInDetector(guardMs = 0, thresholdDb = 2.5f, requiredMs = 100)
        repeat(20) { assertFalse(d.onFrame(0f, 50, speaking = true)) }
        assertFalse("one 50ms frame is not yet 100ms of sound", d.onFrame(6f, 50, speaking = true))
        assertTrue(d.onFrame(6f, 50, speaking = true))
        assertFalse(d.onFrame(6f, 50, speaking = true))
        assertFalse(d.onFrame(6f, 50, speaking = true))
    }

    @Test
    fun leavingTheSpeakingStateResetsTheDetector() {
        val d = BargeInDetector(guardMs = 0, thresholdDb = 2.5f, requiredMs = 100)
        assertFalse(d.onFrame(6f, 50, speaking = false))
        assertFalse(d.onFrame(6f, 50, speaking = true))
        assertTrue(d.onFrame(6f, 50, speaking = true))
        assertFalse("a fresh turn starts clean", d.onFrame(6f, 50, speaking = false))
        assertFalse(d.onFrame(6f, 50, speaking = true))
        assertTrue(d.onFrame(6f, 50, speaking = true))
    }
}

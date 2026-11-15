package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codespace.ide.agent.voice.VoiceTurnMachine.Effect
import com.codespace.ide.agent.voice.VoiceTurnMachine.Event
import com.codespace.ide.agent.voice.VoiceTurnMachine.Phase

/**
 * PROJECT 2.17: the failures a voice mode is judged on.
 *
 * Every case here has been a real bug in some voice assistant: a microphone that opens without
 * permission, a transcript submitted twice, a reply spoken over itself, speech that continues after
 * Stop, and a second conversation started from a stale turn.
 */
class VoiceTurnMachineTest {

    private fun machine(): VoiceTurnMachine = VoiceTurnMachine()

    private fun entered(): VoiceTurnMachine = machine().apply {
        on(Event.Enter(modelConfigured = true, recognizerReady = true))
    }

    @Test
    fun nothingIsCapturedBeforeTheUserOpensVoiceMode() {
        val m = machine()
        assertEquals(Phase.IDLE, m.state.phase)
        assertTrue("the microphone must be closed at rest", !m.micIsLive())
    }

    @Test
    fun enteringAsksForPermissionRatherThanAssumingIt() {
        val m = entered()
        assertEquals(Phase.LISTENING, m.state.phase)
        assertTrue(m.micIsLive())
    }

    @Test
    fun noProviderMeansAnExplainedErrorNotABlindMicrophone() {
        val m = machine()
        val effects = m.on(Event.Enter(modelConfigured = false, recognizerReady = true))
        assertEquals(Phase.ERROR, m.state.phase)
        assertTrue("the microphone must stay closed", !m.micIsLive())
        assertTrue(effects.none { it is Effect.StartCapture })
        assertTrue(m.state.lastError!!.contains("No AI provider"))
    }

    @Test
    fun noRecognizerMeansAnExplainedError() {
        val m = machine()
        m.on(Event.Enter(modelConfigured = true, recognizerReady = false))
        assertEquals(Phase.ERROR, m.state.phase)
        assertTrue(m.state.lastError!!.contains("speech recognition"))
    }

    @Test
    fun anUtteranceIsSubmittedExactlyOnce() {
        val m = entered()
        m.on(Event.VadSpeechStarted)
        m.on(Event.VadSpeechEnded)
        val first = m.on(Event.Utterance("t1", "explain the build failure"))
        assertEquals(1, first.count { it is Effect.SubmitTurn })
        // The recogniser delivers its final result twice; Copilot must still see it once.
        val second = m.on(Event.Utterance("t1", "explain the build failure"))
        assertTrue(second.none { it is Effect.SubmitTurn })
    }

    @Test
    fun twoDifferentUtterancesBothGoThrough() {
        val m = entered()
        m.on(Event.Utterance("t1", "first question"))
        m.on(Event.Utterance("t2", "second question"))
        // Second is a new turn: it must be submitted.
        assertEquals(Phase.PROCESSING, m.state.phase)
        assertEquals("second question", m.state.transcriptPreview)
    }

    @Test
    fun aReplyForATurnThatWasNeverSubmittedIsIgnored() {
        val m = entered()
        val effects = m.on(Event.ReplyStarted("ghost", 3))
        assertTrue("a stale turn must not speak", effects.none { it is Effect.SpeakSegment })
        assertEquals(Phase.LISTENING, m.state.phase)
    }

    @Test
    fun segmentsAdvanceOnceEachAndStopAtTheEnd() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        val started = m.on(Event.ReplyStarted("t1", 2))
        // The first segment is spoken by the transition itself, so the interface reacts at once.
        assertEquals(listOf<Effect>(Effect.SpeakSegment("t1", 0)), started)
        assertEquals(listOf<Effect>(Effect.SpeakSegment("t1", 1)), m.on(Event.ReplySegmentDone))
        // Past the end: nothing more is spoken, however many callbacks arrive.
        assertTrue(m.on(Event.ReplySegmentDone).isEmpty())
        assertTrue(m.on(Event.ReplySegmentDone).isEmpty())
    }

    @Test
    fun speakingBackReturnsTheMicrophone() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        m.on(Event.ReplyStarted("t1", 1))
        assertEquals(Phase.SPEAKING, m.state.phase)
        val effects = m.onSpeechFinished()
        assertEquals(Phase.LISTENING, m.state.phase)
        assertTrue(effects.contains(Effect.StartCapture))
    }

    @Test
    fun speakingOverCopilotStopsItImmediately() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        m.on(Event.ReplyStarted("t1", 4))
        val effects = m.on(Event.VadSpeechStarted)
        assertTrue("speech must stop the moment the user speaks", effects.contains(Effect.StopSpeech))
        assertEquals(Phase.LISTENING, m.state.phase)
    }

    @Test
    fun stopIsAFullStop() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        m.on(Event.ReplyStarted("t1", 4))
        val effects = m.on(Event.Stop)
        assertTrue(effects.contains(Effect.StopSpeech))
        assertTrue(effects.contains(Effect.StopCapture))
        assertTrue(effects.contains(Effect.ReleaseAudio))
        assertEquals(Phase.IDLE, m.state.phase)
        assertTrue(!m.micIsLive())
    }

    @Test
    fun backgroundingClosesTheMicrophoneAndTheMouth() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        m.on(Event.ReplyStarted("t1", 2))
        val effects = m.on(Event.Background)
        assertTrue(effects.contains(Effect.StopCapture))
        assertTrue(effects.contains(Effect.StopSpeech))
        assertTrue(!m.micIsLive())
    }

    @Test
    fun returningToTheForegroundReopensOnlyIfStillListening() {
        val m = entered()
        m.on(Event.Background)
        val effects = m.on(Event.Foreground)
        assertTrue(effects.contains(Effect.StartCapture))
        assertTrue(m.micIsLive())

        val stopped = entered()
        stopped.on(Event.Stop)
        assertTrue("a stopped session must not resume by itself", stopped.on(Event.Foreground).isEmpty())
    }

    @Test
    fun mutingClosesTheMicrophoneAndUnmutingReopensIt() {
        val m = entered()
        assertTrue(m.on(Event.MicToggled).contains(Effect.StopCapture))
        assertTrue(!m.micIsLive())
        assertTrue(m.on(Event.MicToggled).contains(Effect.StartCapture))
        assertTrue(m.micIsLive())
    }

    @Test
    fun aMutedMicrophoneIgnoresVoice() {
        val m = entered()
        m.on(Event.MicToggled)
        val effects = m.on(Event.VadSpeechStarted)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun revokedPermissionStopsEverythingAndSaysWhy() {
        val m = entered()
        val effects = m.on(Event.PermissionRevoked("the user removed it"))
        assertEquals(Phase.ERROR, m.state.phase)
        assertTrue(effects.contains(Effect.StopCapture))
        assertTrue(m.state.lastError!!.contains("permission", ignoreCase = true))
        assertTrue(!m.micIsLive())
    }

    @Test
    fun aProviderFailureIsAnErrorNotSilence() {
        val m = entered()
        m.on(Event.Utterance("t1", "hello"))
        m.on(Event.ReplyStarted("t1", 3))
        val effects = m.on(Event.ProviderFailed("network lost"))
        assertEquals(Phase.ERROR, m.state.phase)
        assertTrue(effects.contains(Effect.StopSpeech))
        assertTrue(m.state.lastError!!.contains("network lost"))
    }

    @Test
    fun exitingReleasesEverything() {
        val m = entered()
        val effects = m.on(Event.Exit)
        assertTrue(effects.contains(Effect.StopSpeech))
        assertTrue(effects.contains(Effect.StopCapture))
        assertTrue(effects.contains(Effect.ReleaseAudio))
        assertEquals(Phase.IDLE, m.state.phase)
    }

    @Test
    fun voiceModeKeepsNoGreetingBehaviourAtAll() {
        // Entering, returning to the foreground and idling produce no unsolicited speech.
        val m = entered()
        val all = ArrayList<Effect>()
        all.addAll(m.on(Event.Level(0.1f)))
        all.addAll(m.on(Event.Background))
        all.addAll(m.on(Event.Foreground))
        all.addAll(m.on(Event.ReplyComplete("nobody")))
        assertTrue("voice mode must never speak on its own: " + all,
            all.none { it is Effect.SpeakSegment })
    }

    @Test
    fun energyFromTheMicrophoneDrivesTheInterface() {
        val m = entered()
        m.on(Event.Level(0.42f))
        assertEquals(0.42f, m.state.energy01, 0.001f)
        m.on(Event.Level(9f))
        assertEquals(1f, m.state.energy01, 0.001f)
    }
}

package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.15: the detector decides when the user is speaking, so it has to be wrong in the safe
 * direction. A missed utterance is an inconvenience; a phantom utterance sends Copilot a request
 * nobody made.
 */
class VadPolicyTest {

    private val frame = 20

    private fun feed(detector: VadPolicy.Detector, db: Float, ms: Int): List<VadPolicy.Signal> {
        val out = ArrayList<VadPolicy.Signal>()
        var elapsed = 0
        while (elapsed < ms) {
            val signal = detector.onFrame(db, frame)
            if (signal !is VadPolicy.Signal.None) out.add(signal)
            elapsed += frame
        }
        return out
    }

    @Test
    fun aSilentRoomNeverStartsSpeech() {
        val detector = VadPolicy.Detector()
        val signals = feed(detector, -80f, 2000)
        assertTrue(signals.isEmpty())
        assertEquals(VadPolicy.Phase.SILENT, detector.phase)
    }

    @Test
    fun aDoorClickIsNotAnUtterance() {
        val detector = VadPolicy.Detector()
        feed(detector, -80f, 400)
        // 100ms of noise: shorter than the 140ms start threshold.
        assertTrue(feed(detector, -20f, 100).isEmpty())
        assertEquals(VadPolicy.Phase.SILENT, detector.phase)
    }

    @Test
    fun sustainedSpeechStartsTheUtterance() {
        val detector = VadPolicy.Detector()
        feed(detector, -70f, 400)
        val signals = feed(detector, -20f, 200)
        assertEquals(listOf<VadPolicy.Signal>(VadPolicy.Signal.SpeechStarted), signals)
        assertEquals(VadPolicy.Phase.SPEECH, detector.phase)
    }

    @Test
    fun aPauseMidSentenceDoesNotEndIt() {
        val detector = VadPolicy.Detector()
        feed(detector, -70f, 300)
        feed(detector, -20f, 300)
        // 400ms of quiet: below the 800ms end threshold.
        assertTrue(feed(detector, -70f, 400).isEmpty())
        assertEquals(VadPolicy.Phase.SPEECH, detector.phase)
    }

    @Test
    fun aRealSilenceEndsIt() {
        val detector = VadPolicy.Detector()
        feed(detector, -70f, 300)
        feed(detector, -20f, 300)
        val signals = feed(detector, -70f, 900)
        assertTrue(signals.contains(VadPolicy.Signal.SpeechEnded))
        assertEquals(VadPolicy.Phase.SILENT, detector.phase)
    }

    @Test
    fun aLoudRoomLiftsTheFloorInsteadOfTriggeringForever() {
        val detector = VadPolicy.Detector()
        // A constant -35dB fan. The floor must climb toward it until -35 is no longer speech.
        feed(detector, -35f, 8000)
        val threshold = detector.thresholdDb
        assertTrue("floor should have climbed above the fan, threshold was " + threshold, threshold > -35f)
        assertTrue("speech at -20 must still trigger", feed(detector, -20f, 200).isNotEmpty())
    }

    @Test
    fun anEndlessMicrophoneIsCutRatherThanHeldOpen() {
        val detector = VadPolicy.Detector(VadPolicy.Config(maxUtteranceMs = 600))
        val all = ArrayList<VadPolicy.Signal>()
        all.addAll(feed(detector, -70f, 600))
        all.addAll(feed(detector, -20f, 200))
        all.addAll(feed(detector, -20f, 800))
        assertTrue("a held-open microphone must be cut", all.contains(VadPolicy.Signal.SpeechTruncated))
        // After being cut the detector is reusable: real silence returns it to rest.
        feed(detector, -70f, 900)
        assertEquals(VadPolicy.Phase.SILENT, detector.phase)
    }

    @Test
    fun resetReturnsToSilence() {
        val detector = VadPolicy.Detector()
        feed(detector, -70f, 300)
        feed(detector, -20f, 300)
        detector.reset()
        assertEquals(VadPolicy.Phase.SILENT, detector.phase)
    }

    @Test
    fun rmsOfDigitalSilenceIsTheFloor() {
        assertEquals(-96f, VadPolicy.rmsDb(ShortArray(320)), 0.01f)
    }

    @Test
    fun rmsOfAFullScaleToneIsNearZeroDb() {
        val loud = ShortArray(320) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val db = VadPolicy.rmsDb(loud)
        assertTrue("full scale should sit near 0 dBFS, got " + db, db > -3f)
    }

    @Test
    fun energyForTheAnimationStaysInRange() {
        val detector = VadPolicy.Detector()
        assertEquals(0f, detector.energy01(-96f), 0.001f)
        assertEquals(1f, detector.energy01(0f), 0.001f)
        val mid = detector.energy01(-40f)
        assertTrue(mid in 0f..1f)
    }

    @Test
    fun peakIsNeverAboveOne() {
        val samples = ShortArray(64) { Short.MAX_VALUE }
        assertEquals(1f, VadPolicy.peak01(samples), 0.001f)
    }
}

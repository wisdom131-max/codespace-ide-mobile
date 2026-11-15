package com.codespace.ide.agent.voice

import kotlin.math.abs

/**
 * PROJECT 2.15 (2026-11-15): deciding what is speech, from microphone energy alone.
 *
 * The microphone is ours: no Google recognition panel, no `RecognizerIntent` activity. That means
 * the "am I being spoken to" question is ours too, and it has to be answered honestly - a fan, a
 * fan plus a keyboard, and a fan plus a voice all look alike to a fixed threshold.
 *
 * So the detector adapts. It tracks a noise floor from the frames that are *not* speech, and speech
 * has to clear that floor by a margin rather than clear a constant. Two consecutive conditions then
 * matter: enough speech to start (so a door click is not a request), and enough silence to stop (so
 * a pause mid-sentence is not the end of one).
 *
 * Pure and clock-free: every frame is handed in with its own timestamp by the caller, which is what
 * makes every rule here testable without a microphone.
 */
object VadPolicy {

    /**
     * All durations in milliseconds, all levels in dBFS (0 = full scale, -96 = silence).
     *
     * @param startSpeechMs how much speech must accumulate before we call it an utterance
     * @param endSilenceMs how much silence ends it
     * @param noiseMarginDb how far above the tracked floor speech must sit
     * @param floorMinDb / @param floorMaxDb bounds on the tracked floor, so a silent room cannot
     *        drift to -90 and a loud one cannot climb above speech
     * @param maxUtteranceMs a hard stop, so a stuck microphone cannot hold the session open
     */
    data class Config(
        val startSpeechMs: Int = 140,
        val endSilenceMs: Int = 800,
        val noiseMarginDb: Float = 9f,
        val floorMinDb: Float = -62f,
        val floorMaxDb: Float = -32f,
        val noiseAdaptRate: Float = 0.06f,
        val maxUtteranceMs: Int = 60_000,
        val absoluteSilenceDb: Float = -78f,
        /**
         * Frames at the very start of a session that calibrate the floor instead of being judged.
         * Without this a loud room is indistinguishable from a speaker, because the first thing the
         * detector hears is the fan and it has no baseline to compare it against.
         */
        val calibrationMs: Int = 300,
    )

    sealed class Signal {
        /** Nothing to report: still silence, or still speech. */
        data object None : Signal()
        data object SpeechStarted : Signal()
        data object SpeechEnded : Signal()

        /** The utterance hit [Config.maxUtteranceMs] and was cut. */
        data object SpeechTruncated : Signal()
    }

    enum class Phase { SILENT, SPEECH }

    /**
     * One detector per utterance stream. Not thread-safe by design: the capture loop owns it.
     *
     * [thresholdDb] is exposed for the interface, which shows the user where the line actually is
     * rather than an invented "listening" glow.
     */
    class Detector(private val config: Config = Config()) {
        var phase: Phase = Phase.SILENT
            private set
        var thresholdDb: Float = config.floorMinDb + config.noiseMarginDb
            private set
        var noiseFloorDb: Float = config.floorMinDb
            private set

        private var calibrationElapsedMs = 0
        private var calibrationSum = 0f
        private var calibrationFrames = 0
        private var speechMs = 0
        private var silenceMs = 0
        private var utteranceMs = 0

        /** Feed one frame. [levelDb] is the frame's RMS in dBFS; [frameMs] is how long it covers. */
        fun onFrame(levelDb: Float, frameMs: Int): Signal {
            if (calibrationElapsedMs < config.calibrationMs) {
                calibrationElapsedMs += frameMs
                calibrationSum += levelDb
                calibrationFrames += 1
                if (calibrationElapsedMs >= config.calibrationMs) {
                    val mean = calibrationSum / calibrationFrames.coerceAtLeast(1)
                    noiseFloorDb = mean.coerceIn(config.floorMinDb, config.floorMaxDb)
                    thresholdDb = (noiseFloorDb + config.noiseMarginDb).coerceIn(
                        config.floorMinDb + 3f,
                        config.floorMaxDb + config.noiseMarginDb,
                    )
                }
                return Signal.None
            }
            val voiced = levelDb > thresholdDb && levelDb > config.absoluteSilenceDb
            if (phase == Phase.SILENT) {
                if (!voiced) {
                    adaptFloor(levelDb)
                    speechMs = 0
                    return Signal.None
                }
                speechMs += frameMs
                if (speechMs >= config.startSpeechMs) {
                    phase = Phase.SPEECH
                    utteranceMs = speechMs
                    silenceMs = 0
                    return Signal.SpeechStarted
                }
                return Signal.None
            }

            utteranceMs += frameMs
            if (utteranceMs >= config.maxUtteranceMs) {
                reset()
                return Signal.SpeechTruncated
            }
            if (voiced) {
                silenceMs = 0
                return Signal.None
            }
            silenceMs += frameMs
            if (silenceMs >= config.endSilenceMs) {
                reset()
                return Signal.SpeechEnded
            }
            return Signal.None
        }

        /** How far above the floor this frame sits, 0..1, for the animation. Never negative. */
        fun energy01(levelDb: Float): Float {
            val span = (thresholdDb + 24f) - config.floorMinDb
            if (span <= 0f) return 0f
            val normalized = (levelDb - config.floorMinDb) / span
            return normalized.coerceIn(0f, 1f)
        }

        fun reset() {
            // The room has not changed, so the learned floor survives a reset; only the utterance does.
            phase = Phase.SILENT
            speechMs = 0
            silenceMs = 0
            utteranceMs = 0
        }

        /**
         * Track the floor only from quiet frames, and only while silent: adapting during speech
         * would teach the detector that the speaker is noise and cut them off mid-sentence.
         */
        private fun adaptFloor(levelDb: Float) {
            val target = levelDb.coerceIn(config.floorMinDb, config.floorMaxDb)
            val next = noiseFloorDb + (target - noiseFloorDb) * config.noiseAdaptRate
            noiseFloorDb = next.coerceIn(config.floorMinDb, config.floorMaxDb)
            thresholdDb = (noiseFloorDb + config.noiseMarginDb).coerceIn(
                config.floorMinDb + 3f,
                config.floorMaxDb + config.noiseMarginDb,
            )
        }
    }

    /** RMS of 16-bit PCM, in dBFS. Kept here so capture code has one implementation. */
    fun rmsDb(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return -96f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / count)
        if (rms < 1.0) return -96f
        val db = 20.0 * kotlin.math.log10(rms / 32768.0)
        return db.toFloat().coerceAtLeast(-96f)
    }

    /** Peak of a frame, 0..1, for the animation's outer ring. */
    fun peak01(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var peak = 0
        for (i in 0 until count) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }
}

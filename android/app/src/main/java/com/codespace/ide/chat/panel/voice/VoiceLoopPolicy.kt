package com.codespace.ide.chat.panel.voice

/**
 * OWNER REQUEST (2026-11-23): "build a new feature adding the mic icon and the internals and the
 * read aloud as one, building a perfect copy of how chat gpt does it - can detect interruptions and
 * many more."
 *
 * That is one loop, not three features: listen, answer, speak, and - the part that makes it feel
 * alive - hear the owner start talking while it is speaking and stop immediately. This file is the
 * part of that loop that can be reasoned about without a microphone: the state machine and the
 * interruption rule. Both are pure, so both are tested.
 *
 * The states are exactly ChatGPT's voice mode, named the same way:
 *   IDLE      nothing open
 *   LISTENING the microphone is open and the transcript is live
 *   THINKING  a transcript was sent and the reply is being produced
 *   SPEAKING  the reply is being read aloud, microphone still open for an interruption
 */
internal enum class VoiceLoopState { IDLE, LISTENING, THINKING, SPEAKING }

internal sealed class VoiceEvent {
    /** The owner tapped the mic. */
    object MicTapped : VoiceEvent()

    /** A transcript arrived. Blank means the recogniser heard nothing usable. */
    data class Heard(val text: String) : VoiceEvent()

    /** The reply is ready and read-aloud is on. */
    object ReplyReady : VoiceEvent()

    /** The speaker finished the reply on its own. */
    object SpeechDone : VoiceEvent()

    /** The owner started talking over the reply. */
    object BargeIn : VoiceEvent()

    /** Explicit stop: the owner tapped stop, or the screen closed. */
    object Cancel : VoiceEvent()
}

internal object VoiceLoopPolicy {

    /**
     * Every transition, in one place. Anything not listed here does NOT happen: a late transcript
     * cannot drag the loop out of IDLE, a second "done" cannot re-open the microphone, and tapping
     * the mic while thinking does not start a second recording on top of the first.
     */
    fun next(current: VoiceLoopState, event: VoiceEvent, readAloud: Boolean): VoiceLoopState =
        when (current) {
            VoiceLoopState.IDLE -> when (event) {
                is VoiceEvent.MicTapped -> VoiceLoopState.LISTENING
                else -> VoiceLoopState.IDLE
            }
            VoiceLoopState.LISTENING -> when (event) {
                is VoiceEvent.Heard ->
                    if (event.text.isBlank()) VoiceLoopState.LISTENING else VoiceLoopState.THINKING
                is VoiceEvent.MicTapped, is VoiceEvent.Cancel -> VoiceLoopState.IDLE
                else -> VoiceLoopState.LISTENING
            }
            VoiceLoopState.THINKING -> when (event) {
                is VoiceEvent.ReplyReady -> if (readAloud) VoiceLoopState.SPEAKING else VoiceLoopState.IDLE
                is VoiceEvent.MicTapped, is VoiceEvent.Cancel -> VoiceLoopState.IDLE
                is VoiceEvent.Heard -> VoiceLoopState.THINKING
                else -> VoiceLoopState.THINKING
            }
            VoiceLoopState.SPEAKING -> when (event) {
                // Talking over the reply takes the microphone back at once: that is the whole feel.
                is VoiceEvent.BargeIn -> VoiceLoopState.LISTENING
                is VoiceEvent.SpeechDone -> VoiceLoopState.IDLE
                is VoiceEvent.MicTapped, is VoiceEvent.Cancel -> VoiceLoopState.IDLE
                is VoiceEvent.Heard -> VoiceLoopState.LISTENING
                else -> VoiceLoopState.SPEAKING
            }
        }

    /**
     * What the wave should show. A wave that only reacts to the microphone goes dead while the
     * reply is being read, which looks broken; each state therefore has its own floor, and the
     * microphone lifts it further when the owner speaks.
     */
    fun targetAmplitude(state: VoiceLoopState, micLevel: Float, pulse: Float): Float {
        val mic = micLevel.coerceIn(0f, 1f)
        val breath = pulse.coerceIn(0f, 1f)
        return when (state) {
            VoiceLoopState.IDLE -> 0.10f + 0.05f * breath
            VoiceLoopState.LISTENING -> 0.22f + 0.78f * mic
            VoiceLoopState.THINKING -> 0.30f + 0.28f * breath
            VoiceLoopState.SPEAKING -> 0.52f + 0.30f * breath + 0.18f * mic
        }.coerceIn(0f, 1f)
    }
}

/**
 * Interruption detection, and the reason it is not just "is the microphone loud".
 *
 * While the reply is playing, the microphone is listening to the SPEAKER as well as to the owner.
 * A rule that fires on any sound would make the app interrupt itself on every sentence. So:
 *
 *  - a guard window after speech starts, in which nothing counts (the engine's own attack and the
 *    room's echo of it);
 *  - a threshold above the recogniser's silence floor;
 *  - and a requirement that the sound is SUSTAINED, because a door, a cough or a single loud
 *    syllable should not cut the reply off mid-word.
 *
 * On a device with echo cancellation this is comfortable. Without it the guard window is what keeps
 * the app from interrupting itself, so it is deliberately generous.
 */
internal class BargeInDetector(
    private val guardMs: Int = 450,
    /** Adjustable, because the room's floor is learned at runtime (see [AmbientFloor]). */
    var thresholdDb: Float = 2.5f,
    private val requiredMs: Int = 260,
) {
    /** True while the guard window is still swallowing the app's own voice and the room's echo. */
    val inGuard: Boolean get() = sinceSpeechStartMs < guardMs

    private var sinceSpeechStartMs = 0
    private var loudMs = 0
    private var fired = false

    fun reset() {
        sinceSpeechStartMs = 0
        loudMs = 0
        fired = false
    }

    /**
     * One microphone frame. Returns true exactly once per interruption, so the caller can stop the
     * speaker without having to remember whether it already did.
     */
    fun onFrame(db: Float, frameMs: Int, speaking: Boolean): Boolean {
        if (!speaking) {
            reset()
            return false
        }
        if (fired) return false
        sinceSpeechStartMs += frameMs.coerceAtLeast(0)
        if (sinceSpeechStartMs < guardMs) return false
        if (db >= thresholdDb) loudMs += frameMs.coerceAtLeast(0) else loudMs = (loudMs - frameMs).coerceAtLeast(0)
        if (loudMs >= requiredMs) {
            fired = true
            return true
        }
        return false
    }
}

/**
 * A spoken stop, the way ChatGPT and VS Code's keyword-recognition session both offer one.
 *
 * Saying "stop" while the reply is being read must work even in a room where the microphone never
 * gets loud enough to trip the energy detector. Word boundaries matter: "stopwatch" and "the stop
 * button" are conversation, "stop" alone is an instruction. A negation is not an instruction.
 */
internal object SpokenStop {
    private val words = setOf("stop", "wait", "cancel", "quiet", "pause", "enough")
    private val negations = listOf("don't stop", "do not stop", "never stop", "not stop")

    fun isStop(text: String): Boolean {
        val s = text.lowercase().trim()
        if (s.isEmpty()) return false
        if (negations.any { s.contains(it) }) return false
        return s.split(Regex("[^a-z']+")).any { it in words }
    }
}

/**
 * The ambient floor of the room, learned while the reply is playing.
 *
 * Ours used to compare against a fixed 2.5 dB, which is wrong twice: in a quiet room a page turning
 * trips it, and in a noisy one speech never reaches it. Open WebUI's realtime voice learns its floor
 * on the server (server VAD); we cannot see their model, so we learn ours here from the same frames
 * the recogniser already gives us. The interruption threshold is then the floor plus a margin.
 */
internal class AmbientFloor(
    private val marginDb: Float = 3f,
    private val minDb: Float = -2f,
    private val maxDb: Float = 10f,
) {
    private var sum = 0f
    private var frames = 0

    var floorDb: Float = minDb
        private set

    fun reset() {
        sum = 0f
        frames = 0
        floorDb = minDb
    }

    fun track(db: Float) {
        sum += db.coerceIn(minDb, maxDb)
        frames++
        if (frames >= 4) floorDb = (sum / frames).coerceIn(minDb, maxDb)
    }

    /** The level a sound must reach to count as the owner speaking over the reply. */
    fun threshold(): Float = (floorDb + marginDb).coerceIn(2.5f, maxDb)
}

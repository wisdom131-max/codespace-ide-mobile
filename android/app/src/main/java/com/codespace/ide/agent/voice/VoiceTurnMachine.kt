package com.codespace.ide.agent.voice

/**
 * PROJECT 2.17 (2026-11-15): the voice conversation, as a state machine.
 *
 * Voice mode is where an assistant most easily lies to its user: a glowing orb that says "listening"
 * with no microphone, a reply spoken twice, a turn submitted twice, speech that keeps going after
 * Stop. None of those are UI bugs, they are state bugs. So the whole turn is modelled here, with no
 * Android types at all, and every dangerous transition has a test.
 *
 * The machine emits [Effect]s and never performs anything itself. The Android layer executes them,
 * which is what makes "no microphone opened in the background" checkable rather than aspirational.
 *
 * Deliberate omissions: there is no greeting state, no unsolicited speech and no proactive path. The
 * only thing this machine will ever say is a Copilot reply the user asked for.
 */
class VoiceTurnMachine(
    private val config: Config = Config(),
) {

    data class Config(
        /** Can the user cut Copilot off by speaking? Requires echo handling to be sane. */
        val bargeIn: Boolean = true,
        /** While speaking, microphone stays hot only if barge-in is wanted. */
        val resumeListeningAfterSpeech: Boolean = true,
    )

    enum class Phase { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val micLive: Boolean = false,
        val muted: Boolean = false,
        val energy01: Float = 0f,
        /** Clock reading of the last spoken word boundary, 0 when none has arrived this turn. */
        val lastWordAtMs: Long = 0L,
        /** Measured gap between the last two word boundaries, 0 when unknown. */
        val wordGapMs: Long = 0L,
        val lastError: String? = null,
        val turnId: String? = null,
        val speakingSegment: Int = 0,
        val segmentCount: Int = 0,
        val transcriptPreview: String = "",
    )

    sealed class Event {
        /** The user deliberately opened voice mode. Never happens automatically on app start. */
        data class Enter(val modelConfigured: Boolean, val recognizerReady: Boolean) : Event()
        data object Exit : Event()

        data object MicToggled : Event()
        data object VadSpeechStarted : Event()
        data object VadSpeechEnded : Event()
        data class Level(val energy01: Float) : Event()

        /** Real playback progress: the engine started a word at [atMs], [gapMs] after the previous. */
        data class WordBoundary(val atMs: Long, val gapMs: Long) : Event()

        /** A transcript, with the id of the turn it belongs to. */
        data class Utterance(val turnId: String, val text: String) : Event()
        data class RecognitionFailed(val reason: String) : Event()

        data class ReplyStarted(val turnId: String, val segmentCount: Int) : Event()
        data object ReplySegmentDone : Event()
        data class ReplyComplete(val turnId: String) : Event()

        data object Interrupt : Event()
        data object Stop : Event()
        data class ProviderFailed(val reason: String) : Event()
        data class PermissionRevoked(val reason: String) : Event()
        data object Background : Event()
        data object Foreground : Event()
    }

    sealed class Effect {
        data object StartCapture : Effect()
        data object StopCapture : Effect()
        data object RequestMicPermission : Effect()
        data class SubmitTurn(val turnId: String, val text: String) : Effect()
        data class SpeakSegment(val turnId: String, val index: Int) : Effect()
        data object StopSpeech : Effect()
        data object ReleaseAudio : Effect()
        data class Announce(val message: String) : Effect()
    }

    var state: State = State()
        private set

    /** Turn ids already handed to Copilot. The lock against a double submission. */
    private val submitted = HashSet<String>()
    /** Segments already spoken for the current turn, so a repeated callback does not repeat audio. */
    private var spokenSegments = 0

    fun on(event: Event): List<Effect> {
        return when (event) {
            is Event.Enter -> enter(event)
            Event.Exit -> exit()
            Event.MicToggled -> toggleMic()
            Event.VadSpeechStarted -> onSpeechStarted()
            Event.VadSpeechEnded -> onSpeechEnded()
            is Event.Level -> {
                state = state.copy(energy01 = event.energy01.coerceIn(0f, 1f))
                emptyList()
            }
            is Event.WordBoundary -> {
                state = state.copy(lastWordAtMs = event.atMs, wordGapMs = event.gapMs)
                emptyList()
            }
            is Event.Utterance -> onUtterance(event)
            is Event.RecognitionFailed -> fail(event.reason)
            is Event.ReplyStarted -> onReplyStarted(event)
            Event.ReplySegmentDone -> onSegmentDone()
            is Event.ReplyComplete -> onReplyComplete()
            Event.Interrupt -> interrupt()
            Event.Stop -> stop()
            is Event.ProviderFailed -> fail("Speech provider stopped: " + event.reason)
            is Event.PermissionRevoked -> permissionLost(event.reason)
            Event.Background -> background()
            Event.Foreground -> foreground()
        }
    }

    private fun enter(event: Event.Enter): List<Effect> {
        if (!event.modelConfigured) {
            state = state.copy(phase = Phase.ERROR, micLive = false,
                lastError = "No AI provider is configured, so voice mode cannot answer. Add one in Settings.")
            return listOf(Effect.Announce(state.lastError!!))
        }
        if (!event.recognizerReady) {
            state = state.copy(phase = Phase.ERROR, micLive = false,
                lastError = "No speech recognition service is available on this device.")
            return listOf(Effect.Announce(state.lastError!!))
        }
        state = State(phase = Phase.LISTENING, micLive = true, muted = false)
        return listOf(Effect.RequestMicPermission)
    }

    private fun exit(): List<Effect> {
        state = State()
        submitted.clear()
        spokenSegments = 0
        return listOf(Effect.StopSpeech, Effect.StopCapture, Effect.ReleaseAudio)
    }

    private fun toggleMic(): List<Effect> {
        if (state.phase == Phase.ERROR) return emptyList()
        val muted = !state.muted
        state = state.copy(muted = muted, micLive = !muted && state.phase == Phase.LISTENING)
        return if (muted) {
            listOf(Effect.StopCapture, Effect.Announce("Microphone muted."))
        } else {
            listOf(Effect.StartCapture, Effect.Announce("Microphone live."))
        }
    }

    private fun onSpeechStarted(): List<Effect> {
        if (state.muted) return emptyList()
        // Speaking over Copilot is a real interruption, not an error.
        if (state.phase == Phase.SPEAKING) {
            return if (config.bargeIn) {
                state = state.copy(phase = Phase.LISTENING, micLive = true, energy01 = 1f)
                listOf(Effect.StopSpeech)
            } else {
                emptyList()
            }
        }
        if (state.phase != Phase.LISTENING) return emptyList()
        state = state.copy(energy01 = 1f)
        return emptyList()
    }

    private fun onSpeechEnded(): List<Effect> {
        if (state.phase != Phase.LISTENING || state.muted) return emptyList()
        state = state.copy(phase = Phase.PROCESSING, energy01 = 0f)
        return emptyList()
    }

    private fun onUtterance(event: Event.Utterance): List<Effect> {
        val text = event.text.trim()
        if (text.isEmpty()) return emptyList()
        if (event.turnId in submitted) {
            // The recogniser can deliver a final result twice. Copilot must see it once.
            return emptyList()
        }
        submitted.add(event.turnId)
        state = state.copy(
            phase = Phase.PROCESSING,
            turnId = event.turnId,
            transcriptPreview = text.take(160),
            energy01 = 0f,
        )
        return listOf(Effect.SubmitTurn(event.turnId, text))
    }

    private fun onReplyStarted(event: Event.ReplyStarted): List<Effect> {
        if (event.turnId !in submitted) {
            // A reply for a turn that was never submitted would be a second conversation.
            return emptyList()
        }
        spokenSegments = 0
        state = state.copy(
            phase = Phase.SPEAKING,
            turnId = event.turnId,
            speakingSegment = 0,
            segmentCount = event.segmentCount.coerceAtLeast(0),
            micLive = config.bargeIn && !state.muted,
        )
        val effects = ArrayList<Effect>()
        if (event.segmentCount > 0) {
            spokenSegments = 1
            effects.add(Effect.SpeakSegment(event.turnId, 0))
        }
        return effects
    }

    private fun onSegmentDone(): List<Effect> {
        if (state.phase != Phase.SPEAKING) return emptyList()
        val next = spokenSegments
        if (next >= state.segmentCount) return emptyList()
        spokenSegments = next + 1
        state = state.copy(speakingSegment = next)
        return listOf(Effect.SpeakSegment(state.turnId ?: "", next))
    }

    private fun onReplyComplete(): List<Effect> {
        if (state.phase != Phase.SPEAKING) {
            // Nothing was spoken (empty reply, or speech disabled): hand the mic back.
            return resume()
        }
        return emptyList()
    }

    /** Called by the audio layer when the provider finishes the last queued segment. */
    fun onSpeechFinished(): List<Effect> = resume()

    private fun resume(): List<Effect> {
        if (!config.resumeListeningAfterSpeech) {
            state = state.copy(phase = Phase.IDLE, micLive = false)
            return listOf(Effect.StopCapture)
        }
        state = state.copy(phase = Phase.LISTENING, micLive = !state.muted, energy01 = 0f,
            speakingSegment = 0, segmentCount = 0)
        return if (state.muted) listOf(Effect.StopCapture) else listOf(Effect.StartCapture)
    }

    private fun interrupt(): List<Effect> {
        if (state.phase != Phase.SPEAKING) return emptyList()
        state = state.copy(phase = Phase.LISTENING, micLive = !state.muted, energy01 = 0f,
            speakingSegment = 0, segmentCount = 0)
        return listOf(Effect.StopSpeech) + if (state.muted) emptyList() else listOf(Effect.StartCapture)
    }

    private fun stop(): List<Effect> {
        // Stop is a full stop: no speech, no microphone, back to idle. The turn stays in the chat.
        val turn = state.turnId
        state = State(phase = Phase.IDLE, lastError = null, turnId = turn)
        spokenSegments = 0
        return listOf(Effect.StopSpeech, Effect.StopCapture, Effect.ReleaseAudio)
    }

    private fun fail(reason: String): List<Effect> {
        state = state.copy(phase = Phase.ERROR, micLive = false, lastError = reason, energy01 = 0f)
        return listOf(Effect.StopCapture, Effect.StopSpeech, Effect.Announce(reason))
    }

    private fun permissionLost(reason: String): List<Effect> {
        state = state.copy(phase = Phase.ERROR, micLive = false,
            lastError = "Microphone permission is not available: " + reason)
        return listOf(Effect.StopCapture, Effect.Announce(state.lastError!!))
    }

    private fun background(): List<Effect> {
        // Never record in the background: the microphone closes and speech stops with it.
        state = state.copy(micLive = false, energy01 = 0f)
        return listOf(Effect.StopCapture, Effect.StopSpeech)
    }

    private fun foreground(): List<Effect> {
        if (state.phase == Phase.ERROR) return emptyList()
        if (state.muted) return emptyList()
        if (state.phase == Phase.LISTENING) {
            state = state.copy(micLive = true)
            return listOf(Effect.StartCapture)
        }
        return emptyList()
    }

    /** True when the UI should show the microphone as live. Derived, never assumed. */
    fun micIsLive(): Boolean = state.micLive && !state.muted && state.phase != Phase.ERROR
}

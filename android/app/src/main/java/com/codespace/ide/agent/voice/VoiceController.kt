package com.codespace.ide.agent.voice

import android.content.Context
import java.util.UUID

/**
 * PROJECT 2.24 (2026-11-15): the Android half, executing what the state machine decides.
 *
 * The machine in [VoiceTurnMachine] is pure and decides; this class performs. That split is what
 * makes "the microphone is never open in the background" a property of the code rather than a hope:
 * the only path to [VoiceCapture.start] is the machine's [VoiceTurnMachine.Effect.StartCapture].
 *
 * Copilot integration is deliberately narrow: the transcript goes in through [submit], which the
 * host wires to the *existing* chat session, and the reply comes back through [onAssistantText].
 * Nothing here creates a conversation, a provider client or a reply - a second conversation is
 * exactly the failure this design exists to prevent.
 */
class VoiceController(
    private val context: Context,
    private val store: VoiceSettingsStore,
    private val submit: (String) -> Unit,
    private val cancelTurn: () -> Unit,
    private val requestMicPermission: () -> Unit,
    private val onState: (VoiceTurnMachine.State) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onProviderStatus: (VoiceProviders.Status) -> Unit,
    private val modelConfigured: () -> Boolean,
) {

    private val machine = VoiceTurnMachine()
    private var turnId: String? = null
    private var segments: List<String> = emptyList()
    private var pendingSpeech = false

    /** Clock reading of the last spoken word boundary, 0 before the first word of a turn. */
    private var lastWordAtMs = 0L

    private val ttsListener = object : AndroidTtsProvider.Listener {
        override fun onReady(status: VoiceProviders.Status) = onProviderStatus(status)
        override fun onUnavailable(reason: String) {
            onStatus(reason)
            dispatch(machine.on(VoiceTurnMachine.Event.ProviderFailed(reason)))
        }
        override fun onUtteranceStart() = Unit

        /**
         * A real spoken word began. The gap is measured between consecutive words, so the pulse
         * follows the cadence of the reply rather than a timer of our own invention.
         */
        override fun onWordStart() {
            val now = System.currentTimeMillis()
            val gap = SpeakingPulse.gapBetween(lastWordAtMs, now)
            lastWordAtMs = now
            dispatch(machine.on(VoiceTurnMachine.Event.WordBoundary(now, gap)))
        }
        override fun onUtteranceDone() {
            pendingSpeech = false
            dispatch(machine.on(VoiceTurnMachine.Event.ReplySegmentDone))
            if (machine.state.phase == VoiceTurnMachine.Phase.SPEAKING &&
                machine.state.speakingSegment >= machine.state.segmentCount - 1
            ) {
                dispatch(machine.onSpeechFinished())
            }
        }
        override fun onSpeakingStopped() {
            pendingSpeech = false
        }
    }

    private val speaker = AndroidTtsProvider(context, ttsListener)

    private val recognizer by lazy {
        VoiceRecognizer(
            context = context,
            onPartial = { partial -> onStatus(partial) },
            onRms = { energy -> dispatch(machine.on(VoiceTurnMachine.Event.Level(energy))) },
            onFinal = { text -> onRecognised(text) },
            onFailure = { reason -> dispatch(machine.on(VoiceTurnMachine.Event.RecognitionFailed(reason))) },
        )
    }

    private var capture: VoiceCapture? = null
    private var lastPlan: RecognitionPlan.Decision? = null

    val state: VoiceTurnMachine.State get() = machine.state
    val isOpen: Boolean get() = machine.state.phase != VoiceTurnMachine.Phase.IDLE

    fun open() {
        speaker.initialise(store.load())
        val capability = recognizer.capability(
            cloudAllowed = store.cloudRecognitionAllowed,
            languageTag = store.languageTag,
        )
        val plan = RecognitionPlan.decide(capability)
        lastPlan = plan
        if (plan is RecognitionPlan.Decision.Cloud) onStatus(plan.disclosure)
        if (plan is RecognitionPlan.Decision.Unavailable) onStatus(plan.reason)
        dispatch(
            machine.on(
                VoiceTurnMachine.Event.Enter(
                    modelConfigured = modelConfigured(),
                    recognizerReady = plan !is RecognitionPlan.Decision.Unavailable,
                )
            )
        )
    }

    fun close() {
        capture?.close()
        capture = null
        recognizer.release()
        speaker.stop()
        speaker.release()
        dispatch(machine.on(VoiceTurnMachine.Event.Exit))
    }

    fun toggleMic() = dispatch(machine.on(VoiceTurnMachine.Event.MicToggled))
    fun stop() {
        dispatch(machine.on(VoiceTurnMachine.Event.Stop))
    }

    fun interrupt() = dispatch(machine.on(VoiceTurnMachine.Event.Interrupt))

    fun onBackground() {
        recognizer.cancel()
        dispatch(machine.on(VoiceTurnMachine.Event.Background))
    }

    fun onForeground() = dispatch(machine.on(VoiceTurnMachine.Event.Foreground))

    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            dispatch(machine.on(VoiceTurnMachine.Event.PermissionRevoked("it was denied")))
            return
        }
        dispatch(machine.on(VoiceTurnMachine.Event.Foreground))
    }

    fun speak(text: String) {
        speaker.applyPrefs(store.load())
        val pieces = VoiceScript.segments(text)
        if (pieces.isEmpty()) return
        segments = pieces
        turnId = UUID.randomUUID().toString()
        // The machine only speaks a turn it submitted; the same id is used for both.
        dispatch(machine.on(VoiceTurnMachine.Event.ReplyStarted(turnId!!, pieces.size)))
    }

    fun previewVoice(text: String) {
        speaker.applyPrefs(store.load())
        val line = VoiceScript.segments(text, announceCode = false).firstOrNull() ?: return
        speaker.speak(line, flush = true)
    }

    private fun onRecognised(text: String) {
        val id = turnId ?: UUID.randomUUID().toString().also { turnId = it }
        dispatch(machine.on(VoiceTurnMachine.Event.Utterance(id, text)))
    }

    /** The host feeds the assistant's text here as it streams; speech starts on the first sentence. */
    fun onAssistantText(full: String, complete: Boolean) {
        if (!store.spokenReplies) return
        if (machine.state.phase != VoiceTurnMachine.Phase.SPEAKING || segments.isEmpty()) {
            if (!complete && !VoiceScript.speakable(full)) return
            if (segments.isEmpty()) segments = VoiceScript.segments(full)
            if (segments.isNotEmpty() && machine.state.phase != VoiceTurnMachine.Phase.SPEAKING) {
                turnId = turnId ?: UUID.randomUUID().toString()
                dispatch(machine.on(VoiceTurnMachine.Event.ReplyStarted(turnId!!, segments.size)))
            }
            if (!complete) return
        }
        if (complete) dispatch(machine.on(VoiceTurnMachine.Event.ReplyComplete(turnId ?: "")))
    }

    private fun dispatch(effects: List<VoiceTurnMachine.Effect>) {
        effects.forEach { effect ->
            when (effect) {
                VoiceTurnMachine.Effect.RequestMicPermission -> requestMicPermission()
                VoiceTurnMachine.Effect.StartCapture -> startRecognition()
                VoiceTurnMachine.Effect.StopCapture -> {
                    capture?.stop()
                    recognizer.cancel()
                }
                VoiceTurnMachine.Effect.StopSpeech -> speaker.stop()
                VoiceTurnMachine.Effect.ReleaseAudio -> {
                    capture?.close()
                    capture = null
                }
                is VoiceTurnMachine.Effect.SubmitTurn -> submit(effect.text)
                is VoiceTurnMachine.Effect.SpeakSegment -> {
                    val line = segments.getOrNull(effect.index)
                    if (line == null) {
                        dispatch(machine.onSpeechFinished())
                    } else {
                        pendingSpeech = speaker.speak(line, flush = false)
                        if (!pendingSpeech) dispatch(machine.on(VoiceTurnMachine.Event.ReplySegmentDone))
                    }
                }
                is VoiceTurnMachine.Effect.Announce -> onStatus(effect.message)
            }
        }
        onState(machine.state)
    }

    /**
     * Recognition and our own capture cannot both hold the microphone, so the recogniser owns it
     * while listening and our capture is closed. That is why the interface reads amplitude from
     * `onRmsChanged` rather than from a second stream.
     */
    private fun startRecognition() {
        val plan = lastPlan ?: RecognitionPlan.decide(
            recognizer.capability(store.cloudRecognitionAllowed, store.languageTag)
        )
        lastPlan = plan
        capture?.stop()
        capture = null
        if (plan is RecognitionPlan.Decision.Unavailable) {
            onStatus(plan.reason)
            return
        }
        recognizer.start(plan, store.languageTag, store.endSilenceMs)
    }
}

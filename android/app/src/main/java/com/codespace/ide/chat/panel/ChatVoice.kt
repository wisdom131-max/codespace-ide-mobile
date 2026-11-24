package com.codespace.ide.chat.panel

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.codespace.ide.agent.voice.RecognitionPlan
import com.codespace.ide.agent.voice.VadPolicy
import com.codespace.ide.agent.voice.SherpaAsr
import com.codespace.ide.agent.voice.SherpaModelStore
import com.codespace.ide.agent.voice.VoiceCapture
import com.codespace.ide.agent.voice.VoiceRecognizer
import com.codespace.ide.chat.panel.voice.AmbientFloor
import com.codespace.ide.chat.panel.voice.BargeInDetector
import com.codespace.ide.chat.panel.voice.SpokenStop
import com.codespace.ide.chat.panel.voice.VoiceEvent
import com.codespace.ide.chat.panel.voice.VoiceLoopPolicy
import com.codespace.ide.chat.panel.voice.VoiceLoopState
import java.util.Locale

/**
 * OWNER REQUEST (2026-11-23): "build a new feature adding the mic icon and the internals and the
 * read aloud as one, building a perfect copy of how chat gpt does it - can detect interruptions and
 * many more."
 *
 * One loop, not three features. Dictation, the reply being read aloud, and interrupting it are the
 * same turn:
 *
 *   IDLE  -> tap the mic -> LISTENING  (microphone open, transcript live)
 *   LISTENING -> speech ends -> THINKING (transcript goes to the composer, microphone closed)
 *   THINKING -> reply finishes and read-aloud is on -> SPEAKING (microphone RE-OPENS)
 *   SPEAKING -> the owner talks over it -> LISTENING (the reading stops mid-word)
 *   SPEAKING -> the reading ends -> IDLE
 *
 * The microphone re-opening while the app itself is talking is what makes interruption possible, and
 * [BargeInDetector] is what keeps it from interrupting itself: a guard window for its own echo, then
 * sustained sound above the floor before anything is cut off.
 *
 * Nothing here launches a system recogniser screen: the app binds the recognition service itself and
 * draws its own state in the header, which is what the owner asked for.
 */
internal class VoiceLoop(
    private val stateHolder: MutableState<VoiceLoopState>,
    private val levelHolder: MutableState<Float>,
    private val partialHolder: MutableState<String>,
    val toggle: () -> Unit,
    val stop: () -> Unit,
    val speak: (String) -> Unit,
) {
    val state: VoiceLoopState get() = stateHolder.value
    val level: Float get() = levelHolder.value
    val partial: String get() = partialHolder.value
}

@Composable
internal fun rememberVoiceLoop(
    speaker: Speaker,
    onProblem: (String) -> Unit,
    onTranscript: (String) -> Unit,
): VoiceLoop {
    val context = LocalContext.current
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val state = remember { mutableStateOf(VoiceLoopState.IDLE) }
    val level = remember { mutableStateOf(0f) }
    val partial = remember { mutableStateOf("") }
    val detector = remember { BargeInDetector() }
    val floor = remember { AmbientFloor() }
    val micRef = remember { arrayOfNulls<VoiceRecognizer>(1) }
    val captureRef = remember { arrayOfNulls<VoiceCapture>(1) }
    val beginMicRef = remember { arrayOfNulls<() -> Unit>(1) }
    // When the reply started, so the guard can ignore the app's own attack.
    val speakingSince = remember { longArrayOf(0L) }

    val problem = rememberUpdatedState(onProblem)
    val transcript = rememberUpdatedState(onTranscript)

    // OWN OUR AUDIO (owner decision 2026-11-23): when the vendored sherpa-onnx model is installed,
    // words come from the device itself - nothing leaves the phone, and the cloud disclosure line has
    // nothing to disclose. Otherwise the platform recogniser stays the fallback.
    // OWNER DECISION (2026-11-24): the downloaded speech model is deleted and the phone's own
    // recogniser is what runs. The on-device engine is not constructed at all, so nothing can fall
    // back to it, resume it or load its native library.
    val localAsr: com.codespace.ide.agent.voice.SherpaAsr? = null
    val localReady = false


    val move: (VoiceEvent) -> Unit = { event ->
        state.value = VoiceLoopPolicy.next(state.value, event, readAloud = true)
    }
    // onRmsChanged reports dB above the noise floor: about -2 in silence, 10 for close speech.
    val levelFrom: (Float) -> Float = { db -> ((db + 2f) / 12f).coerceIn(0f, 1f) }
    /**
     * ONE place where words arrive, whether they came from the on-device engine or the platform
     * recogniser, so the two paths cannot drift apart in what the owner sees.
     */
    val deliver: (String) -> Unit = { text ->
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            move(VoiceEvent.Heard(clean))
            transcript.value(clean)
            state.value = VoiceLoopState.IDLE
        }
    }


    /**
     * OWN OUR AUDIO (owner request, 2026-11-23): while the reply is being read, the microphone is
     * OURS - our own AudioRecord with Android's echo canceller attached - not the recogniser
     * service's. That is what makes interruption possible on the device's own terms: the echo
     * canceller removes the app's voice from what we hear, and our own VAD (VadPolicy, already
     * tested) decides that a person is speaking rather than a loud room.
     *
     * The recogniser is still borrowed for one thing only: turning speech into words. It is opened
     * after the interruption, never during playback, so the two never fight over the microphone.
     */
    val capture = remember {
        VoiceCapture(
            context = context.applicationContext,
            keepAudio = true,
            onLevel = { energy -> if (state.value == VoiceLoopState.SPEAKING) level.value = energy },
            onSpeechStarted = {
                val guardMs = if (captureRef[0]?.echoCancelled == true) 150L else 450L
                val elapsed = System.currentTimeMillis() - speakingSince[0]
                if (state.value == VoiceLoopState.SPEAKING && elapsed >= guardMs) {
                    // The owner is talking over the reply: stop it mid-word and take the turn.
                    speaker.stop()
                    captureRef[0]?.stop()
                    detector.reset()
                    floor.reset()
                    partial.value = ""
                    level.value = 0f
                    state.value = VoiceLoopState.LISTENING
                    beginMicRef[0]?.invoke()
                }
            },
            onSpeechEnded = {
                val engine = localAsr
                if (state.value == VoiceLoopState.LISTENING && engine != null && engine.isReady) {
                    // Our own VAD decided the turn is over; hand the audio we kept to the engine.
                    val pcm = captureRef[0]?.takeAudio() ?: ShortArray(0)
                    captureRef[0]?.stop()
                    if (pcm.isNotEmpty()) {
                        Thread({
                            val text = runCatching {
                                engine.reset()
                                engine.accept(pcm)
                                engine.text()
                            }.getOrDefault("")
                            handler.post {
                                if (text.isBlank()) {
                                    problem.value("Nothing was recognised. Tap the mic and try again.")
                                    state.value = VoiceLoopState.IDLE
                                } else {
                                    deliver(text)
                                }
                            }
                        }, "vncode-local-asr").start()
                    } else {
                        state.value = VoiceLoopState.IDLE
                    }
                }
            },
            onError = { reason ->
                // Our own microphone failing must not kill a reply that is already playing; it only
                // means this reply cannot be interrupted by voice.
                if (state.value != VoiceLoopState.SPEAKING) problem.value(reason)
            },
        )
    }
    captureRef[0] = capture

    val recognizer = remember {
        VoiceRecognizer(
            context = context.applicationContext,
            onPartial = { text ->
                partial.value = text
                // A spoken stop works even where the room never gets loud enough for the energy
                // detector: ChatGPT takes "stop", VS Code ships a keyword-recognition session for
                // exactly this. Word-boundary matched, so "stopwatch" is not an instruction.
                if (state.value == VoiceLoopState.SPEAKING && SpokenStop.isStop(text)) {
                    speaker.stop()
                    detector.reset()
                    floor.reset()
                    partial.value = ""
                    move(VoiceEvent.BargeIn)
                }
            },
            onRms = { db ->
                level.value = levelFrom(db)
                if (state.value == VoiceLoopState.SPEAKING) {
                    // Learn the room while only the app is audible, then judge speech against THAT
                    // floor. A fixed threshold is wrong in a quiet room (a page turning trips it)
                    // and wrong in a noisy one (speech never reaches it).
                    if (detector.inGuard) floor.track(db) else detector.thresholdDb = floor.threshold()
                    if (detector.onFrame(db, FRAME_MS, true)) {
                        // Talking over the reply: stop the voice mid-word and take the microphone back.
                        speaker.stop()
                        detector.reset()
                        floor.reset()
                        partial.value = ""
                        move(VoiceEvent.BargeIn)
                    }
                }
            },
            onFinal = { text ->
                val clean = text.trim()
                level.value = 0f
                partial.value = ""
                micRef[0]?.cancel()
                val wasSpeaking = state.value == VoiceLoopState.SPEAKING
                if (wasSpeaking) {
                    speaker.stop()
                    detector.reset()
                }
                if (clean.isEmpty()) {
                    if (wasSpeaking) {
                        move(VoiceEvent.BargeIn)
                    } else {
                        problem.value("Nothing was recognised. Tap the mic and try again.")
                    }
                } else {
                    if (wasSpeaking) state.value = VoiceLoopState.LISTENING
                    move(VoiceEvent.Heard(clean))
                    transcript.value(clean)
                    // The words land in the COMPOSER for review, not sent straight off, so this
                    // turn is finished: back to IDLE until the owner taps the mic again. (THINKING
                    // exists in the policy for a send-driven turn; the chat reviews first.)
                    state.value = VoiceLoopState.IDLE
                }
            },
            onFailure = { reason ->
                level.value = 0f
                partial.value = ""
                // A failure of the interruption microphone must not kill a reply that is playing.
                if (state.value != VoiceLoopState.SPEAKING) {
                    state.value = VoiceLoopState.IDLE
                    problem.value(reason)
                }
            },
        )
    }
    micRef[0] = recognizer

    val beginMic: () -> Unit = {
        val tag = Locale.getDefault().toLanguageTag()
        val decision = RecognitionPlan.decide(recognizer.capability(cloudAllowed = true, languageTag = tag))
        if (decision is RecognitionPlan.Decision.Unavailable) {
            problem.value(decision.reason)
            state.value = VoiceLoopState.IDLE
        } else {
            val started = recognizer.start(decision, tag, VadPolicy.Config().endSilenceMs)
            if (started is RecognitionPlan.Decision.Unavailable) {
                problem.value(started.reason)
                state.value = VoiceLoopState.IDLE
            }
        }
    }

    beginMicRef[0] = beginMic

    val stopAll: () -> Unit = {
        speaker.stop()
        captureRef[0]?.stop()
        micRef[0]?.cancel()
        detector.reset()
        level.value = 0f
        partial.value = ""
        state.value = VoiceLoopState.IDLE
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            state.value = VoiceLoopState.LISTENING
            beginMic()
        } else {
            problem.value(
                "Microphone permission is required for voice. Grant it in Settings, then try again.",
            )
        }
    }

    val start: () -> Unit = {
        when (state.value) {
            VoiceLoopState.IDLE -> {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    state.value = VoiceLoopState.LISTENING
                    // On-device when its model is installed: our recorder, our VAD, our engine.
                    if (localReady) {
                        captureRef[0]?.start()
                    } else {
                        // Not ready: keep listening with the platform recogniser now, and heal the
                        // model in the background so the next turn can be fully offline.
                        beginMic()
                    }
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            // Any other state: the tap is a stop. One control, two meanings, the way voice modes work.
            else -> stopAll()
        }
    }

    val speak: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            // VS Code keeps two speech sessions from ever being in progress at once; the same
            // invariant here. The microphone is closed BEFORE the reply starts, then re-opened.
            micRef[0]?.cancel()
            detector.reset()
            floor.reset()
            speaker.speak(text)
            // OWNER REQUEST (2026-11-24): the mic still opened with read-aloud. Reading a reply now
            // touches the loop state NOT AT ALL, so nothing can open the microphone, animate the chip
            // or claim the mic is listening. The loop only ever moves when the owner taps the mic.
            speakingSince[0] = System.currentTimeMillis()
            // OWNER DECISION (2026-11-24): read-aloud no longer opens the microphone. Talking over a
            // reply does nothing now, which is how it worked before the downloaded model existed.
        }
    }

    DisposableEffect(speaker) {
        speaker.onFinished = {
            if (state.value == VoiceLoopState.SPEAKING) {
                detector.reset()
                floor.reset()
                captureRef[0]?.stop()
                micRef[0]?.cancel()
                level.value = 0f
                partial.value = ""
                state.value = VoiceLoopState.IDLE
            }
        }
        onDispose {
            speaker.onFinished = null
            micRef[0]?.release()
            captureRef[0]?.close()
            localAsr?.release()
        }
    }

    return remember(recognizer) {
        VoiceLoop(state, level, partial, start, stopAll, speak)
    }
}

/** The recogniser's RMS callback is not frame-stamped; 50 ms is its usual cadence. */
private const val FRAME_MS = 50

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
    val state = remember { mutableStateOf(VoiceLoopState.IDLE) }
    val level = remember { mutableStateOf(0f) }
    val partial = remember { mutableStateOf("") }
    val detector = remember { BargeInDetector() }
    val floor = remember { AmbientFloor() }
    val micRef = remember { arrayOfNulls<VoiceRecognizer>(1) }
    val problem = rememberUpdatedState(onProblem)
    val transcript = rememberUpdatedState(onTranscript)

    val move: (VoiceEvent) -> Unit = { event ->
        state.value = VoiceLoopPolicy.next(state.value, event, readAloud = true)
    }
    // onRmsChanged reports dB above the noise floor: about -2 in silence, 10 for close speech.
    val levelFrom: (Float) -> Float = { db -> ((db + 2f) / 12f).coerceIn(0f, 1f) }

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

    val stopAll: () -> Unit = {
        speaker.stop()
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
                    beginMic()
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
            state.value = VoiceLoopState.SPEAKING
            // Re-open the microphone so the owner can talk over the reply. Without permission the
            // reply still reads; it just cannot be interrupted.
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) beginMic()
        }
    }

    DisposableEffect(speaker) {
        speaker.onFinished = {
            if (state.value == VoiceLoopState.SPEAKING) {
                detector.reset()
                floor.reset()
                micRef[0]?.cancel()
                level.value = 0f
                partial.value = ""
                state.value = VoiceLoopState.IDLE
            }
        }
        onDispose {
            speaker.onFinished = null
            micRef[0]?.release()
        }
    }

    return remember(recognizer) {
        VoiceLoop(state, level, partial, start, stopAll, speak)
    }
}

/** The recogniser's RMS callback is not frame-stamped; 50 ms is its usual cadence. */
private const val FRAME_MS = 50

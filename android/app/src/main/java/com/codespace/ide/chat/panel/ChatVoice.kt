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
import java.util.Locale

/**
 * M3 - REAL DICTATION FOR CHAT (owner decision: option A), rebuilt 2026-11-23.
 *
 * OWNER REQUEST: "for the mic icon in the chat header I want it to be able to work perfectly
 * without showing the google icon on tap, I'd like something custom like what you see in the image
 * and I need animations for it."
 *
 * The old path launched [android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH] as an ACTIVITY.
 * That hands the microphone to whichever app owns that intent - on a normal phone, the Google app -
 * so the owner got Google's listening sheet with Google's icon. Nothing the app drew could cover
 * that, because it was a different activity on top of ours.
 *
 * This path never leaves the app. [VoiceRecognizer] binds the recognition SERVICE directly: results
 * arrive through a listener, the microphone level arrives through `onRmsChanged` (which is what
 * drives the bars), and the app draws its own listening surface. No intent, no Google activity.
 *
 * Every way this can fail still has its own honest outcome, and permission is requested by us
 * because we are now the ones holding the microphone.
 */
internal sealed class DictationOutcome {
    data class Heard(val text: String) : DictationOutcome()
    data class Unavailable(val reason: String) : DictationOutcome()
    data class Failed(val reason: String) : DictationOutcome()
    object Cancelled : DictationOutcome()
}

/**
 * The handle the composer holds. The three readable fields are Compose state, so a screen that reads
 * them redraws as the level and the transcript change.
 */
internal class Dictation(
    private val listeningState: MutableState<Boolean>,
    private val partialState: MutableState<String>,
    private val levelState: MutableState<Float>,
    private val disclosureState: MutableState<String?>,
    val start: () -> Unit,
    val cancel: () -> Unit,
) {
    val listening: Boolean get() = listeningState.value
    val partial: String get() = partialState.value
    val level: Float get() = levelState.value
    val disclosure: String? get() = disclosureState.value
}

@Composable
internal fun rememberDictation(onOutcome: (DictationOutcome) -> Unit): Dictation {
    val context = LocalContext.current
    val listening = remember { mutableStateOf(false) }
    val partial = remember { mutableStateOf("") }
    val level = remember { mutableStateOf(0f) }
    val disclosure = remember { mutableStateOf<String?>(null) }
    val outcome = rememberUpdatedState(onOutcome)

    // onRmsChanged reports dB above the noise floor, roughly -2 (silence) to 10 (close speech).
    fun levelFrom(db: Float): Float = ((db + 2f) / 12f).coerceIn(0f, 1f)

    val recognizer = remember {
        VoiceRecognizer(
            context = context.applicationContext,
            onPartial = { text -> partial.value = text },
            onRms = { db -> level.value = levelFrom(db) },
            onFinal = { text ->
                listening.value = false
                level.value = 0f
                val clean = text.trim()
                partial.value = ""
                if (clean.isEmpty()) {
                    outcome.value(DictationOutcome.Failed("Nothing was recognised."))
                } else {
                    outcome.value(DictationOutcome.Heard(clean))
                }
            },
            onFailure = { reason ->
                listening.value = false
                level.value = 0f
                partial.value = ""
                outcome.value(DictationOutcome.Failed(reason))
            },
        )
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer.release() }
    }

    fun begin() {
        val tag = Locale.getDefault().toLanguageTag()
        val decision = RecognitionPlan.decide(recognizer.capability(cloudAllowed = true, languageTag = tag))
        when (decision) {
            is RecognitionPlan.Decision.Unavailable ->
                outcome.value(DictationOutcome.Unavailable(decision.reason))
            else -> {
                disclosure.value = (decision as? RecognitionPlan.Decision.Cloud)?.disclosure
                partial.value = ""
                level.value = 0f
                val started = recognizer.start(decision, tag, VadPolicy.Config().endSilenceMs)
                if (started is RecognitionPlan.Decision.Unavailable) {
                    listening.value = false
                    outcome.value(DictationOutcome.Unavailable(started.reason))
                } else {
                    listening.value = true
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            begin()
        } else {
            outcome.value(
                DictationOutcome.Unavailable(
                    "Microphone permission is required to dictate. Grant it in Settings, then try again.",
                ),
            )
        }
    }

    val start: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            begin()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val cancel: () -> Unit = {
        recognizer.cancel()
        listening.value = false
        level.value = 0f
        partial.value = ""
    }

    return remember(recognizer) {
        Dictation(listening, partial, level, disclosure, start, cancel)
    }
}

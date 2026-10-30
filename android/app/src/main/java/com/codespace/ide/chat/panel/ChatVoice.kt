package com.codespace.ide.chat.panel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * M3 — REAL DICTATION FOR CHAT (owner decision: option A).
 *
 * Uses the platform speech recogniser through [RecognizerIntent], the same mechanism the
 * terminal's voice dictation already uses. No transcript is ever invented: the only source of
 * text is `EXTRA_RESULTS` from a real recognition result, and every way this can fail has its
 * own honest outcome.
 *
 * Permission: the recogniser runs in its own process and holds the microphone permission, so the
 * app does not request it here. A device with no recogniser, or with recognition disabled,
 * reports [DictationOutcome.Unavailable] and the UI says so instead of failing silently.
 */
internal sealed class DictationOutcome {
    data class Heard(val text: String) : DictationOutcome()
    data class Unavailable(val reason: String) : DictationOutcome()
    data class Failed(val reason: String) : DictationOutcome()
    object Cancelled : DictationOutcome()
}

@Composable
internal fun rememberDictation(onOutcome: (DictationOutcome) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK) {
            // A cancel is not a failure: the user dismissed the recogniser. An error code WITH
            // a payload means the recogniser ran and refused (no match, no speech).
            val error = data?.getIntExtra(RecognizerIntent.EXTRA_RESULTS, -1) ?: -1
            if (data == null) onOutcome(DictationOutcome.Cancelled)
            else onOutcome(DictationOutcome.Failed(describe(result.resultCode, error)))
            return@rememberLauncherForActivityResult
        }
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) {
            onOutcome(DictationOutcome.Failed("Nothing was recognised."))
        } else {
            onOutcome(DictationOutcome.Heard(text))
        }
    }
    return remember(launcher) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate a message")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                onOutcome(DictationOutcome.Unavailable("No speech recognition service is installed on this device."))
            } catch (t: Throwable) {
                onOutcome(DictationOutcome.Failed("Could not start speech recognition (" + (t.message ?: "unknown") + ")."))
            }
        }
    }
}

private fun describe(resultCode: Int, error: Int): String = when {
    error != -1 -> "Speech recognition reported an error (" + error + ")."
    resultCode == Activity.RESULT_CANCELED -> "Speech recognition was cancelled."
    else -> "Speech recognition did not return a transcript."
}

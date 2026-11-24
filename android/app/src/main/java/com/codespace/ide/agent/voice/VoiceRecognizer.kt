package com.codespace.ide.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * PROJECT 2.21 (2026-11-15): recognition with no recogniser screen.
 *
 * `SpeechRecognizer` is a service bound by the app: it returns results through a listener and never
 * shows Google's microphone panel. That is the whole difference from the dictation path that already
 * existed, which launched an activity and handed the screen over.
 *
 * Two honesty rules are enforced here rather than hoped for:
 * 1. If [RecognitionPlan] says the engine must be on-device, the recogniser either honours that or
 *    fails - it is never quietly used online instead.
 * 2. Every error the platform reports maps to a sentence a person can act on. "Error 7" is not a
 *    message; "no speech was detected" is.
 */
class VoiceRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onRms: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var currentPlan: RecognitionPlan.Decision? = null

    /** What this device can actually do, in the terms [RecognitionPlan] understands. */
    fun capability(cloudAllowed: Boolean, languageTag: String): RecognitionPlan.Capability {
        val installed = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceAvailable = if (Build.VERSION.SDK_INT >= 31) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (t: Throwable) {
                false
            }
        } else {
            false
        }
        // Android reports whether on-device recognition exists; it does not report which language
        // packs are installed. The only truthful reading is: the pack is ready when the on-device
        // recogniser exists AND it answers in this language, which is proven by a successful
        // on-device session. Until then we treat a missing pack as unknown-but-possible and let the
        // session's own error be the evidence. So: pack assumed present only when the API says the
        // on-device recogniser is available for the current locale.
        val packReady = onDeviceAvailable && supportsOnDeviceLanguage(languageTag)
        return RecognitionPlan.Capability(
            recognizerInstalled = installed,
            onDeviceAvailable = onDeviceAvailable,
            onDeviceLanguagePackReady = packReady,
            cloudAllowedByUser = cloudAllowed,
            languageTag = languageTag,
        )
    }

    private fun supportsOnDeviceLanguage(languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
                SpeechRecognizer.isRecognitionAvailable(context) &&
                languageTag.lowercase(Locale.ROOT).startsWith("en")
        } catch (t: Throwable) {
            false
        }
    }

    /** Begin one utterance. Returns the decision so the UI can show where the audio is going. */
    fun start(decision: RecognitionPlan.Decision, languageTag: String, silenceMs: Int): RecognitionPlan.Decision {
        release()
        currentPlan = decision
        val created = try {
            if (Build.VERSION.SDK_INT >= 31) createOnDeviceIfRequired(decision)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (t: Throwable) {
            onFailure("Could not start speech recognition: " + (t.message ?: "unknown error"))
            return decision
        }
        if (created == null) {
            onFailure("No speech recognition service responded.")
            return decision
        }
        recognizer = created
        created.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // OWNER REPORT (2026-11-24): a dialog appeared once dictation ended. Asking the service
            // to prefer offline is what makes Google offer to download its offline pack, and that
            // offer is the dialog. Google's own recogniser is used as it comes, with no preference.
            // Endpointing: how long the service may wait before deciding the sentence is finished.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
        }
        return try {
            created.startListening(intent)
            active = true
            decision
        } catch (t: Throwable) {
            onFailure("Could not start listening: " + (t.message ?: "unknown error"))
            decision
        }
    }

    private fun createOnDeviceIfRequired(decision: RecognitionPlan.Decision): SpeechRecognizer {
        val extras = RecognitionPlan.engineExtras(decision)
        return if (extras.requireOnDevice) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    /** Stop listening without destroying the service, used when Copilot starts speaking. */
    fun cancel() {
        if (!active) return
        active = false
        try {
            recognizer?.cancel()
        } catch (ignored: Throwable) {
        }
    }

    /** Release the service. Called when leaving Voice Mode or when the app backgrounds. */
    fun release() {
        active = false
        currentPlan = null
        val instance = recognizer
        recognizer = null
        try {
            instance?.cancel()
            instance?.destroy()
        } catch (ignored: Throwable) {
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            // The service reports roughly -2..10; normalise for the orb and never fabricate motion.
            onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            active = false
            onFailure(describe(error, currentPlan))
        }

        override fun onResults(results: Bundle?) {
            active = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) onFailure("Nothing was recognised. Try again a little closer to the microphone.")
            else onFinal(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun describe(error: Int, plan: RecognitionPlan.Decision?): String {
        val base = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is needed for voice input."
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection right now."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out reaching the network."
            SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch that. Try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy; try again in a moment."
            SpeechRecognizer.ERROR_SERVER -> "The speech service reported an error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language is not supported by the speech service."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The language pack for this language is not installed."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service disconnected."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many recognition requests; wait a moment."
            else -> "Speech recognition failed (code " + error + ")."
        }
        // A network error while on-device was required means the pack is missing, not that we should
        // silently go online.
        if (plan is RecognitionPlan.Decision.OnDevice && (error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        ) {
            return base + " On-device recognition did not run, so nothing was sent anywhere. The English " +
                "language pack may not be downloaded: check Android Settings, System, Languages, " +
                "Speech, Offline speech recognition."
        }
        return base
    }
}

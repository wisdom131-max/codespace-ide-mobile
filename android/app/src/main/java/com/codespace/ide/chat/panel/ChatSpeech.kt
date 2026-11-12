package com.codespace.ide.chat.panel

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * P7.D - reading a reply aloud, with the same honesty as dictation.
 *
 * Input already existed (`ChatVoice.rememberDictation`, honest outcomes, no invented transcript).
 * Output did not. This is the output half: the platform speech engine, one utterance at a time, a
 * cancel that actually stops it, and a state that says whether it is speaking, idle, or unavailable.
 *
 * Nothing here touches normal text chat: a device with no TTS engine reports [SpeechState.Unavailable]
 * and the composer, the transcript and the send path are unaffected.
 */
sealed class SpeechState {
    data object Idle : SpeechState()
    data object Loading : SpeechState()
    data object Speaking : SpeechState()
    data class Unavailable(val reason: String) : SpeechState()
}

/** Pure: how much of [text] is worth speaking, so a long answer is bounded rather than endless. */
internal object SpeechText {
    const val MAX_CHARS = 1_200

    /**
     * The spoken form: fenced code is dropped (reading source aloud is noise), markers are stripped,
     * whitespace collapsed, and the whole thing capped. Returns null when nothing is left to say.
     */
    fun spoken(text: String): String? {
        val noCode = text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        val flat = noCode.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        if (flat.length <= MAX_CHARS) return flat
        val cut = flat.take(MAX_CHARS)
        val atSentence = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        val body = if (atSentence > MAX_CHARS / 2) cut.take(atSentence + 1) else cut
        return body.trim() + " ... (the rest is in the chat.)"
    }
}

/**
 * A speaker bound to the composition. [speak] replaces whatever is currently being said (one reply at
 * a time, never a queue the user cannot stop), [stop] halts immediately, and both are safe to call
 * when the engine failed to initialise.
 */
internal class Speaker {
    var state: SpeechState = SpeechState.Loading
        private set
    private var tts: TextToSpeech? = null
    private var onState: (SpeechState) -> Unit = {}

    fun attach(engine: TextToSpeech?, ready: Boolean, reason: String?) {
        tts = engine
        state = if (ready) SpeechState.Idle else SpeechState.Unavailable(reason ?: "No speech engine is available.")
        onState(state)
    }

    fun observe(listener: (SpeechState) -> Unit) {
        onState = listener
        listener(state)
    }

    fun speak(text: String) {
        val engine = tts
        val body = SpeechText.spoken(text)
        if (engine == null || body == null) {
            state = if (engine == null) SpeechState.Unavailable("No speech engine is available.") else SpeechState.Idle
            onState(state)
            return
        }
        engine.stop()
        val queued = engine.speak(body, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
        state = if (queued == TextToSpeech.SUCCESS) SpeechState.Speaking else SpeechState.Idle
        onState(state)
    }

    fun stop() {
        tts?.stop()
        if (state == SpeechState.Speaking) {
            state = SpeechState.Idle
            onState(state)
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    internal fun onDone() {
        if (state == SpeechState.Speaking) {
            state = SpeechState.Idle
            onState(state)
        }
    }

    companion object {
        const val UTTERANCE = "vncode-reply"
    }
}

/**
 * Binds a [Speaker] to the composition. The engine is created once, shut down on dispose, and reports
 * unavailable rather than throwing when the device has none.
 */
@Composable
internal fun rememberSpeaker(onState: (SpeechState) -> Unit = {}): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker() }
    DisposableEffect(speaker) {
        speaker.observe(onState)
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ok = engine?.setLanguage(Locale.getDefault())
                val usable = ok != TextToSpeech.LANG_MISSING_DATA && ok != TextToSpeech.LANG_NOT_SUPPORTED
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = speaker.onDone()
                    @Deprecated("Required override")
                    override fun onError(utteranceId: String?) = speaker.onDone()
                })
                speaker.attach(engine, usable, if (usable) null else "This device has no voice for your language.")
            } else {
                speaker.attach(null, false, "This device has no speech engine installed.")
            }
        }
        onDispose {
            // Detach the listener chain first: a shutdown while the engine is still speaking a long
            // reply is the one case that can throw on some OEM builds.
            runCatching { speaker.stop() }
            speaker.release()
        }
    }
    return speaker
}

package com.codespace.ide.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * PROJECT 2.22 (2026-11-15): the on-device voice, tuned.
 *
 * One utterance at a time, never a queue the user cannot stop: a new segment replaces what is being
 * said only when the caller asked for a replacement, and [stop] silences the engine immediately.
 *
 * The voice is chosen through [VoiceSettings.choose], so a device with no male voice still speaks and
 * reports that the preference could not be met. Pitch and rate default to the recipe observed in the
 * Brahma dex, expressed as multipliers because Android's engine takes multipliers.
 *
 * Every failure path ends in [Listener.onUnavailable] with a sentence, never in silence.
 */
class AndroidTtsProvider(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onReady(status: VoiceProviders.Status)
        fun onUnavailable(reason: String)
        /**
         * The engine began a spoken word (API 26+ `onRangeStart`). This is the only real playback
         * signal Android exposes to an app, so it is what the orb's speech pulse is driven by.
         */
        fun onWordStart() = Unit

        fun onUtteranceStart()
        fun onUtteranceDone() 
        fun onSpeakingStopped()
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentUtterance: String? = null
    /** Settings the engine is currently applying. Written through [applyPrefs]. */
    var prefs: VoiceSettings.Prefs = VoiceSettings.Prefs()
        private set
    private var chosen: VoiceSettings.Choice = VoiceSettings.Choice.Unavailable("not initialised yet")

    val isReady: Boolean get() = ready
    val voiceChoice: VoiceSettings.Choice get() = chosen

    /** Replace the settings, then push them into the engine when it is ready. */
    fun applyPrefs(next: VoiceSettings.Prefs) {
        prefs = next
        applyPreferences()
    }

    fun initialise(prefs: VoiceSettings.Prefs) {
        this.prefs = prefs
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                listener.onUnavailable("The device's speech engine did not start.")
                return@TextToSpeech
            }
            ready = true
            applyPreferences()
        }
    }

    /** Push settings and voice choice into the engine. Safe to call repeatedly. */
    fun applyPreferences() {
        val engine = tts ?: return
        if (!ready) return
        val language = try {
            engine.setLanguage(Locale.US)
        } catch (t: Throwable) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            listener.onUnavailable("This device's speech engine has no English voice installed.")
            return
        }
        val candidates = engine.voices.orEmpty().map { voice: Voice ->
            VoiceSettings.Candidate(
                name = voice.name,
                locale = voice.locale.toLanguageTag(),
                isNetworkRequired = voice.isNetworkConnectionRequired,
                features = voice.features.orEmpty(),
            )
        }
        chosen = VoiceSettings.choose(candidates, prefs.voiceName, prefs.localOnly)
        (chosen as? VoiceSettings.Choice.Picked)?.let { picked ->
            candidates.firstOrNull { it.name == picked.candidate.name }?.let { match ->
                engine.voices?.firstOrNull { it.name == match.name }?.let { voice ->
                    try {
                        engine.voice = voice
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        engine.setPitch(VoiceSettings.clampPitch(prefs.pitch))
        engine.setSpeechRate(VoiceSettings.clampRate(prefs.rate))
        engine.setOnUtteranceProgressListener(progress)
        listener.onReady(
            VoiceProviders.Status(
                providerId = VoiceProviders.ANDROID_TTS,
                label = "Android speech engine (on-device)",
                detail = (chosen as? VoiceSettings.Choice.Picked)?.why
                    ?: (chosen as? VoiceSettings.Choice.Unavailable)?.reason.orEmpty(),
                degraded = chosen is VoiceSettings.Choice.Unavailable,
            )
        )
    }

    /**
     * Speak one segment. Returns false when nothing was said, so the caller can keep the state
     * machine honest rather than waiting for a callback that will never arrive.
     */
    fun speak(segment: String, flush: Boolean): Boolean {
        val engine = tts
        if (!ready || engine == null) {
            listener.onUnavailable("No speech engine is ready.")
            return false
        }
        val text = segment.trim()
        if (text.isEmpty()) return false
        val id = UUID.randomUUID().toString()
        currentUtterance = id
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = try {
            engine.speak(text, mode, null, id)
        } catch (t: Throwable) {
            listener.onUnavailable("The speech engine failed: " + (t.message ?: "unknown error"))
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            listener.onUnavailable("The speech engine could not say that.")
            return false
        }
        return true
    }

    /** Stop immediately. The interface reflects the stop at once; this is not a fade. */
    fun stop() {
        try {
            tts?.stop()
        } catch (ignored: Throwable) {
        }
        currentUtterance = null
        listener.onSpeakingStopped()
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (ignored: Throwable) {
        }
        tts = null
        ready = false
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            listener.onWordStart()
        }

        override fun onStart(utteranceId: String?) {
            listener.onUtteranceStart()
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == currentUtterance) currentUtterance = null
            listener.onUtteranceDone()
        }

        @Deprecated("Required override; the id-based overload is the one the engine calls.")
        override fun onError(utteranceId: String?) {
            listener.onUnavailable("The speech engine reported an error while speaking.")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            listener.onUnavailable("The speech engine reported an error while speaking (" + errorCode + ").")
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            listener.onSpeakingStopped()
        }
    }
}

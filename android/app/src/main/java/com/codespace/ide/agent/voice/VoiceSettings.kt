package com.codespace.ide.agent.voice

/**
 * PROJECT 2.14 (2026-11-15): choosing a voice, and admitting when the device has none.
 *
 * Android has no "give me a male voice" call. `TextToSpeech` exposes whatever the installed engine
 * ships - Voice objects with a name, a locale and a feature set - and the catalogue differs per
 * phone and per engine. So the choice is a preference ORDER, and every step down it is a fact worth
 * surfacing rather than a silent surprise.
 *
 * The one thing copied from the Brahma dex is its tuning, because it is a public, measurable recipe
 * rather than code: it wraps speech in SSML with pitch -2Hz and rate +2%. Android's TTS takes pitch
 * and rate as multipliers instead, so the same intent is expressed as a small downward pitch and a
 * small upward rate.
 */
object VoiceSettings {

    /** A voice as the engine describes it, flattened so this file needs no Android types. */
    data class Candidate(
        val name: String,
        val locale: String,
        val isNetworkRequired: Boolean,
        val features: Set<String> = emptySet(),
    )

    data class Prefs(
        val spokenReplies: Boolean = false,
        val welcomeEnabled: Boolean = true,
        val voiceName: String? = null,
        val pitch: Float = DEFAULT_PITCH,
        val rate: Float = DEFAULT_RATE,
        val localOnly: Boolean = true,
    )

    sealed class Choice {
        data class Picked(val candidate: Candidate, val why: String) : Choice()

        /** No usable voice. The caller must say so rather than speak in silence. */
        data class Unavailable(val reason: String) : Choice()
    }

    /**
     * Pick a male English voice if the engine has one, then any English voice, then anything.
     *
     * Gender is not a first-class field in Android's voice list, so it has to be read from the
     * voice's own name - engines that offer male and female variants name them so ("en-us-x-iom-local"
     * style identifiers aside, the descriptive names contain "male"). Anything else is a guess, and a
     * guess is labelled as one in [Choice.Picked.why].
     */
    fun choose(candidates: List<Candidate>, preferredName: String? = null, localOnly: Boolean = true): Choice {
        val usable = candidates.filter { !localOnly || !it.isNetworkRequired }
        if (usable.isEmpty()) {
            return Choice.Unavailable(
                if (candidates.isEmpty()) "this device reports no installed voices at all"
                else "every installed voice needs a network connection and local-only is on"
            )
        }
        val preferred = preferredName?.let { name -> usable.firstOrNull { it.name == name } }
        if (preferred != null) return Choice.Picked(preferred, "your chosen voice")

        val english = usable.filter { it.locale.startsWith("en", ignoreCase = true) }
        val male = english.firstOrNull { looksMale(it.name) }
        if (male != null) return Choice.Picked(male, "a male English voice on this device")
        val englishAny = english.firstOrNull()
        if (englishAny != null) {
            return Choice.Picked(
                englishAny,
                "no voice is marked male on this device, so this English voice is used; " +
                    "the male preference cannot be honoured here",
            )
        }
        return Choice.Picked(usable.first(), "no English voice is installed; using " + usable.first().locale)
    }

    /** Heuristic, deliberately narrow: only an explicit word counts. */
    fun looksMale(voiceName: String): Boolean {
        val lower = voiceName.lowercase()
        if (lower.contains("female")) return false
        return lower.contains("male") || lower.contains("#male") || lower.contains("_male")
    }

    fun clampPitch(pitch: Float): Float = pitch.coerceIn(MIN_PITCH, MAX_PITCH)

    fun clampRate(rate: Float): Float = rate.coerceIn(MIN_RATE, MAX_RATE)

    /** Small downward pitch, small upward rate: the recipe observed in the Brahma dex, within our bounds. */
    const val DEFAULT_PITCH = 0.95f
    const val DEFAULT_RATE = 1.02f

    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 1.5f
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f
}

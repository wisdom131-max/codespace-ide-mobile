package com.codespace.ide.chat.panel.voice

/**
 * OWNER REQUEST (2026-11-23): "there's a read replies aloud button in the overflow menu, I want it
 * to be able to exercise emotions - we should find a way".
 *
 * The honest way, on Android, without a cloud voice: the platform engine exposes PITCH and RATE per
 * utterance and nothing else expressive. SSML is ignored by most installed engines, and a neural
 * voice would mean uploading every reply to a service. So emotion here is real prosody: each
 * sentence is classified and spoken at its own pitch and rate, one utterance at a time, so the
 * setting actually applies to that sentence instead of leaking into the one already queued.
 *
 * Everything in this file is pure: no Android, no engine, no audio. That is deliberate - it is the
 * part that can be tested, so the classifier cannot quietly drift.
 */
internal enum class SpeechEmotion {
    NEUTRAL, WARM, EXCITED, SERIOUS, APOLOGETIC, CURIOUS,
}

/** Multipliers applied to the engine's baseline, never absolute values. */
internal data class Prosody(val pitch: Float, val rate: Float) {
    fun clamped(): Prosody = Prosody(
        pitch.coerceIn(0.5f, 2.0f),
        rate.coerceIn(0.5f, 2.0f),
    )
}

internal object EmotionProsody {

    /**
     * Order matters. An apology inside a serious sentence is still an apology; a warning inside an
     * excited one is still a warning. Excitement never wins over a real caution.
     */
    private val APOLOGETIC = listOf(
        "sorry", "apolog", "my mistake", "i was wrong", "i got that wrong", "unfortunately",
        "that is on me", "that's on me",
    )
    private val SERIOUS = listOf(
        "warning", "careful", "danger", "critical", "failed", "failure", "error", "cannot",
        "can't", "never", "must not", "important", "breaks", "will lose", "data loss", "revert",
    )
    private val EXCITED = listOf(
        "great", "nice", "perfect", "works", "working now", "done", "fixed", "success", "shipped",
        "all green", "passed", "ready",
    )
    private val WARM = listOf(
        "thanks", "thank you", "glad", "hope", "happy", "welcome", "please", "you'll", "you can",
        "let me know", "appreciate",
    )
    private val CURIOUS_OPENERS = listOf(
        "would you", "do you want", "shall i", "should i", "want me to", "which one", "how about",
    )

    fun classify(sentence: String): SpeechEmotion {
        val s = sentence.lowercase().trim()
        if (s.isEmpty()) return SpeechEmotion.NEUTRAL
        if (APOLOGETIC.any { s.contains(it) }) return SpeechEmotion.APOLOGETIC
        if (SERIOUS.any { s.contains(it) }) return SpeechEmotion.SERIOUS
        val questions = s.count { it == '?' }
        val exclamations = s.count { it == '!' }
        if (exclamations > 0 && questions == 0) return SpeechEmotion.EXCITED
        if (EXCITED.any { s.contains(it) }) return SpeechEmotion.EXCITED
        if (questions > 0 || CURIOUS_OPENERS.any { s.startsWith(it) }) return SpeechEmotion.CURIOUS
        if (WARM.any { s.contains(it) }) return SpeechEmotion.WARM
        return SpeechEmotion.NEUTRAL
    }

    /**
     * Pitch up and slightly faster reads as pleased; pitch down and slower reads as grave; a small
     * lift with a slower rate reads as a question. These are deliberately small: a large jump stops
     * sounding like a person and starts sounding like a cartoon.
     */
    fun prosodyFor(emotion: SpeechEmotion): Prosody = when (emotion) {
        SpeechEmotion.NEUTRAL -> Prosody(1.00f, 1.00f)
        SpeechEmotion.WARM -> Prosody(1.03f, 0.97f)
        SpeechEmotion.EXCITED -> Prosody(1.12f, 1.10f)
        SpeechEmotion.SERIOUS -> Prosody(0.92f, 0.93f)
        SpeechEmotion.APOLOGETIC -> Prosody(0.95f, 0.92f)
        SpeechEmotion.CURIOUS -> Prosody(1.08f, 0.98f)
    }.clamped()

    /**
     * Split into speakable sentences. A very long sentence is cut at a comma so one clause cannot
     * hold the microphone for ten seconds with a single flat tone.
     */
    fun sentences(text: String, maxChars: Int = 220): List<String> {
        val out = ArrayList<String>()
        val rough = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
        for (piece in rough) {
            val p = piece.trim()
            if (p.isEmpty()) continue
            if (p.length <= maxChars) { out.add(p); continue }
            var rest = p
            while (rest.length > maxChars) {
                val cut = rest.lastIndexOf(',', maxChars).takeIf { it > maxChars / 3 }
                    ?: rest.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 3 }
                    ?: maxChars
                out.add(rest.substring(0, cut).trim().trimEnd(','))
                rest = rest.substring(cut).trimStart().trimStart(',')
            }
            if (rest.isNotEmpty()) out.add(rest)
        }
        return out
    }

    /** One sentence plus how it should be spoken. */
    data class Line(val text: String, val emotion: SpeechEmotion, val prosody: Prosody)

    fun plan(text: String): List<Line> = sentences(text).map { s ->
        val e = classify(s)
        Line(s, e, prosodyFor(e))
    }
}

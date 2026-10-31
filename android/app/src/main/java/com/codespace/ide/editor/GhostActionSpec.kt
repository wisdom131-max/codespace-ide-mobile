package com.codespace.ide.editor

/**
 * The ghost-text action strip (2026-10-31).
 *
 * The overlay used to accept on tap and nothing else: the first line meant "one word", any later
 * line meant "all of it", and there was no way to ask for exactly one line or to make the
 * suggestion go away for a while. Those are the three things a completion UI actually needs
 * beyond accept, so they are modelled here and drawn by the strip.
 */
internal object GhostActionSpec {

    /** A hide that lasts long enough to finish a thought, short enough not to feel broken. */
    const val SNOOZE_MS = 30_000L

    data class AcceptLine(val text: String, val remaining: List<String>)

    /** Accept exactly the first suggested line, keeping the rest of the suggestion on screen. */
    fun acceptLine(lines: List<String>): AcceptLine {
        if (lines.isEmpty()) return AcceptLine("", emptyList())
        val first = lines.first()
        val rest = lines.drop(1)
        return AcceptLine(first, rest)
    }

    fun snoozeActive(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs

    /** Hiding is always offered; accepting a word is only meaningful with text on screen. */
    fun canAcceptLine(lines: List<String>): Boolean = lines.isNotEmpty() && lines.first().isNotBlank()

    /**
     * Snooze is keyed by the suggestion itself: a NEW suggestion is not silenced by an old snooze,
     * which is the difference between "not now" and "never again".
     */
    fun snoozeKey(ghostText: String): Int = ghostText.hashCode()
}

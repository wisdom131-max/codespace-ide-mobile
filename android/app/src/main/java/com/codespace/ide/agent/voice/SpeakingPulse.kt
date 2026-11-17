package com.codespace.ide.agent.voice

/**
 * PROJECT SEVEN 7.10 — orbital energy while Copilot speaks, from a REAL playback signal.
 *
 * Android does not give an app access to the amplitude of another engine's audio output: the TTS
 * engine's audio session is not exposed, and `Visualizer` on session 0 is refused on modern
 * platforms. Claiming amplitude synchronisation would be a lie, so this uses the one real signal the
 * platform does deliver: `UtteranceProgressListener.onRangeStart`, which fires at the exact moment
 * the engine begins each spoken WORD. The orb therefore pulses on real speech cadence and decays
 * between words, which looks like speech because it IS the speech timing.
 *
 * The distinction matters for honesty: this is word-boundary driven, not waveform driven. It cannot
 * reflect loudness, only the rhythm of what is actually being said.
 */
internal object SpeakingPulse {

    /** How long a single word's pulse decays when the engine reports no gap yet. */
    const val DEFAULT_DECAY_MS = 420L

    /** Gaps outside this range are not a cadence, they are a stall or a one-off; fall back. */
    private const val MIN_GAP_MS = 120L
    private const val MAX_GAP_MS = 1_800L

    /** Called the instant the engine starts a word: the orb peaks, then decays. */
    fun onWordStart(): Float = 1f

    /**
     * @param nowMs current clock reading, from the same clock that produced [lastWordAtMs].
     * @param lastWordAtMs when the engine last began a word, or 0 when it has not begun one.
     * @param gapMs measured gap between the last two word starts, or 0 when unknown.
     */
    fun energyAt(nowMs: Long, lastWordAtMs: Long, gapMs: Long): Float {
        if (lastWordAtMs <= 0L) return 0f
        val elapsed = (nowMs - lastWordAtMs).coerceAtLeast(0L)
        val span = if (gapMs in MIN_GAP_MS..MAX_GAP_MS) gapMs else DEFAULT_DECAY_MS
        val remaining = 1f - (elapsed.toFloat() / span.toFloat())
        return remaining.coerceIn(0f, 1f)
    }

    /** Gap between consecutive word starts, or the default when there is no previous word. */
    fun gapBetween(previousWordAtMs: Long, nowMs: Long): Long {
        if (previousWordAtMs <= 0L) return DEFAULT_DECAY_MS
        return (nowMs - previousWordAtMs).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    }
}

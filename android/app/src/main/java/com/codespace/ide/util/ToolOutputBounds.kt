package com.codespace.ide.util

/**
 * PHASE 1 (owner instruction 2026-10-30): tool results that can be arbitrarily large are
 * capped before they reach the model, so one huge diff cannot consume the whole context
 * window and silently push out the conversation.
 *
 * The cap is honest: the truncated text says how much was shown and how much was
 * dropped, so the model can ask for a narrower range instead of assuming it saw
 * everything.
 */
object ToolOutputBounds {

    /** 8000 characters is roughly 2000 tokens: generous for reading, safe against blowout. */
    const val DEFAULT_MAX_CHARS: Int = 8000

    /**
     * Returns [text] unchanged when it already fits, otherwise the first
     * [maxChars] characters minus the marker, followed by the marker.
     */
    fun bound(text: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val marker = "\n[truncated: " + maxChars + " of " + text.length +
            " characters shown; narrow the request to see the rest]"
        val head = (maxChars - marker.length).coerceAtLeast(0)
        return text.take(head) + marker
    }
}

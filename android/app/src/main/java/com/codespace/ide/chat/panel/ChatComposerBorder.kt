package com.codespace.ide.chat.panel

/**
 * Which edge colour the composer card wears. Pure, so the rule is tested instead of
 * eyeballed: blue exactly when there is something to send, neutral otherwise.
 *
 * Whitespace-only text counts as empty (a stray space or newline must not light the
 * border), and the width never changes between the two states, only the colour, so
 * the card cannot shift by a pixel when the first character arrives.
 */
internal enum class ComposerBorder { NEUTRAL, ACTIVE }

internal object ChatComposerBorder {

    /** Stroke width in dp. One constant for both states: no layout jump. */
    const val WIDTH_DP = 1

    fun forText(text: String): ComposerBorder =
        if (text.isBlank()) ComposerBorder.NEUTRAL else ComposerBorder.ACTIVE
}

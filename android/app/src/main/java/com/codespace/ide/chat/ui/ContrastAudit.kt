package com.codespace.ide.chat.ui

import androidx.compose.ui.graphics.Color

/**
 * PROJECT SEVEN 7.9A — contrast MEASURED, not eyeballed.
 *
 * The palette is generated from `chat-tokens.json`, so the honest way to answer "is this readable" is to
 * compute the ratio from the actual token values the renderer will use, for both themes. WCAG AA needs
 * 4.5:1 for normal text and 3:1 for large text and meaningful non-text UI.
 *
 * Nothing here changes a colour by itself: [problems] reports, and the palette is fixed at its single
 * source when a pair fails.
 */
object ContrastAudit {

    /** One foreground used on one background, with what the guideline requires of it. */
    data class Pair(
        val name: String,
        val fg: Color,
        val bg: Color,
        val large: Boolean = false,
    ) {
        val required: Double get() = if (large) 3.0 else 4.5
        val ratio: Double get() = ratio(fg, bg)
        val passes: Boolean get() = ratio >= required
    }

    private fun channel(c: Float): Double {
        val s = c.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** The WCAG contrast ratio: (lighter + 0.05) / (darker + 0.05). */
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The pairs the chat actually renders, per theme. */
    fun pairs(c: ChatColors): List<Pair> = listOf(
        Pair("body text on background", c.text, c.bg),
        Pair("body text on surface", c.text, c.surface),
        Pair("secondary text on surface", c.textSecondary, c.surface),
        Pair("secondary text on background", c.textSecondary, c.bg),
        Pair("accent text on surface", c.accent, c.surface),
        Pair("error text on error surface", c.error, c.errorBg),
        Pair("warning text on warning surface", c.warning, c.warningBg),
        Pair("success text on surface", c.success, c.surface),
        Pair("stream cursor on surface", c.streamCursor, c.surface, large = true),
    )

    /**
     * MEASURED FINDING (checkpoint 3): the `border` token is a hairline divider - 1.60:1 in dark and
     * 1.41:1 in light - so it is decorative and exempt from 3:1 by WCAG 1.4.11, which covers controls
     * and states, not separators. Where a border DOES carry meaning (a focused input, a state outline)
     * it must not use that hairline. This derives a compliant strong border from the palette's own
     * direction of travel, so a meaning-bearing edge is available without repainting the app.
     */
    fun strongBorder(c: ChatColors, on: Color, dark: Boolean = true): Color {
        val step = 8
        for (i in 1..32) {
            val v = (if (dark) 0x45 + i * step else 0xD0 - i * step).coerceIn(0, 255)
            val candidate = Color(v, v, v)
            if (ratio(candidate, on) >= 3.0) return candidate
        }
        return if (dark) Color.White else Color.Black
    }

    fun problems(c: ChatColors): List<String> = pairs(c).filter { !it.passes }
        .map { it.name + " = " + "%.2f".format(it.ratio) + ":1, needs " + it.required + ":1" }

    /** "12.4:1" - for a report or a diagnostic note. */
    fun describe(c: ChatColors): String = pairs(c).joinToString("; ") {
        it.name + " " + "%.2f".format(it.ratio) + ":1 " + (if (it.passes) "ok" else "FAILS")
    }
}

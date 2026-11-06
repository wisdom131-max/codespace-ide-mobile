package com.codespace.ide.editor

/**
 * The hard ceiling on how tall a single Compose text layout can be, and the guard that keeps the
 * editor from ever asking for more.
 *
 * THE CRASH (owner's log, 2026-11-06_19-54-33, Thread main):
 *   java.lang.IllegalArgumentException: Can't represent a size of 3000001 in Constraints
 *     at androidx.compose.ui.unit.Constraints$Companion.bitsNeedForSize(Constraints.kt:403)
 *   at TextDelegate.layoutText(TextDelegate.kt:205)
 *   at CoreTextField.measure(CoreTextField.kt:679)
 *
 * WHY IT HAPPENS: Compose packs width and height into one 32-bit field. `bitsNeedForSize`
 * (verified by disassembling androidx.compose.ui.unit:ui-unit) returns 13 bits below 8,192 px,
 * 15 below 32,768, 16 below 65,536, 18 below 262,144, and THROWS above that. Width plus height
 * must fit in 31 bits, so on a phone (width well under 8,192 px, i.e. 13 bits) the tallest
 * layout that can exist is 262,143 px. A text field holding N lines is one layout N x lineHeight
 * tall, so a big enough file cannot be rendered by a text field AT ALL, no matter how fast the
 * styling is. This is a representational limit, not a performance one.
 *
 * The guard below runs BEFORE a tab is created, so the editor never builds that layout.
 */
internal object EditorLayoutLimits {

    /** Verified: bitsNeedForSize throws above this (2^18 - 1). */
    const val MAX_DIMENSION_PX = 262_143

    /**
     * Work to 60% of the ceiling. Compose also has to fit soft-wrapped display lines, the sticky
     * line padding and the IME insets into the same constraint, and a file that just fits is a
     * crash waiting for the user to change system font size.
     */
    const val SAFE_FRACTION = 0.60f

    val SAFE_MAX_PX: Int = (MAX_DIMENSION_PX * SAFE_FRACTION).toInt()

    /** Line height in px, exactly as the editor computes it (fontSize x multiplier, sp -> px). */
    fun lineHeightPx(fontSizeSp: Float, density: Float, fontScale: Float): Float {
        val heightSp = fontSizeSp * EditorMetrics.LINE_HEIGHT_MULTIPLIER
        return heightSp * density * fontScale.coerceAtLeast(0.5f)
    }

    /**
     * Lines that fit in the HARD ceiling (what Compose will actually accept). Above this the
     * layout throws; the guard deliberately stops earlier, at [SAFE_MAX_PX].
     */
    fun maxLinesHard(lineHeightPx: Float): Int =
        if (lineHeightPx <= 0f) Int.MAX_VALUE else (MAX_DIMENSION_PX / lineHeightPx).toInt()

    /** How many lines of this height fit inside the safe ceiling. */
    fun maxLines(lineHeightPx: Float): Int =
        if (lineHeightPx <= 0f) Int.MAX_VALUE else (SAFE_MAX_PX / lineHeightPx).toInt()

    /** True when a file of this many lines cannot be held by one editable text field. */
    fun exceedsEditor(lineCount: Int, lineHeightPx: Float): Boolean =
        lineCount > maxLines(lineHeightPx)

    /** The one sentence the user sees when the guard stops an open. Plain words, no jargon. */
    fun refusalMessage(lineCount: Int, lineHeightPx: Float): String {
        val max = maxLines(lineHeightPx)
        return "This file has " + lineCount + " lines. One editable page can show about " + max +
            " at this font size, and opening more would crash the app. Large-file viewing comes next."
    }
}

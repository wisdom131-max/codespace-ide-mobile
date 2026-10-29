package com.codespace.ide.chat.panel

/**
 * U1 (build 657 finding e) — the message input collapsed to a sliver, and its
 * actions ran off the edge at narrow widths.
 *
 * The panel is the whole screen on a phone and a narrow column in split view, and
 * the soft keyboard takes roughly half the height while the user types. A single
 * Row with `weight(1f)` on the field cannot serve those cases: the two action
 * labels claim their intrinsic width first at every width, and with the keyboard
 * open the field's spare height is gone too.
 *
 * This file holds the DECISION only, as pure data with no Compose imports, so the
 * breakpoints can be tested at 280 / 360 / 480 dp on the JVM instead of by eye on
 * a device. ChatInputRow renders whatever [ChatInputLayout.forWidth] returns; if a
 * test and the UI ever disagree, the UI is wrong.
 *
 * Breakpoints, and why:
 *  - below 340 dp the field takes its own full line. At 280 dp (a phone in split
 *    view) the field plus two labels in one row leaves the field under 100 dp —
 *    the reported "sliver".
 *  - 340-479 dp keeps the row layout; the actions stay beside the field because
 *    height is the scarce resource, not width.
 *  - 480 dp and up is comfortable: full labels, taller field.
 *  - with the keyboard open, height is halved, so the field drops to 2 lines and
 *    the stacked layout is forced until 400 dp — a 4-line field above an open
 *    keyboard hides the reply that was just streamed in.
 *  - below 240 dp even a stacked action row cannot fit two labels side by side,
 *    so the actions stack vertically rather than clipping.
 */
internal enum class ChatInputMode {
    /** Field on its own full-width line, actions beneath it. */
    STACKED,

    /** Field and actions share one line, field weighted. */
    INLINE,
}

internal data class ChatInputSpec(
    val mode: ChatInputMode,
    /** Floor for the text field so it can never be squeezed into a sliver. */
    val fieldMinWidthDp: Int,
    /** The field's max lines; shrinks when the keyboard eats the height. */
    val fieldMaxLines: Int,
    /** True when labels must be shortened to fit (e.g. "Send" not "Send anyway"). */
    val shortLabels: Boolean,
    /** True when the action row itself must stack vertically (ultra-narrow). */
    val actionsStackVertically: Boolean,
)

internal object ChatInputLayout {

    const val FIELD_MIN_DP = 130
    const val STACK_BELOW_DP = 340
    const val STACK_BELOW_DP_WITH_IME = 400
    const val ULTRA_NARROW_DP = 240
    const val SHORT_LABELS_BELOW_DP = 480

    fun forWidth(widthDp: Int, imeVisible: Boolean): ChatInputSpec {
        val w = if (widthDp < 0) 0 else widthDp
        val stackThreshold = if (imeVisible) STACK_BELOW_DP_WITH_IME else STACK_BELOW_DP
        val stacked = w < stackThreshold
        val ultraNarrow = w < ULTRA_NARROW_DP
        val maxLines = when {
            // Keyboard open: height is halved, so the field stays short even on a
            // tablet - a tall field above the keyboard hides the streamed reply.
            imeVisible && w < 600 -> 2
            imeVisible -> 3
            w < 480 -> 3
            else -> 5
        }
        return ChatInputSpec(
            mode = if (stacked) ChatInputMode.STACKED else ChatInputMode.INLINE,
            // The field never drops below a usable floor; in a stacked layout it is
            // full width anyway, so the floor only binds on the inline path.
            fieldMinWidthDp = FIELD_MIN_DP,
            fieldMaxLines = maxLines,
            shortLabels = w < SHORT_LABELS_BELOW_DP,
            actionsStackVertically = ultraNarrow,
        )
    }
}

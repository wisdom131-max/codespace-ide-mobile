package com.codespace.ide.editor

/**
 * Lightbulb icon semantics (2026-10-31).
 *
 * The bulb used to be one emoji for everything, so "there is a lint fix", "there is an AI fix" and
 * "there is both" all looked identical. VS Code varies the glyph, and the variation is the only
 * signal the user gets that asking for AI help is worth it on this line.
 *
 * One deliberate deviation: VS Code auto-runs an action when it is the only valid one. Auto-running
 * something that REWRITES code without a tap is not a trade this app makes on a phone, so a writing
 * action still needs a tap. [autoRunAllowed] states that, and a test pins it.
 */
enum class BulbIcon { BULB, SPARKLE, SPARKLE_WITH_FIX }

internal object LightbulbSpec {

    fun iconFor(hasQuickFix: Boolean, hasAiFix: Boolean): BulbIcon = when {
        hasAiFix && hasQuickFix -> BulbIcon.SPARKLE_WITH_FIX
        hasAiFix -> BulbIcon.SPARKLE
        else -> BulbIcon.BULB
    }

    fun glyph(icon: BulbIcon): String = when (icon) {
        BulbIcon.BULB -> "\uD83D\uDCA1"
        BulbIcon.SPARKLE -> "\u2728"
        BulbIcon.SPARKLE_WITH_FIX -> "\u2728+"
    }

    /**
     * VS Code pads the menu by about a third of a line height so the popup never sits on the line
     * it is offering to fix. Same rule here, derived from the font size rather than hardcoded.
     */
    fun menuPaddingDp(fontSize: Float): Float = fontSize * EditorMetrics.LINE_HEIGHT_MULTIPLIER / 3f

    /** Writing actions are never auto-run, whatever the provider says. */
    fun autoRunAllowed(writesCode: Boolean): Boolean = !writesCode

    /** True when any action kind is one of ours. Kinds are strings, so match the prefix. */
    fun hasAiAction(kinds: List<String>): Boolean = kinds.any { it.startsWith("ai.") }
}

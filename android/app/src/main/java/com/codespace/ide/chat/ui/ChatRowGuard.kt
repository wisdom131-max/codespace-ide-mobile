package com.codespace.ide.chat.ui

/**
 * OWNER FIX (2026-11-28): the one rule that stops a blank row from painting.
 *
 * A note with no text still drew its background chip, so it reached the screen as a full-width
 * grey bar with nothing in it. The cause was a note source handing over "" instead of null; this
 * guard means no future source can repaint the same bar. Text-bearing rows are untouched.
 */
internal object ChatRowGuard {

    /** True when this row carries no text at all and must not be appended. */
    fun isBlankNote(row: ChatRowModel): Boolean = when (row) {
        is ChatRowModel.InfoNote -> row.text.isBlank()
        is ChatRowModel.AssistantStream -> row.text.isBlank()
        is ChatRowModel.User -> row.text.isBlank()
        else -> false
    }
}

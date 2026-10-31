package com.codespace.ide.agent

/**
 * terminal_selection. The selection lives inside the vendored TerminalView, which only the
 * terminal pane can reach, so the pane publishes a provider and the tool reads through it.
 *
 * Verified 2026-10-31: TerminalView.getSelectedText() exists and returns null when nothing is
 * selected, so this needs no change to the vendored source.
 */
object TerminalSelectionState {

    @Volatile
    private var provider: (() -> String?)? = null

    fun register(read: (() -> String?)?) {
        provider = read
    }

    /** Null means "no terminal view attached"; an empty result means "nothing selected". */
    fun current(): String? {
        val read = provider ?: return null
        return runCatching { read() }.getOrNull()
    }
}

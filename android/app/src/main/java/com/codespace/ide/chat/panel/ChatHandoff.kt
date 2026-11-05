package com.codespace.ide.chat.panel

/**
 * 2026-11-05: one door into the chat, for panels that own no chat reference.
 *
 * The chat panel is already reachable from the editor through `pendingPrompt` in the shell, and the
 * Source Control pane has had a complete "✨ AI commit message" button for months whose handler was
 * a stub ("AI commit messages return with the new chat") because the old provider layer it called
 * was deleted. Threading a callback through ExplorerPane -> SourceControlPane -> CommitInputSection
 * would add a parameter to four signatures for one call site; the shell already owns the prompt
 * channel, so the shell registers here once and the panels ask.
 *
 * Same shape as EditorInsertBus / EditorUndoBus: one sink, honest answers, no silent success.
 */
object ChatHandoff {

    @Volatile private var sink: ((String) -> Unit)? = null

    fun register(fn: (String) -> Unit) { sink = fn }

    fun unregister(fn: (String) -> Unit) {
        if (sink === fn) sink = null
    }

    /** True when a chat panel is mounted and can accept a prompt. */
    fun canAsk(): Boolean = sink != null

    /** Open the chat and send [prompt]. False when there is nowhere to send it. */
    fun ask(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val target = sink ?: return false
        target(prompt)
        return true
    }

    internal fun resetForTest() { sink = null }
}

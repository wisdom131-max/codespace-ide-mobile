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

    /** A caller waiting for the answer to its own question. */
    private class PendingReply(val label: String, val onReply: (String) -> Unit)

    @Volatile private var pending: PendingReply? = null

    /**
     * Open the chat and send [prompt].
     *
     * 2026-11-05 (owner: "add the reply to panel channel"): when [onReply] is given the answer is
     * delivered BACK to the caller - once, then forgotten. That is what turns the Source Control
     * button from "the message appears in the chat, paste it yourself" into an actual round trip.
     * Only one request can be in flight per panel kind; a second ask replaces the first, which is
     * the honest behaviour (the user asked again, so the earlier answer is no longer wanted).
     */
    fun ask(prompt: String, label: String = "", onReply: ((String) -> Unit)? = null): Boolean {
        if (prompt.isBlank()) return false
        val target = sink ?: return false
        pending = onReply?.let { PendingReply(label, it) }
        target(prompt)
        return true
    }

    /**
     * Called by the chat when a turn ends in an ANSWER (never for a tool round, which is not a
     * reply to anyone). Returns true when a waiting caller took it.
     */
    fun deliverAnswer(text: String): Boolean {
        val p = pending ?: return false
        pending = null
        if (text.isBlank()) return false
        p.onReply(text)
        return true
    }

    /** True when some panel is waiting for an answer. */
    fun awaitingReply(): Boolean = pending != null

    internal fun resetForTest() {
        sink = null
        pending = null
    }
}

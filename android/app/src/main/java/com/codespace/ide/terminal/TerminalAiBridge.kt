package com.codespace.ide.terminal

/**
 * I2 — TERMINAL BRIDGE (2026-09-13, VS Code chatTerminalCommandPaste +
 * chatContextService analogs): a tiny global side-channel connecting the
 * interactive terminal with the AI chat panel.
 *
 *  - recordPaste(): called from the terminal paste hook; single-line command-like
 *    pastes surface the "Explain in AI chat" chip (pastedChip is Compose-observable,
 *    so the chip recomposes from anywhere).
 *  - recordRun(): called from AgentTools.runCommand so the LAST agent-executed
 *    command AND its real output are attachable (the "what did that do?" loop).
 *  - transcriptProvider: wired by TerminalPane to the live Termux emulator screen;
 *    transcriptTail() strips ANSI escapes and returns the newest transcript slice.
 */
object TerminalAiBridge {

    /** Compose-observable chip state — the pasted command awaiting an "Explain" tap. */
    val pastedChip = androidx.compose.runtime.mutableStateOf<String?>(null)

    @Volatile var lastPastedCommand: String? = null
        private set
    @Volatile var lastRunCommand: String? = null
        private set
    @Volatile var lastRunOutput: String? = null
        private set

    /** Live transcript access — set by TerminalPane (screen.getTranscriptText()). */
    @Volatile var transcriptProvider: (() -> String?)? = null

    fun recordPaste(text: String) {
        val t = text.trim()
        // Chip only for command-like pastes: single line, not huge.
        if (t.isEmpty() || t.contains('\n') || t.length > 500) {
            pastedChip.value = null
            lastPastedCommand = null
            return
        }
        lastPastedCommand = t
        pastedChip.value = t
    }

    fun recordRun(command: String, output: String) {
        lastRunCommand = command.trim().take(500)
        lastRunOutput = output.take(6000)
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")

    /** Newest terminal transcript slice, ANSI-stripped (or null when no terminal). */
    fun transcriptTail(maxChars: Int = 6000): String? {
        val raw = transcriptProvider?.invoke() ?: return null
        val clean = ANSI.replace(raw, "")
        if (clean.isBlank()) return null
        return if (clean.length > maxChars) clean.takeLast(maxChars) else clean
    }
}

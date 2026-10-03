package com.codespace.ide.util

/**
 * REFRESH-FAMILY (2026-10-03): AI/agent file operations run in non-UI
 * singletons (AgentTools, the chat staged-apply flow) — the Explorer's only
 * reactive refresh channel was TerminalPane's onFileSystemChanged, so
 * AI-created files never triggered a tree re-scan (VS Code parity: the
 * explorer rebuilds on ANY workspace file event; explorerModel.ts has no
 * manual-refresh dependency). This broadcast bridge lets any non-UI writer
 * say "the workspace changed"; PSS bumps the same terminalActivityCounter
 * the terminal path uses, so the Explorer's existing reactive channel does
 * the rest. Listeners always run on the main thread; bursts coalesce into
 * one dispatch (500ms debounce), so an agent writing a batch of files or a
 * staged apply touching many paths costs a single tree re-scan.
 */
object FsChangeNotifier {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var listeners = listOf<() -> Unit>()
    private var dirty = false

    fun addListener(l: () -> Unit) {
        listeners = listeners + l
    }

    fun removeListener(l: () -> Unit) {
        listeners = listeners - l
    }

    /**
     * Announce a workspace file change from a non-UI writer. Safe to call
     * from any thread (agent coroutines included). Debounced: a wave of
     * calls inside 500ms dispatches the listeners once.
     */
    fun notifyChanged() {
        synchronized(this) {
            if (dirty) return
            dirty = true
        }
        main.postDelayed({
            synchronized(this) { dirty = false }
            listeners.toList().forEach { runCatching(it) }
        }, 500)
    }
}

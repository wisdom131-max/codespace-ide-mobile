package com.codespace.ide.debug

import android.content.Context
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.domain.Language

/**
 * P54-DEBUG-DEPS: Installs per-language debugger dependencies in the SAME batch as
 * the language server install (called from LspManager.startServer) instead of
 * lazily deferring them to the first debug-button tap.
 *
 * Trigger semantics (identical to the LSP server check that calls us):
 *  - Fires on the first open of a matching file, only while no healthy LSP server
 *    process exists for that language — the exact same condition under which the
 *    language server itself is checked/installed.
 *  - Subsequent file opens short-circuit at LspManager's "reuse existing healthy
 *    server" check BEFORE any probe runs, so this object is never reached again
 *    while the server stays alive.
 *  - Per-process memo: once ensure() has run for a language (success OR failure)
 *    it never re-probes for the rest of the app session — no re-check on every
 *    file view. A FAILED install is not retried on file opens either; the retry
 *    point is an explicit debug launch (the adapter's launch() self-heal chain).
 *  - Languages without an installable debugger dependency are memoized instantly.
 */
object DebuggerDependencies {

    private const val TAG = "DebuggerDeps"

    /** Per-app-process memo: language name -> already ensured (true on success or failure). */
    private val ensured = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun ensure(context: Context, language: Language) {
        val key = language.name
        if (ensured.containsKey(key)) return

        val depName = when (language) {
            Language.PYTHON -> "debugpy"
            Language.JAVASCRIPT, Language.TYPESCRIPT -> "@vscode/js-debug"
            else -> null
        }
        if (depName == null) {
            // No installable debugger dependency for this language (Kotlin uses the
            // KLS stdlib ensured separately; legacy providers need nothing).
            ensured[key] = true
            return
        }

        AppOutputLog.log(
            "[DEBUG-DEPS] Ensuring debugger dependency for ${language.displayName}: $depName (bundled with LSP install)",
            "lsp")

        val ok = when (language) {
            Language.PYTHON -> {
                val adapter = PythonDAPAdapter()
                if (adapter.isDebugpyInstalled(context)) {
                    AppOutputLog.log("[DEBUG-DEPS] debugpy already installed and healthy — skipping", "lsp")
                    true
                } else {
                    AppOutputLog.log("[DEBUG-DEPS] debugpy missing — installing with LSP batch…", "lsp")
                    adapter.installDebugpy(context)
                }
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                val adapter = NodeDAPAdapter()
                if (adapter.isJsDebugInstalled(context)) {
                    AppOutputLog.log("[DEBUG-DEPS] @vscode/js-debug already installed and healthy — skipping", "lsp")
                    true
                } else {
                    AppOutputLog.log("[DEBUG-DEPS] @vscode/js-debug missing — installing with LSP batch…", "lsp")
                    adapter.installJsDebug(context)
                }
            }
            else -> true // unreachable — memoized above
        }

        if (ok) {
            AppOutputLog.log("[DEBUG-DEPS] Debugger dependencies ready for ${language.displayName}", "lsp")
        } else {
            AppOutputLog.log(
                "[DEBUG-DEPS] WARNING: debugger dependency install FAILED for ${language.displayName} — it will retry at the next debug launch (see output above)",
                "lsp")
        }
        // Mark ensured on success AND failure: never re-check on every file open.
        ensured[key] = true
    }
}

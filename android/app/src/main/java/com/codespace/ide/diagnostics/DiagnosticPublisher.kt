package com.codespace.ide.diagnostics

import android.content.Context
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.domain.Language
import kotlinx.coroutines.launch  // PR11: CoroutineScope.launch is an EXTENSION — import required, member-style call is unresolved

/**
 * Phase P — Bridges existing diagnostic sources into the central DiagnosticManager.
 *
 * LSP diagnostics are wired directly in LspManager (publishDiagnostics handler).
 * This object handles the non-LSP sources: LintChecker (static analysis) and
 * GradleErrorParser (build output).
 *
 * Called from EditorPane when files change, and from BuildPanel when builds complete.
 */
object DiagnosticPublisher {

    // PR11 (P4a-2): lint previously ran SYNCHRONOUSLY ON THE MAIN THREAD three
    // times over the same content — file open, every tab switch, and every
    // save — a full string scan each pass, identical output every time. Two
    // fixes, both honest:
    //  (1) CONTENT-HASH DEDUPE: a file whose content hash matches the last
    //      PUBLISHED lint is skipped outright — the rows are already in the
    //      store, so a re-run with identical input would republish identical
    //      rows. Open/switch/save of UNCHANGED content now costs zero.
    //  (2) BACKGROUND SCAN: the scan itself runs on Dispatchers.Default via
    //      the app-scope worker; DiagnosticManager publishes through its own
    //      mainHandler post, so callers of this method never block the UI
    //      thread on a lint pass again. Single-flight per file: while a lint
    //      is in flight, newer content is parked as the pending request and
    //      re-runs when the worker frees up (never dropped silently).
    private val lastLintHash = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pendingLint = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )

    /** True when the new content is byte-identical to the last PUBLISHED lint for this file. */
    private fun alreadyLinted(filePath: String, content: String): Boolean =
        lastLintHash[filePath] == content.hashCode() && content.hashCode() != 0

    /**
     * Run LintChecker on a file and publish results to DiagnosticManager.
     * Replaces previous lint diagnostics for this file (publish pattern).
     * PR11: dedupes unchanged content and scans off the main thread.
     */
    fun publishLintDiagnostics(filePath: String, content: String, context: Context? = null) {
        if (filePath.isEmpty()) return
        if (alreadyLinted(filePath, content)) return
        pendingLint[filePath] = content
        maybeStartLint(filePath)
    }

    private fun maybeStartLint(filePath: String) {
        val content = pendingLint[filePath] ?: return
        if (!inFlight.add(filePath)) return  // busy: the finally-block below re-checks pending
        scope.launch {
            try {
                val problems = LintChecker.unified(filePath, content)
                val uri = "file://$filePath"
                if (problems.isEmpty()) {
                    // Clear lint diagnostics for this file
                    DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.LINTER, "lintchecker", uri)
                    DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.STATIC_ANALYZER, "lintanalyzer", uri)
                } else {
                    // Convert and publish
                    val diagnostics = DiagnosticConverter.fromLint(problems, filePath, uri, "lintchecker")
                    DiagnosticManager.publishDiagnostics(
                        DiagnosticManager.DiagnosticSource.LINTER, "lintchecker", uri, filePath, diagnostics
                    )
                }
                lastLintHash[filePath] = content.hashCode()
            } catch (_: Exception) {
                // Lint is best-effort diagnostics: a scan failure must not
                // crash the app or poison the dedupe map — leave the old hash.
            } finally {
                inFlight.remove(filePath)
                pendingLint.remove(filePath, content)
                if (pendingLint.containsKey(filePath)) maybeStartLint(filePath)
            }
        }
    }

    /**
     * Publish build/compiler diagnostics from GradleErrorParser output.
     * Uses addDiagnostics (incremental) since build problems accumulate.
     */
    fun publishBuildDiagnostics(problems: List<com.codespace.ide.build.GradleErrorParser.BuildProblem>) {
        if (problems.isEmpty()) return

        // Clear previous build diagnostics first (publish pattern for build)
        // Group by file and publish per-file
        val byFile = problems.groupBy { it.file }
        for ((file, fileProblems) in byFile) {
            val uri = "file://$file"
            val diagnostics = DiagnosticConverter.fromBuildProblems(fileProblems)
            DiagnosticManager.publishDiagnostics(
                DiagnosticManager.DiagnosticSource.BUILD, "gradle", uri, file, diagnostics
            )
        }
    }

    /**
     * F3 (TG03): bridge test failures into the Problems panel under the TEST
     * source (own sourceId "testrun" — never mixed into BUILD/gradle rows).
     * Publish pattern: a passing run clears this file's stale failure rows.
     */
    fun publishTestFailures(
        filePath: String,
        failures: List<Pair<Int, String>>,
    ) {
        val uri = "file://$filePath"
        if (failures.isEmpty()) {
            DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.TEST, "testrun", uri)
            return
        }
        val diagnostics = failures.map { (lineIndex, message) ->
            val line = (lineIndex + 1).coerceAtLeast(1)
            val range = DiagnosticManager.DiagnosticRange(line, 1, line, 1)
            DiagnosticManager.Diagnostic(
                id = DiagnosticManager.computeId(
                    DiagnosticManager.DiagnosticSource.TEST, "testrun", uri,
                    range, DiagnosticManager.Severity.ERROR, null, message
                ),
                source = DiagnosticManager.DiagnosticSource.TEST,
                sourceId = "testrun",
                uri = uri,
                filePath = filePath,
                range = range,
                severity = DiagnosticManager.Severity.ERROR,
                message = message,
                sourceName = "Tests",
            )
        }
        DiagnosticManager.publishDiagnostics(
            DiagnosticManager.DiagnosticSource.TEST, "testrun", uri, filePath, diagnostics
        )
    }

    /**
     * Clear all build diagnostics (e.g. when starting a new build).
     */
    fun clearBuildDiagnostics() {
        DiagnosticManager.clearSource(DiagnosticManager.DiagnosticSource.BUILD, "gradle")
    }

    /**
     * Mark LSP diagnostics stale for a language when its server crashes.
     * Already wired in LspManager.stopServer, but exposed here for external use.
     */
    fun markLspStale(language: Language) {
        DiagnosticManager.markSourceStale(
            DiagnosticManager.DiagnosticSource.LSP, language.name.lowercase()
        )
    }
}

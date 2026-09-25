package com.codespace.ide.diagnostics

import android.content.Context
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.domain.Language

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

    /**
     * Run LintChecker on a file and publish results to DiagnosticManager.
     * Replaces previous lint diagnostics for this file (publish pattern).
     */
    fun publishLintDiagnostics(filePath: String, content: String, context: Context? = null) {
        val uri = "file://$filePath"

        // Run unified lint (LintChecker + LintAnalyzer merged)
        val problems = LintChecker.unified(filePath, content)

        if (problems.isEmpty()) {
            // Clear lint diagnostics for this file
            DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.LINTER, "lintchecker", uri)
            DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.STATIC_ANALYZER, "lintanalyzer", uri)
            return
        }

        // Convert and publish
        val diagnostics = DiagnosticConverter.fromLint(problems, filePath, uri, "lintchecker")
        DiagnosticManager.publishDiagnostics(
            DiagnosticManager.DiagnosticSource.LINTER, "lintchecker", uri, filePath, diagnostics
        )
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

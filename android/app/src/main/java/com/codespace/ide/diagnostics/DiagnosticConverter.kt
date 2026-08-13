package com.codespace.ide.diagnostics

import com.codespace.ide.editor.LintError
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase P — Converts existing diagnostic types (LSP JSONArray, LintError,
 * Problem, BuildProblem) to the unified DiagnosticManager.Diagnostic model.
 *
 * Bridges the old ad-hoc types into the central store without requiring
 * a rewrite of LSP or lint infrastructure.
 */
object DiagnosticConverter {

    /**
     * Convert LSP publishDiagnostics JSONArray to Diagnostic list.
     * LSP line/col are 0-based; Diagnostic uses 1-based for display.
     */
    fun fromLsp(
        diagnostics: JSONArray,
        uri: String,
        filePath: String,
        sourceId: String,
    ): List<DiagnosticManager.Diagnostic> {
        val result = mutableListOf<DiagnosticManager.Diagnostic>()
        for (i in 0 until diagnostics.length()) {
            val diag = diagnostics.optJSONObject(i) ?: continue
            val range = parseLspRange(diag.optJSONObject("range"))
            val severity = parseLspSeverity(diag.optInt("severity", 1))
            val message = diag.optString("message", "LSP diagnostic")
            val code = if (diag.has("code") && !diag.isNull("code")) diag.opt("code")?.toString() else null
            val sourceName = if (diag.has("source") && !diag.isNull("source")) diag.optString("source") else null

            // Related information
            val relatedInfo = mutableListOf<DiagnosticManager.RelatedInfo>()
            val relatedArr = diag.optJSONArray("relatedInformation")
            if (relatedArr != null) {
                for (j in 0 until relatedArr.length()) {
                    val related = relatedArr.optJSONObject(j) ?: continue
                    val relMsg = related.optString("message", "")
                    val relLoc = related.optJSONObject("location")
                    val relUri = relLoc?.optString("uri", "") ?: ""
                    val relFilePath = relUri.substringAfterLast("/")
                    val relLine = (relLoc?.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0) + 1
                    val relCol = (relLoc?.optJSONObject("range")?.optJSONObject("start")?.optInt("character", 0) ?: 0) + 1
                    if (relMsg.isNotEmpty()) {
                        relatedInfo.add(DiagnosticManager.RelatedInfo(relMsg, relUri, relFilePath, relLine, relCol))
                    }
                }
            }

            // Tags
            val tags = mutableListOf<String>()
            val tagsArr = diag.optJSONArray("tags")
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) {
                    when (tagsArr.optInt(j)) {
                        1 -> tags.add("unnecessary")
                        2 -> tags.add("deprecated")
                    }
                }
            }

            // Code description
            val codeDesc = diag.optJSONObject("codeDescription")?.optString("href")

            val id = DiagnosticManager.computeId(
                DiagnosticManager.DiagnosticSource.LSP, sourceId, uri, range, severity, code, message
            )

            result.add(DiagnosticManager.Diagnostic(
                id = id,
                source = DiagnosticManager.DiagnosticSource.LSP,
                sourceId = sourceId,
                uri = uri,
                filePath = filePath,
                range = range,
                severity = severity,
                message = message,
                code = code,
                codeDescription = codeDesc,
                sourceName = sourceName,
                relatedInformation = relatedInfo,
                tags = tags,
            ))
        }
        return result
    }

    /**
     * Convert LintChecker.Problem list to Diagnostic list.
     */
    fun fromLint(
        problems: List<Problem>,
        filePath: String,
        uri: String,
        sourceId: String = "lintchecker",
    ): List<DiagnosticManager.Diagnostic> {
        return problems.map { p ->
            val severity = when (p.severity) {
                Problem.Severity.ERROR -> DiagnosticManager.Severity.ERROR
                Problem.Severity.WARNING -> DiagnosticManager.Severity.WARNING
                Problem.Severity.INFO -> DiagnosticManager.Severity.INFO
            }
            val range = DiagnosticManager.DiagnosticRange(p.line, 1, p.line, 1)
            val id = DiagnosticManager.computeId(
                DiagnosticManager.DiagnosticSource.LINTER, sourceId, uri, range, severity, p.code, p.message
            )
            val related = p.relatedInfo.map { (msg, loc) ->
                val parts = loc.split(":")
                val relLine = parts.firstOrNull()?.toIntOrNull() ?: 1
                val relCol = parts.drop(1).firstOrNull()?.toIntOrNull() ?: 1
                DiagnosticManager.RelatedInfo(msg, "", parts.firstOrNull() ?: "", relLine, relCol)
            }
            DiagnosticManager.Diagnostic(
                id = id,
                source = DiagnosticManager.DiagnosticSource.LINTER,
                sourceId = sourceId,
                uri = uri,
                filePath = filePath,
                range = range,
                severity = severity,
                message = p.message,
                code = p.code,
                sourceName = p.source,
                relatedInformation = related,
            )
        }
    }

    /**
     * Convert LintError (offset-based) to Diagnostic.
     * LintError uses char offsets; we need to convert to line/column.
     */
    fun fromLintErrors(
        errors: List<LintError>,
        fileContent: String,
        filePath: String,
        uri: String,
        sourceId: String = "lintanalyzer",
    ): List<DiagnosticManager.Diagnostic> {
        val lines = fileContent.split("\n")
        fun offsetToLineCol(offset: Int): Pair<Int, Int> {
            var rem = offset
            for ((i, line) in lines.withIndex()) {
                if (rem <= line.length) return (i + 1) to (rem + 1)
                rem -= line.length + 1
            }
            return lines.size.coerceAtLeast(1) to 1
        }
        return errors.map { e ->
            val (startLine, startCol) = offsetToLineCol(e.start)
            val (endLine, endCol) = offsetToLineCol(e.end)
            val severity = when (e.severity) {
                0 -> DiagnosticManager.Severity.HINT
                1 -> DiagnosticManager.Severity.ERROR
                2 -> DiagnosticManager.Severity.WARNING
                else -> DiagnosticManager.Severity.INFO
            }
            val range = DiagnosticManager.DiagnosticRange(startLine, startCol, endLine, endCol)
            val id = DiagnosticManager.computeId(
                DiagnosticManager.DiagnosticSource.STATIC_ANALYZER, sourceId, uri, range, severity, e.code, e.message
            )
            DiagnosticManager.Diagnostic(
                id = id,
                source = DiagnosticManager.DiagnosticSource.STATIC_ANALYZER,
                sourceId = sourceId,
                uri = uri,
                filePath = filePath,
                range = range,
                severity = severity,
                message = e.message,
                code = e.code,
            )
        }
    }

    /**
     * Convert GradleErrorParser.BuildProblem list to Diagnostic list.
     */
    fun fromBuildProblems(
        problems: List<com.codespace.ide.build.GradleErrorParser.BuildProblem>,
    ): List<DiagnosticManager.Diagnostic> {
        return problems.map { p ->
            val severity = when (p.severity) {
                com.codespace.ide.build.GradleErrorParser.Severity.ERROR -> DiagnosticManager.Severity.ERROR
                com.codespace.ide.build.GradleErrorParser.Severity.WARNING -> DiagnosticManager.Severity.WARNING
                com.codespace.ide.build.GradleErrorParser.Severity.INFO -> DiagnosticManager.Severity.INFO
            }
            val uri = "file://${p.file}"
            val range = DiagnosticManager.DiagnosticRange(p.line, p.column, p.line, p.column)
            val id = DiagnosticManager.computeId(
                DiagnosticManager.DiagnosticSource.BUILD, "gradle", uri, range, severity, null, p.message
            )
            DiagnosticManager.Diagnostic(
                id = id,
                source = DiagnosticManager.DiagnosticSource.BUILD,
                sourceId = "gradle",
                uri = uri,
                filePath = p.file,
                range = range,
                severity = severity,
                message = p.message,
                sourceName = p.task.ifEmpty { "gradle" },
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseLspRange(rangeObj: JSONObject?): DiagnosticManager.DiagnosticRange {
        if (rangeObj == null) return DiagnosticManager.DiagnosticRange.EMPTY
        val start = rangeObj.optJSONObject("start")
        val end = rangeObj.optJSONObject("end")
        return DiagnosticManager.DiagnosticRange(
            startLine = (start?.optInt("line", 0) ?: 0) + 1,
            startColumn = (start?.optInt("character", 0) ?: 0) + 1,
            endLine = (end?.optInt("line", 0) ?: 0) + 1,
            endColumn = (end?.optInt("character", 0) ?: 0) + 1,
        )
    }

    private fun parseLspSeverity(severity: Int): DiagnosticManager.Severity {
        return when (severity) {
            1 -> DiagnosticManager.Severity.ERROR
            2 -> DiagnosticManager.Severity.WARNING
            3 -> DiagnosticManager.Severity.INFO
            4 -> DiagnosticManager.Severity.HINT
            else -> DiagnosticManager.Severity.ERROR
        }
    }
}

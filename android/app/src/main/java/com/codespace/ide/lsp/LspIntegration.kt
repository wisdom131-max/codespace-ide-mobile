package com.codespace.ide.lsp

import com.codespace.ide.diagnostics.Problem
import org.json.JSONArray
import org.json.JSONObject

/**
 * P22-G: LSP integration helpers.
 *
 * Converts LSP diagnostics and hover responses into the app's existing data types
 * so they can be displayed alongside the built-in LintChecker results.
 */

/**
 * Converts LSP diagnostics (JSONArray) to Problem objects for the Problems panel.
 * LSP line numbers are 0-based; Problem line numbers are 1-based (add 1).
 * LSP severity: 1=Error, 2=Warning, 3=Info, 4=Hint.
 */
fun lspDiagnosticsToProblems(diagnostics: JSONArray): List<Problem> {
    val problems = mutableListOf<Problem>()
    for (i in 0 until diagnostics.length()) {
        val diag = diagnostics.optJSONObject(i) ?: continue
        val startLine = diag.optJSONObject("range")
            ?.optJSONObject("start")
            ?.optInt("line", 0) ?: 0
        val severity = when (diag.optInt("severity", 1)) {
            1 -> Problem.Severity.ERROR
            2 -> Problem.Severity.WARNING
            else -> Problem.Severity.INFO
        }
        val message = diag.optString("message", "LSP diagnostic")
        val source = diag.optString("source", "")
        val fullMessage = if (source.isNotEmpty()) "[$source] $message" else message
        problems.add(Problem(startLine + 1, severity, fullMessage))
    }
    return problems
}

/**
 * Extracts text content from an LSP Hover response.
 * Hover contents can be:
 *   - A string (plain text)
 *   - A MarkupContent object: { kind: "markdown"|"plaintext", value: "..." }
 *   - An array of strings and MarkupContent objects
 * Returns null if no usable content is found.
 */
fun parseHoverContent(hover: JSONObject): String? {
    val contents = hover.opt("contents") ?: return null
    return when (contents) {
        is String -> contents.ifBlank { null }
        is JSONObject -> contents.optString("value", "").ifBlank { null }
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until contents.length()) {
                val item = contents.opt(i)
                when (item) {
                    is String -> sb.append(item).append('\n')
                    is JSONObject -> sb.append(item.optString("value", "")).append('\n')
                    else -> {}
                }
            }
            val result = sb.toString().trim()
            result.ifBlank { null }
        }
        else -> null
    }
}

/**
 * P22-H: LSP completion item — converted from LSP CompletionItem JSON.
 */
data class LspCompletionItem(
    val label: String,
    val detail: String?,
    val insertText: String,
    val kind: Int,
)

/**
 * Converts LSP CompletionItem array to LspCompletionItem list.
 * Strips snippet placeholders ($1, $2, ${1:default}) from insertText.
 */
fun parseLspCompletions(items: JSONArray): List<LspCompletionItem> {
    val result = mutableListOf<LspCompletionItem>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val label = item.optString("label", "")
        if (label.isBlank()) continue
        var insertText = item.optString("insertText", label)
        insertText = insertText.replace(Regex("""\$\{\d+:?[^}]*}"""), "").replace(Regex("""\$\d+"""), "")
        val detail = item.optString("detail", "")
        val kind = item.optInt("kind", 1)
        result.add(LspCompletionItem(label, detail.ifBlank { null }, insertText, kind))
    }
    return result
}

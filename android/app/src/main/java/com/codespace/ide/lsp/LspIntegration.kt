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

/**
 * P22-J: ImportEdit — represents a single import statement to insert at the top of a file.
 */
data class ImportEdit(
    val importLine: String,   // e.g. "import kotlinx.coroutines.delay"
    val insertAtLine: Int,    // 0-based line index where to insert
)

/**
 * P22-J: Parses LSP CodeAction JSON array and extracts import edits.
 * Looks for WorkspaceEdit -> changes/documentChanges -> TextEdit with import-like text.
 * Returns a list of ImportEdit for the given file URI.
 */
fun parseImportEdits(actions: JSONArray, fileUri: String): List<ImportEdit> {
    val edits = mutableListOf<ImportEdit>()
    for (i in 0 until actions.length()) {
        val action = actions.optJSONObject(i) ?: continue
        val edit = action.optJSONObject("edit") ?: continue
        // Try documentChanges first, then changes
        val docChanges = edit.optJSONArray("documentChanges")
        if (docChanges != null) {
            for (j in 0 until docChanges.length()) {
                val dc = docChanges.optJSONObject(j) ?: continue
                val dcUri = dc.optJSONObject("textDocument")?.optString("uri", "") ?: ""
                if (dcUri != fileUri) continue
                val textEdits = dc.optJSONArray("edits") ?: continue
                extractImportEdits(textEdits, edits)
            }
        } else {
            val changes = edit.optJSONObject("changes") ?: continue
            val textEdits = changes.optJSONArray(fileUri) ?: continue
            extractImportEdits(textEdits, edits)
        }
    }
    return edits.distinctBy { it.importLine }
}

private fun extractImportEdits(textEdits: JSONArray, out: MutableList<ImportEdit>) {
    for (k in 0 until textEdits.length()) {
        val te = textEdits.optJSONObject(k) ?: continue
        val newText = te.optString("newText", "")
        val startLine = te.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
        // Only include edits that look like import statements
        val trimmed = newText.trim()
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ") ||
            trimmed.startsWith("using ") || trimmed.startsWith("#include")) {
            out.add(ImportEdit(trimmed.lines().first(), startLine))
        }
    }
}

/**
 * P22-J: Apply import edits to file content.
 * Inserts missing import lines at the correct positions, deduplicating against existing imports.
 */
fun applyImportEdits(content: String, edits: List<ImportEdit>): String {
    if (edits.isEmpty()) return content
    val lines = content.split("\n").toMutableList()
    // Find the last existing import line to insert after it
    val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") || it.trim().startsWith("from ") }
    val insertIdx = if (lastImportIdx >= 0) lastImportIdx + 1 else
        lines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
    val existing = lines.map { it.trim() }.toSet()
    var offset = 0
    for (edit in edits) {
        if (edit.importLine in existing) continue
        lines.add(insertIdx + offset, edit.importLine)
        offset++
    }
    return lines.joinToString("\n")
}


/**
 * P24: Quick fix / Code action from LSP.
 * Represents a single code action that can be applied to fix a diagnostic.
 */
data class LspCodeAction(
    val title: String,
    val kind: String?,       // e.g. "quickfix", "refactor", "source.organizeImports"
    val edit: String?,       // JSON string of WorkspaceEdit, or null if it's a Command
    val isPreferred: Boolean,
)

/**
 * P24: Parse LSP code actions into LspCodeAction list.
 */
fun parseCodeActions(actions: JSONArray): List<LspCodeAction> {
    val result = mutableListOf<LspCodeAction>()
    for (i in 0 until actions.length()) {
        val action = actions.optJSONObject(i) ?: continue
        val title = action.optString("title", "")
        if (title.isBlank()) continue
        val kind = action.optString("kind").ifBlank { null }
        val edit = action.optJSONObject("edit")?.toString()
        val isPreferred = action.optBoolean("isPreferred", false)
        result.add(LspCodeAction(title, kind, edit, isPreferred))
    }
    return result
}

package com.codespace.ide.lsp

import com.codespace.ide.editor.LintError

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
 *   - A MarkedString object: { language: "...", value: "..." }
 *   - An array of any of the above
 *
 * BUG FIX (P35): The old code used optString("value") which calls toString() on the
 * value field. If the LSP server returns a nested JSONObject as "value" (non-standard
 * but observed from typescript-language-server for some hover responses), optString
 * returns the JSON representation with escaped slashes — producing raw JSON in the
 * tooltip. Fix: use opt("value") and recurse, so nested objects are properly unwrapped.
 */
fun parseHoverContent(hover: JSONObject): String? {
    val contents = hover.opt("contents") ?: return null

    fun extractText(obj: Any?): String? {
        return when (obj) {
            is String -> {
                // P38-FIX: Some LSP servers (notably pylsp) return contents array elements
                // as raw JSON strings like '{"kind":"plaintext","value":"..."}' instead of
                // parsed JSONObjects. Detect and parse these to extract clean text.
                val s = obj.takeIf { it.isNotBlank() } ?: return@extractText null
                if (s.trimStart().startsWith("{") && s.trimEnd().endsWith("}")) {
                    try {
                        val parsed = JSONObject(s)
                        extractText(parsed) ?: s
                    } catch (_: Exception) {
                        s
                    }
                } else {
                    s
                }
            }
            is JSONObject -> {
                // Try "value" first (MarkupContent + MarkedString), then "label" (some servers)
                extractText(obj.opt("value")) ?: extractText(obj.opt("label"))
            }
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until obj.length()) {
                    extractText(obj.opt(i))?.let { sb.append(it).append('\n') }
                }
                sb.toString().trim().ifBlank { null }
            }
            else -> null
        }
    }

    return extractText(contents)
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
 * P24-1: Convert LSP publishDiagnostics JSONArray to LintError list for editor squiggles.
 * Maps LSP line/character positions to character offsets in the file content string.
 */
fun lspDiagnosticsToLintErrors(diagnostics: JSONArray, fileContent: String): List<LintError> {
    val lines = fileContent.split("\n")
    // Pre-compute line start offsets
    val lineOffsets = IntArray(lines.size + 1)
    for (i in lines.indices) {
        lineOffsets[i + 1] = lineOffsets[i] + lines[i].length + 1 // +1 for \n
    }

    val result = mutableListOf<LintError>()
    for (i in 0 until diagnostics.length()) {
        val diag = diagnostics.optJSONObject(i) ?: continue
        val range = diag.optJSONObject("range") ?: continue
        val start = range.optJSONObject("start") ?: continue
        val end = range.optJSONObject("end") ?: continue
        val startLine = start.optInt("line", 0).coerceIn(0, lines.size - 1)
        val startChar = start.optInt("character", 0).coerceIn(0, lines.getOrElse(startLine) { "" }.length)
        val endLine = end.optInt("line", startLine).coerceIn(0, lines.size - 1)
        val endChar = end.optInt("character", startChar + 1).coerceIn(0, lines.getOrElse(endLine) { "" }.length + 1)
        // FIX: LSP can send a diagnostic at the end-of-file position (line = last line,
        // character = 0, meaning "position after the last newline"). This makes
        // startOffset == fileContent.length, and then startOffset+1 > fileContent.length
        // causes coerceIn() to throw IllegalArgumentException ("maximum N is less than
        // minimum N+1"). Clamp startOffset to the last valid character position so the
        // squiggle covers the final character instead of crashing.
        var startOffset = (lineOffsets[startLine] + startChar).coerceIn(0, fileContent.length)
        if (fileContent.isNotEmpty() && startOffset >= fileContent.length) {
            startOffset = fileContent.length - 1
        }
        val endOffset = (lineOffsets[endLine] + endChar).coerceIn(startOffset + 1, fileContent.length)
        val message = diag.optString("message", "LSP diagnostic")
        result.add(LintError(start = startOffset, end = endOffset, message = message))
    }
    return result
}

/**
 * P37-4: Parse LSP codeAction response into List<LspCodeAction> for the context menu.
 * Handles both JSON Object (code action) and JSON String (command title) entries.
 */
fun parseCodeActions(actions: JSONArray): List<LspCodeAction> {
    val result = mutableListOf<LspCodeAction>()
    for (i in 0 until actions.length()) {
        val item = actions.opt(i) ?: continue
        when (item) {
            is JSONObject -> {
                val title = item.optString("title", "Unknown action")
                val kind = if (item.has("kind") && !item.isNull("kind")) item.getString("kind") else null
                val edit = item.optJSONObject("edit")?.toString()
                val command = item.optJSONObject("command")?.toString()
                result.add(LspCodeAction(title, kind, edit, command))
            }
            is String -> {
                result.add(LspCodeAction(title = item))
            }
        }
    }
    return result
}

package com.codespace.ide.lsp

import com.codespace.ide.editor.LintError
import com.codespace.ide.domain.Language

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
        val source = if (diag.has("source") && !diag.isNull("source")) diag.optString("source") else null
        // P41-DIAG: Extract diagnostic code (can be string or number in LSP)
        val code = if (diag.has("code") && !diag.isNull("code")) diag.opt("code")?.toString() else null
        // P41-DIAG: Extract related diagnostics
        val relatedInfo = mutableListOf<Pair<String, String>>()
        val relatedArr = diag.optJSONArray("relatedInformation")
        if (relatedArr != null) {
            for (j in 0 until relatedArr.length()) {
                val related = relatedArr.optJSONObject(j) ?: continue
                val relMsg = related.optString("message", "")
                val relLoc = related.optJSONObject("location")
                val relUri = relLoc?.optString("uri", "")?.substringAfterLast("/") ?: ""
                val relLine = (relLoc?.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0) + 1
                if (relMsg.isNotEmpty()) relatedInfo.add(relMsg to "$relUri:$relLine")
            }
        }
        problems.add(Problem(startLine + 1, severity, message, code = code, source = source, relatedInfo = relatedInfo))
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
    // P41-D: Auto-import support — LSP servers attach import edits here
    val additionalTextEditsJson: String? = null,
    // P41-D: Range-based replacement (some servers use this instead of insertText)
    val textEditJson: String? = null,
    // P41-I: LSP insertTextFormat (1=PlainText, 2=Snippet). When 2, insertText contains $1/$0 syntax.
    val insertTextFormat: Int = 1,
    // P41-K: Resolved documentation (lazily filled by completionItem/resolve)
    val documentation: String? = null,
    // Phase U-2: LSP sortText — server-provided sort priority (lower string = higher priority)
    val sortText: String? = null,
    // Phase U-3: LSP filterText — server-provided text for matching (falls back to label)
    val filterText: String? = null,
    // Phase U-4: LSP command — JSON string of command to execute after applying completion
    val command: String? = null,
    // Phase U-5: LSP commitCharacters — chars that commit the selected completion when typed
    val commitCharacters: List<Char> = emptyList(),
)

/**
 * Phase U-1: Completion response wrapper carrying isIncomplete flag.
 * When isIncomplete=true, the server signals more items may be available on re-request.
 */
data class CompletionResponse(
    val items: List<LspCompletionItem>,
    val isIncomplete: Boolean = false,
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
        val insertTextFormat = item.optInt("insertTextFormat", 1)
        // P41-I: Only strip snippet placeholders for plain-text items.
        // When insertTextFormat == 2 (Snippet), keep $1/$0 syntax for SnippetEngine to parse on accept.
        if (insertTextFormat != 2) {
            insertText = insertText.replace(Regex("\\$\\{\\d+:?[^}]*}"), "").replace(Regex("\\$\\d+"), "")
        }
        val detail = item.optString("detail", "")
        val kind = item.optInt("kind", 1)
        // P41-D: Capture additionalTextEdits (auto-import) and textEdit (range replace)
        val additionalTextEditsJson = item.optJSONArray("additionalTextEdits")?.toString()
        val textEditJson = item.optJSONObject("textEdit")?.toString()
        // Phase U-2: Parse sortText (optional, server-provided sort priority)
        val sortText = item.optString("sortText", "").ifBlank { null }
        // Phase U-3: Parse filterText (optional, used for matching instead of label)
        val filterText = item.optString("filterText", "").ifBlank { null }
        // Phase U-4: Parse command (optional, executed after applying completion)
        val command = item.optJSONObject("command")?.toString()
        // Phase U-5: Parse commitCharacters (optional, JSON array of single-char strings)
        val commitChars = item.optJSONArray("commitCharacters")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                arr.optString(idx, "").takeIf { it.isNotEmpty() }?.firstOrNull()
            }
        } ?: emptyList()
        result.add(LspCompletionItem(label, detail.ifBlank { null }, insertText, kind,
            additionalTextEditsJson, textEditJson, insertTextFormat, null,
            sortText, filterText, command, commitChars))
    }
    return result
}

// ── P41-F: Workspace Symbol Parser ──────────────────────────────────────────

/**
 * Parse a workspace/symbol LSP response (JSONArray of SymbolInformation) into
 * LspCompletionItem list for the completion dropdown.
 */
fun parseWorkspaceSymbols(symbols: JSONArray): List<LspCompletionItem> {
    val result = mutableListOf<LspCompletionItem>()
    for (i in 0 until symbols.length()) {
        val sym = symbols.optJSONObject(i) ?: continue
        val name = sym.optString("name", "")
        if (name.isBlank()) continue
        val symbolKind = sym.optInt("kind", 1)
        val containerName = sym.optString("containerName", "")
        val completionKind = symbolKindToCompletionKind(symbolKind)
        val location = sym.optJSONObject("location")
        val detail = if (location != null) {
            val uri = location.optString("uri", "")
            val range = location.optJSONObject("range")
            val line = range?.optJSONObject("start")?.optInt("line", 0) ?: 0
            val fileName = uri.removePrefix("file://").substringAfterLast("/")
            val container = if (containerName.isNotBlank()) containerName else ""
            if (container.isNotBlank()) "$container · $fileName:${line + 1}" else "$fileName:${line + 1}"
        } else if (containerName.isNotBlank()) {
            containerName
        } else null
        result.add(LspCompletionItem(
            label = name,
            kind = completionKind,
            detail = detail,
            insertText = name,
            additionalTextEditsJson = null,
            textEditJson = null,
        ))
    }
    return result
}

private fun symbolKindToCompletionKind(symbolKind: Int): Int {
    return when (symbolKind) {
        1 -> 17; 2 -> 1; 3 -> 9; 4 -> 7; 5 -> 5; 6 -> 22; 7 -> 10; 8 -> 8
        9 -> 2; 10 -> 3; 11 -> 7; 12 -> 12; 13 -> 11; 14 -> 13; 22 -> 23
        23 -> 20; 24 -> 21; 25 -> 24; 26 -> 25
        else -> 1
    }
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
        val code = if (diag.has("code") && !diag.isNull("code")) diag.opt("code")?.toString() else null
        val severity = diag.optInt("severity", 1)
        result.add(LintError(
            start = startOffset, end = endOffset, message = message, code = code, severity = severity,
            line = startLine, startCol = startChar, endCol = if (startLine == endLine) endChar else -1,
        ))
    }
    return result
}

/**
 * P37-4: Parse LSP codeAction response into List<LspCodeAction> for the context menu.
 * Handles both JSON Object (code action) and JSON String (command title) entries.
 */
/**
 * P39: Standard LSP CodeActionKind constants + client-generated AI action kinds.
 * See https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#codeActionKind
 */
object CodeActionKind {
    const val QuickFix = "quickfix"
    const val QuickFixAll = "quickfix.fixAll"
    const val Refactor = "refactor"
    const val RefactorExtract = "refactor.extract"
    const val RefactorInline = "refactor.inline"
    const val RefactorRewrite = "refactor.rewrite"
    const val RefactorMove = "refactor.move"
    const val Source = "source"
    const val SourceOrganizeImports = "source.organizeImports"
    const val SourceFixAll = "source.fixAll"
    const val SourceRemoveUnused = "source.removeUnused"

    // Client-generated (not part of the LSP spec) — routed through onAiFixRequest instead
    // of edit/command application.
    const val AIExplain = "ai.explain"
    const val AIExplainError = "ai.explainError"
    const val AIGenerateDoc = "ai.generateDoc"
    const val AIGenerateTests = "ai.generateTests"
    const val AIOptimize = "ai.optimize"
    const val AIImprovePerf = "ai.improvePerf"
    const val AIRewrite = "ai.rewrite"
    const val AISimplify = "ai.simplify"
    const val AIRefactor = "ai.refactor"
    const val AIAddComments = "ai.addComments"

    /** Small emoji/glyph shown next to the action title in the dropdown menu. */
    fun icon(kind: String?): String = when {
        kind == null -> "\u2022"
        kind.startsWith("ai.") -> "\u2728"
        kind.startsWith("quickfix") -> "\ud83d\udca1"
        kind.startsWith("refactor") -> "\ud83d\udd27"
        kind.startsWith("source") -> "\ud83d\udce6"
        else -> "\u2022"
    }

    /** Group header label used by categorizeCodeActions() to bucket actions in the menu. */
    fun groupLabel(kind: String?): String = when {
        kind == null -> "Actions"
        kind.startsWith("ai.") -> "AI"
        kind.startsWith("quickfix") -> "Quick Fixes"
        kind.startsWith("refactor") -> "Refactor"
        kind.startsWith("source") -> "Source Actions"
        else -> "Actions"
    }
}

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
                // P39: Extract enhanced fields
                val isPreferred = item.optBoolean("isPreferred", false)
                val disabled = if (item.has("disabled") && !item.isNull("disabled")) {
                    item.optJSONObject("disabled")?.optString("reason", null)
                } else null
                val data = if (item.has("data") && !item.isNull("data")) item.get("data").toString() else null
                val diagnostics = item.optJSONArray("diagnostics")?.toString()
                result.add(LspCodeAction(title, kind, edit, command, isPreferred, disabled, data, diagnostics))
            }
            is String -> {
                result.add(LspCodeAction(title = item))
            }
        }
    }
    return result
}

/**
 * P39: Categorize code actions by their kind prefix for grouped display.
 * Returns an ordered map of group label -> actions.
 */
fun categorizeCodeActions(actions: List<LspCodeAction>): Map<String, List<LspCodeAction>> {
    val groups = LinkedHashMap<String, MutableList<LspCodeAction>>()
    for (action in actions) {
        val label = com.codespace.ide.lsp.CodeActionKind.groupLabel(action.kind)
        groups.getOrPut(label) { mutableListOf() }.add(action)
    }
    // Sort: Quick Fixes first, then Refactor, then Source, then AI
    val order = listOf("Quick Fixes", "Refactor", "Source Actions", "AI", "Actions")
    return groups.entries.sortedBy { e -> order.indexOf(e.key).let { if (it < 0) order.size else it } }
        .associate { it.key to it.value }
}

/**
 * P39: Build a diagnostics JSONArray from lint errors for the code action context.
 * This lets the language server know about existing diagnostics at the target range,
 * enabling targeted quick fixes (e.g. "Fix all" for a specific error type).
 */
fun buildDiagnosticsContext(
    lintErrors: List<com.codespace.ide.editor.LintError>,
    line: Int,
): JSONArray {
    val arr = JSONArray()
    for (err in lintErrors) {
        // LintError has start/end as character offsets — convert to line numbers
        // Check if this error overlaps the target line (0-indexed)
        // line parameter is 0-indexed, err.start/err.end are char offsets in text
        // We can't convert char offsets to lines without the full text, so we
        // include all errors — the server will filter by range
        val diag = JSONObject()
        diag.put("range", JSONObject().apply {
            put("start", JSONObject().apply {
                put("line", line)
                put("character", 0)
            })
            put("end", JSONObject().apply {
                put("line", line)
                put("character", 999)
            })
        })
        diag.put("severity", 1)  // Error
        diag.put("message", err.message)
        diag.put("source", "lint")
        arr.put(diag)
    }
    return arr
}

/**
 * P39: Apply a WorkspaceEdit to the given text content.
 * Handles both documentChanges (LSP 3.16+) and changes (legacy) formats.
 * Returns the new text after applying all edits, or null on failure.
 */
fun applyWorkspaceEdit(
    editJson: String,
    currentText: String,
    currentUri: String? = null,
): String? {
    return try {
        val wsEdit = JSONObject(editJson)
        var newText = currentText

        // Try documentChanges first (preferred format)
        val docChanges = wsEdit.optJSONArray("documentChanges")
        if (docChanges != null) {
            for (i in 0 until docChanges.length()) {
                val dc = docChanges.optJSONObject(i) ?: continue
                val dcUri = dc.optJSONObject("textDocument")?.optString("uri", "") ?: ""
                // If currentUri is provided, only apply edits to the current file
                if (currentUri != null && dcUri != currentUri) continue
                val textEdits = dc.optJSONArray("edits") ?: continue
                newText = applyLspTextEdits(newText, textEdits)
            }
        } else {
            // Legacy changes format (URI -> TextEdit[])
            val changes = wsEdit.optJSONObject("changes")
            if (changes != null) {
                val uri = currentUri ?: changes.keys().next()
                val textEdits = changes.optJSONArray(uri) ?: return null
                newText = applyLspTextEdits(newText, textEdits)
            }
        }
        newText
    } catch (e: Exception) {
        null
    }
}

/**
 * P39: Apply a list of TextEdits to text content.
 * Edits are applied in reverse order (bottom-to-top) to preserve line/character offsets.
 */
public fun applyLspTextEdits(text: String, textEdits: JSONArray): String {
    var result = text
    val edits = (0 until textEdits.length()).mapNotNull { i ->
        textEdits.optJSONObject(i)
    }.sortedByDescending { te ->
        te.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
    }
    for (te in edits) {
        val rng = te.optJSONObject("range") ?: continue
        val startLine = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
        val startChar = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
        val endLine = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
        val endChar = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
        val replacement = te.optString("newText", "")
        val lines = result.split("\n".toRegex()).toMutableList()
        if (startLine == endLine && startLine < lines.size) {
            val line = lines[startLine]
            lines[startLine] = line.substring(0, startChar.coerceAtMost(line.length)) +
                replacement +
                line.substring(endChar.coerceAtMost(line.length))
        } else if (startLine < lines.size) {
            val before = lines[startLine].substring(0, startChar.coerceAtMost(lines[startLine].length))
            val after = if (endLine < lines.size) lines[endLine].substring(endChar.coerceAtMost(lines[endLine].length)) else ""
            lines[startLine] = before + replacement + after
            if (startLine + 1 <= endLine && endLine < lines.size) {
                for (k in endLine downTo startLine + 1) {
                    if (k < lines.size) lines.removeAt(k)
                }
            }
        }
        result = lines.joinToString("\n")
    }
    return result
}




/**
 * P41-Q: Apply a WorkspaceEdit JSON to current file text AND write other-file edits to disk.
 * Returns Pair(newText, appliedAny) — if appliedAny is false, nothing changed.
 * Unlike applyWorkspaceEdit (current-file-only), this also writes edits to other files on disk.
 */
fun applyWorkspaceEditToFilesystem(
    wsEdit: JSONObject,
    currentText: String,
    currentFilePath: String,
): Pair<String, Boolean> {
    var newText = currentText
    var appliedAny = false
    val docChanges = wsEdit.optJSONArray("documentChanges")
    val changes = wsEdit.optJSONObject("changes")
    if (docChanges != null) {
        for (i in 0 until docChanges.length()) {
            val dc = docChanges.optJSONObject(i) ?: continue
            val editUri = dc.optString("uri", "")
            val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
            val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
            val textEdits = dc.optJSONArray("edits") ?: continue
            if (decodedPath == currentFilePath) {
                newText = applyLspTextEdits(newText, textEdits)
                appliedAny = true
            } else {
                try {
                    val targetText = java.io.File(decodedPath).readText()
                    val updated = applyLspTextEdits(targetText, textEdits)
                    java.io.File(decodedPath).writeText(updated)
                } catch (_: Exception) {}
            }
        }
    } else if (changes != null) {
        val keys = changes.keys()
        while (keys.hasNext()) {
            val editUri = keys.next()
            val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
            val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
            val textEdits = changes.optJSONArray(editUri) ?: continue
            if (decodedPath == currentFilePath) {
                newText = applyLspTextEdits(newText, textEdits)
                appliedAny = true
            } else {
                try {
                    val targetText = java.io.File(decodedPath).readText()
                    val updated = applyLspTextEdits(targetText, textEdits)
                    java.io.File(decodedPath).writeText(updated)
                } catch (_: Exception) {}
            }
        }
    }
    return Pair(newText, appliedAny)
}

// ── P41-K: Completion Item Resolver ─────────────────────────────────────────

/**
 * P41-K: Resolve a completion item for richer documentation/detail.
 * Called lazily when the user highlights an item in the dropdown (150ms debounce).
 * Returns a new LspCompletionItem with documentation filled in, or null if resolution fails.
 */
fun resolveCompletionItem(language: Language, item: LspCompletionItem): LspCompletionItem? {
    return try {
        val server = LspManager
        // Reconstruct a minimal JSONObject for the resolve request
        val itemJson = org.json.JSONObject().apply {
            put("label", item.label)
            if (item.detail != null) put("detail", item.detail)
            put("kind", item.kind)
        }
        val resolved = server.resolveCompletion(language, itemJson)
        if (resolved != null) {
            val docs = resolved.opt("documentation")
            val docText = when (docs) {
                is org.json.JSONObject -> docs.optString("value", "")
                is String -> docs
                else -> ""
            }
            val resolvedDetail = resolved.optString("detail", item.detail ?: "")
            LspCompletionItem(
                label = item.label,
                detail = resolvedDetail.ifBlank { item.detail },
                insertText = item.insertText,
                kind = item.kind,
                additionalTextEditsJson = resolved.optJSONArray("additionalTextEdits")?.toString() ?: item.additionalTextEditsJson,
                textEditJson = resolved.optJSONObject("textEdit")?.toString() ?: item.textEditJson,
                insertTextFormat = resolved.optInt("insertTextFormat", item.insertTextFormat),
                documentation = docText.ifBlank { null },
            )
        } else null
    } catch (_: Exception) { null }
}


// ── P41-M: Call Hierarchy Data Classes & Parsers ─────────────────────────────

/**
 * P41-M: Call Hierarchy item — a function/method in the call hierarchy tree.
 */
data class CallHierarchyItem(
    val name: String,
    val detail: String?,
    val kind: Int,          // LSP SymbolKind (12=Function, 6=Method, 2=Module, etc.)
    val uri: String,
    val line: Int,          // 0-based selection range start line
    val character: Int,     // 0-based selection range start character
    val endLine: Int,
    val endCharacter: Int,
    val rawJson: String,    // Original JSON for passing to incoming/outgoing calls
)

/**
 * P41-M: Incoming call — who calls this function.
 */
data class IncomingCall(
    val from: CallHierarchyItem,   // The calling function
    val fromRanges: List<CallRange>, // Where in the caller this is called
)

/**
 * P41-M: Outgoing call — what this function calls.
 */
data class OutgoingCall(
    val to: CallHierarchyItem,      // The called function
    val fromRanges: List<CallRange>, // Where in this function the call happens
)

/**
 * P41-M: A range within a call hierarchy item.
 */
data class CallRange(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
)

/**
 * P41-M: Parse an LSP CallHierarchyItem JSON array into Kotlin data classes.
 */
fun parseCallHierarchyItems(items: JSONArray): List<CallHierarchyItem> {
    val result = mutableListOf<CallHierarchyItem>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val sel = item.optJSONObject("selectionRange") ?: item.optJSONObject("range") ?: continue
        val range = item.optJSONObject("range") ?: sel
        val start = sel.optJSONObject("start") ?: continue
        val end = sel.optJSONObject("end") ?: continue
        result.add(CallHierarchyItem(
            name = item.optString("name", ""),
            detail = item.optString("detail", "").ifBlank { null },
            kind = item.optInt("kind", 12),
            uri = item.optString("uri", ""),
            line = start.optInt("line", 0),
            character = start.optInt("character", 0),
            endLine = end.optInt("line", 0),
            endCharacter = end.optInt("character", 0),
            rawJson = item.toString(),
        ))
    }
    return result
}

/**
 * P41-M: Parse incoming calls from LSP response.
 * Each item: { from: CallHierarchyItem, fromRanges: Range[] }
 */
fun parseIncomingCalls(items: JSONArray): List<IncomingCall> {
    val result = mutableListOf<IncomingCall>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val fromJson = item.optJSONObject("from") ?: continue
        val fromRangesJson = item.optJSONArray("fromRanges") ?: JSONArray()
        val fromItem = parseCallHierarchyItems(JSONArray().put(fromJson)).firstOrNull() ?: continue
        val ranges = mutableListOf<CallRange>()
        for (j in 0 until fromRangesJson.length()) {
            val r = fromRangesJson.optJSONObject(j) ?: continue
            val rs = r.optJSONObject("start")
            val re = r.optJSONObject("end")
            if (rs != null && re != null) {
                ranges.add(CallRange(rs.optInt("line", 0), rs.optInt("character", 0),
                    re.optInt("line", 0), re.optInt("character", 0)))
            }
        }
        result.add(IncomingCall(fromItem, ranges))
    }
    return result
}

/**
 * P41-M: Parse outgoing calls from LSP response.
 * Each item: { to: CallHierarchyItem, fromRanges: Range[] }
 */
fun parseOutgoingCalls(items: JSONArray): List<OutgoingCall> {
    val result = mutableListOf<OutgoingCall>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val toJson = item.optJSONObject("to") ?: continue
        val fromRangesJson = item.optJSONArray("fromRanges") ?: JSONArray()
        val toItem = parseCallHierarchyItems(JSONArray().put(toJson)).firstOrNull() ?: continue
        val ranges = mutableListOf<CallRange>()
        for (j in 0 until fromRangesJson.length()) {
            val r = fromRangesJson.optJSONObject(j) ?: continue
            val rs = r.optJSONObject("start")
            val re = r.optJSONObject("end")
            if (rs != null && re != null) {
                ranges.add(CallRange(rs.optInt("line", 0), rs.optInt("character", 0),
                    re.optInt("line", 0), re.optInt("character", 0)))
            }
        }
        result.add(OutgoingCall(toItem, ranges))
    }
    return result
}

// ── P41-M: Type Hierarchy Data Classes & Parsers ────────────────────────────

/**
 * P41-M: Type Hierarchy item — a class/interface in the type hierarchy tree.
 */
data class TypeHierarchyItem(
    val name: String,
    val detail: String?,
    val kind: Int,          // LSP SymbolKind (5=Class, 11=Interface, 1=File, etc.)
    val uri: String,
    val line: Int,
    val character: Int,
    val endLine: Int,
    val endCharacter: Int,
    val rawJson: String,    // Original JSON for passing to supertypes/subtypes
)

/**
 * P41-M: Parse an LSP TypeHierarchyItem JSON array into Kotlin data classes.
 */
fun parseTypeHierarchyItems(items: JSONArray): List<TypeHierarchyItem> {
    val result = mutableListOf<TypeHierarchyItem>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val sel = item.optJSONObject("selectionRange") ?: item.optJSONObject("range") ?: continue
        val range = item.optJSONObject("range") ?: sel
        val start = sel.optJSONObject("start") ?: continue
        val end = sel.optJSONObject("end") ?: continue
        result.add(TypeHierarchyItem(
            name = item.optString("name", ""),
            detail = item.optString("detail", "").ifBlank { null },
            kind = item.optInt("kind", 5),
            uri = item.optString("uri", ""),
            line = start.optInt("line", 0),
            character = start.optInt("character", 0),
            endLine = end.optInt("line", 0),
            endCharacter = end.optInt("character", 0),
            rawJson = item.toString(),
        ))
    }
    return result
}

/**
 * P41-N: CodeLens data class — parsed from LSP textDocument/codeLens response.
 */
data class CodeLensData(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val title: String,
    val command: String?,
    val arguments: JSONArray?,
    val hasData: Boolean,
    val rawJson: String
)

/**
 * P41-N: Parse a CodeLens JSONArray into a list of CodeLensData.
 */
fun parseCodeLensItems(lenses: JSONArray): List<CodeLensData> {
    val result = mutableListOf<CodeLensData>()
    for (i in 0 until lenses.length()) {
        val lens = lenses.optJSONObject(i) ?: continue
        val range = lens.optJSONObject("range") ?: continue
        val start = range.optJSONObject("start") ?: continue
        val end = range.optJSONObject("end") ?: continue
        val command = lens.optJSONObject("command")
        val title = command?.optString("title", "") ?: lens.optString("title", "")
        val cmd = command?.optString("command", null)
        val args = command?.opt("arguments") as? JSONArray
        val hasData = lens.has("data")
        result.add(CodeLensData(
            startLine = start.optInt("line", 0),
            startChar = start.optInt("character", 0),
            endLine = end.optInt("line", 0),
            endChar = end.optInt("character", 0),
            title = title,
            command = cmd,
            arguments = args,
            hasData = hasData,
            rawJson = lens.toString()
        ))
    }
    return result
}

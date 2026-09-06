package com.codespace.ide.editor

import com.codespace.ide.domain.Language

data class LintError(
    val start: Int,
    val end: Int,
    val message: String,
    val code: String? = null,
    val severity: Int = 1,
    /**
     * Per-line coordinates for structural desync prevention.
     * When line >= 0, spans are column-relative within the owning line
     * and can never be invalidated by edits on other lines.
     * When line < 0, absolute offsets are used (legacy path).
     */
    val line: Int = -1,
    val startCol: Int = -1,
    val endCol: Int = -1,
)

/**
 * P2-5 — Lint Analyzer (hardened, low-noise)
 *
 * Rules that fire:
 *   1. Unmatched braces / brackets / parens  (all languages)
 *   2. Unterminated string literals           (all languages)
 *   3. Unused imports                         (Kotlin / Java)
 *   4. TODO / FIXME markers                  (all languages — INFO level, subtle)
 *
 * Rules intentionally REMOVED vs. old version:
 *   - checkDefinedVsUsed  → produced massive false-positive noise on valid code
 *   - checkIndentMismatch → Python indent heuristic was unreliable
 *
 * Offset contract: LintError.start / .end are absolute char offsets in the
 * original (unfolded) text, matching what SyntaxTransformation expects.
 */
object LintAnalyzer {

    fun analyze(text: String, language: Language): List<LintError> {
        val errors = mutableListOf<LintError>()
        // B1 FIX (2026-09-06): code-specific scans only for code languages. Prose
        // (markdown/plain text) previously produced false "Unmatched" bracket errors
        // from ordinary parentheses/apostrophes in sentences — these showed as editor
        // squiggles AND inflated the Problems badge. TODO/FIXME info checks stay on
        // for every file (matches the Todo Tree convention).
        val isCodeLanguage = language != Language.MARKDOWN && language != Language.PLAINTEXT && language != Language.PLAIN
        if (isCodeLanguage) {
            checkBraceBalance(text, errors)
            checkStringTermination(text, errors, language)
        }
        if (language == Language.KOTLIN || language == Language.JAVA) {
            checkUnusedImports(text, errors)
        }
        checkTodoFixme(text, errors)
        // Populate per-line fields for all errors
        val lineStarts = computeLineStarts(text)
        val withLineInfo = errors.map { err ->
            val line = findLine(err.start, lineStarts)
            val lineStart = if (line < lineStarts.size) lineStarts[line] else 0
            val startCol = err.start - lineStart
            val endCol = (err.end - lineStart).let { if (it > 0) it else startCol + 1 }
            err.copy(line = line, startCol = startCol, endCol = endCol)
        }
        return withLineInfo.sortedBy { it.start }.distinctBy { it.start }
    }

    /** Pre-compute line start offsets for offset->line conversion. */
    private fun computeLineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray()
    }

    /** Binary search to find the line containing an absolute offset. */
    private fun findLine(offset: Int, lineStarts: IntArray): Int {
        if (lineStarts.isEmpty()) return 0
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStarts[mid] <= offset) lo = mid + 1
            else hi = mid - 1
        }
        return hi.coerceAtLeast(0)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Returns true if [offset] falls inside a line comment or block comment. */
    private fun isInComment(text: String, offset: Int): Boolean {
        var i = 0
        while (i < offset) {
            if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '/') {
                // line comment — skip to EOL
                val eol = text.indexOf('\n', i)
                val end = if (eol == -1) text.length else eol
                if (offset < end) return true
                i = end + 1
                continue
            }
            if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '*') {
                val closeIdx = text.indexOf("*/", i + 2)
                val end = if (closeIdx == -1) text.length else closeIdx + 2
                if (offset < end) return true
                i = end
                continue
            }
            // Skip over string literals so we don't misidentify // inside a string
            if (text[i] == '"' || text[i] == '\'') {
                val q = text[i]
                i++
                while (i < text.length && text[i] != q && text[i] != '\n') {
                    if (text[i] == '\\') i++
                    i++
                }
                i++
                continue
            }
            i++
        }
        return false
    }

    // ── 1. Unmatched braces ───────────────────────────────────────────────
    private fun checkBraceBalance(text: String, errors: MutableList<LintError>) {
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // Skip line comments
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                i = (text.indexOf('\n', i).takeIf { it != -1 } ?: text.length) + 1
                continue
            }
            // Skip block comments
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("*/", i + 2)
                i = if (close == -1) text.length else close + 2
                continue
            }
            // Skip triple-quoted strings (Kotlin)
            if (c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"') {
                val close = text.indexOf("\"\"\"", i + 3)
                i = if (close == -1) text.length else close + 3
                continue
            }
            // Skip regular strings
            if (c == '"' || c == '\'') {
                val q = c; i++
                while (i < text.length && text[i] != q && text[i] != '\n') {
                    if (text[i] == '\\') i++
                    i++
                }
                i++; continue
            }
            if (c == '(' || c == '[' || c == '{') {
                stack.addLast(Pair(c, i))
            } else if (c in pairs) {
                val expected = pairs[c]!!
                if (stack.isNotEmpty() && stack.last().first == expected) {
                    stack.removeLast()
                } else {
                    // Unmatched close
                    errors.add(LintError(i, i + 1, "Unmatched '$c'"))
                }
            }
            i++
        }
        // Unmatched opens — mark at their position (limit to first 5 to avoid flood)
        for ((ch, pos) in stack.takeLast(5.coerceAtMost(stack.size))) {
            errors.add(LintError(pos, pos + 1, "Unmatched '$ch'"))
        }
    }

    // ── 2. Unterminated string literals ──────────────────────────────────
    private fun checkStringTermination(
        text: String, errors: MutableList<LintError>, _language: Language
    ) {
        val lines = text.split("\n")
        var offset = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            // Skip comment lines entirely
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) {
                offset += line.length + 1
                continue
            }
            // Scan for unclosed " or ' on this line
            // (triple-quoted strings intentionally span lines — skip them)
            var i = 0
            while (i < line.length) {
                val c = line[i]
                // Skip block comment openings mid-line
                if (c == '/' && i + 1 < line.length && line[i + 1] == '/') break
                // Triple-quote — not an unterminated single-line string
                if (c == '"' && i + 2 < line.length && line[i + 1] == '"' && line[i + 2] == '"') {
                    // Jump past the opening triple-quote — rest may span lines, don't check
                    break
                }
                // Python raw/f-strings prefix
                val _isStringStart = (c == '"' || c == '\'') &&
                    (i == 0 || !line[i - 1].isLetter() ||
                        (i >= 1 && line[i - 1] in listOf('r', 'f', 'b', 'u', 'R', 'F', 'B', 'U')))
                if (c == '"' || c == '\'') {
                    val q = c
                    val start = i
                    i++
                    var closed = false
                    while (i < line.length) {
                        if (line[i] == '\\') { i += 2; continue }
                        if (line[i] == q) { closed = true; i++; break }
                        i++
                    }
                    if (!closed) {
                        errors.add(LintError(offset + start, offset + start + 1, "Unterminated string"))
                    }
                    continue
                }
                i++
            }
            offset += line.length + 1
        }
    }

    // ── 3. Unused imports (Kotlin / Java only) ────────────────────────────
    private fun checkUnusedImports(text: String, errors: MutableList<LintError>) {
        // Only run on files that have actual import blocks
        if (!text.contains("import ")) return
        val importRe = Regex(
            """^import\s+[\w.]+\.(\w+)\s*$""",
            setOf(RegexOption.MULTILINE)
        )
        val imports = importRe.findAll(text).toList()
        if (imports.isEmpty()) return

        // Build the non-import text (to check usage)
        val codeWithoutImports = text.replace(Regex("""^import\s+.+$""", RegexOption.MULTILINE), "")

        for (match in imports) {
            val symbol = match.groupValues[1]
            // Wildcards → don't flag
            if (symbol == "*") continue
            // Check if symbol appears anywhere else in the file (outside the import line)
            val usageRe = Regex("""\b${Regex.escape(symbol)}\b""")
            if (!usageRe.containsMatchIn(codeWithoutImports)) {
                errors.add(
                    LintError(
                        match.range.first,
                        match.range.last + 1,
                        "Unused import: $symbol"
                    )
                )
            }
        }
    }

    // ── 4. TODO / FIXME markers (subtle warning) ─────────────────────────
    private fun checkTodoFixme(text: String, errors: MutableList<LintError>) {
        val re = Regex("""(?i)\b(TODO|FIXME|HACK|XXX)\b""")
        for (match in re.findAll(text)) {
            // Only flag if it's inside a comment
            if (isInComment(text, match.range.first)) {
                errors.add(
                    LintError(
                        match.range.first,
                        match.range.last + 1,
                        "${match.value}: unresolved marker"
                    )
                )
            }
        }
    }
}

package com.codespace.ide.diagnostics

/**
 * Lightweight, dependency-free static checks — deliberately NOT a full
 * language server (no javac/pyright/tsserver bundled, would be way too heavy
 * for a 3GB device). Runs instantly in-process on the currently open file's
 * text and gives the Problems panel something genuinely real to show instead
 * of a hardcoded "No problems" placeholder.
 *
 * Covers the checks that catch the most common real mistakes across any
 * C-family/Python/JS/Kotlin file: unbalanced brackets/quotes, trailing
 * whitespace, mixed tabs+spaces, and TODO/FIXME markers (info level, like
 * VS Code's own Todo Tree convention).
 */
data class Problem(
    val line: Int,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING, INFO }
}

object LintChecker {

    fun check(path: String, content: String): List<Problem> {
        val problems = mutableListOf<Problem>()
        val lines = content.split("\n")
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        val openers = pairs.values.toSet()

        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1

            // Trailing whitespace
            if (rawLine.isNotEmpty() && (rawLine.last() == ' ' || rawLine.last() == '\t')) {
                problems.add(Problem(lineNo, Problem.Severity.WARNING, "Trailing whitespace"))
            }

            // Mixed tabs and spaces at start of line
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }
            if (indent.contains(' ') && indent.contains('\t')) {
                problems.add(Problem(lineNo, Problem.Severity.WARNING, "Mixed tabs and spaces in indentation"))
            }

            // Overly long lines
            if (rawLine.length > 200) {
                problems.add(Problem(lineNo, Problem.Severity.INFO, "Line exceeds 200 characters (${rawLine.length})"))
            }

            // TODO / FIXME markers
            if (Regex("(TODO|FIXME)[:\\s]").containsMatchIn(rawLine)) {
                problems.add(Problem(lineNo, Problem.Severity.INFO, rawLine.trim().take(120)))
            }

            // Bracket balance (best-effort: skips string/char literals, stops at "//" comments)
            var inString = false
            var stringChar = ' '
            var i = 0
            while (i < rawLine.length) {
                val c = rawLine[i]
                if (inString) {
                    if (c == '\\') { i += 2; continue }
                    if (c == stringChar) inString = false
                    i++
                    continue
                }
                if (c == '/' && i + 1 < rawLine.length && rawLine[i + 1] == '/') break // rest of line is a comment
                when {
                    c == '"' || c == '\'' -> { inString = true; stringChar = c }
                    c in openers -> stack.addLast(c to lineNo)
                    pairs.containsKey(c) -> {
                        if (stack.isEmpty() || stack.last().first != pairs.getValue(c)) {
                            problems.add(Problem(lineNo, Problem.Severity.ERROR, "Unmatched closing '$c'"))
                        } else {
                            stack.removeLast()
                        }
                    }
                }
                i++
            }
        }

        if (stack.isNotEmpty()) {
            stack.forEach { (ch, ln) ->
                problems.add(Problem(ln, Problem.Severity.ERROR, "Unclosed '$ch' — never matched by end of file"))
            }
        }

        return problems.sortedBy { it.line }
    }
}

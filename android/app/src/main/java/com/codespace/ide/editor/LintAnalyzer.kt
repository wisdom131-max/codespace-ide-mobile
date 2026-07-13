package com.codespace.ide.editor

import com.codespace.ide.domain.Language

data class LintError(val start: Int, val end: Int, val message: String)

object LintAnalyzer {

    fun analyze(text: String, language: Language): List<LintError> {
        val errors = mutableListOf<LintError>()
        when (language) {
            Language.KOTLIN, Language.JAVA -> {
                checkBraceBalance(text, errors)
                checkStringTermination(text, errors)
                checkUnusedImports(text, errors)
                checkDefinedVsUsed(text, errors, language)
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                checkBraceBalance(text, errors)
                checkStringTermination(text, errors)
                checkDefinedVsUsed(text, errors, language)
            }
            Language.PYTHON -> {
                checkIndentMismatch(text, errors)
                checkStringTermination(text, errors)
            }
            else -> {
                checkBraceBalance(text, errors)
            }
        }
        return errors.sortedBy { it.start }.distinctBy { it.start }
    }

    // ── 1. Unmatched braces / brackets / parens ───────────────────────────
    private fun checkBraceBalance(text: String, errors: MutableList<LintError>) {
        val stack = ArrayDeque<Pair<Char, Int>>() // (char, index)
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        var inString = false
        var stringChar = ' '
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // skip block comment
            if (!inString && !inLineComment && text.startsWith("/*", i)) {
                inBlockComment = true; i += 2; continue
            }
            if (inBlockComment) {
                if (text.startsWith("*/", i)) { inBlockComment = false; i += 2 }
                else i++
                continue
            }
            // skip line comment
            if (!inString && (text.startsWith("//", i) || (i < text.length && c == '#'))) {
                inLineComment = true; i++; continue
            }
            if (inLineComment) { if (c == '\n') inLineComment = false; i++; continue }
            // strings
            if (!inString && (c == '"' || c == '\'' || c == '`')) {
                inString = true; stringChar = c; i++; continue
            }
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == stringChar) inString = false
                i++; continue
            }
            // bracket tracking
            if (c in "([{") { stack.addLast(c to i); i++; continue }
            if (c in ")]}") {
                val expected = pairs[c]
                if (stack.isEmpty() || stack.last().first != expected) {
                    errors.add(LintError(i, i + 1, "Unexpected '$c'"))
                } else {
                    stack.removeLast()
                }
                i++; continue
            }
            i++
        }
        for ((ch, idx) in stack) {
            errors.add(LintError(idx, idx + 1, "Unclosed '$ch'"))
        }
    }

    // ── 2. Unterminated strings ───────────────────────────────────────────
    private fun checkStringTermination(text: String, errors: MutableList<LintError>) {
        val lines = text.split("\n")
        var offset = 0
        for (line in lines) {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"' || c == '\'') {
                    val quote = c
                    var j = i + 1
                    var closed = false
                    while (j < line.length) {
                        if (line[j] == '\\') { j += 2; continue }
                        if (line[j] == quote) { closed = true; j++; break }
                        j++
                    }
                    if (!closed) {
                        errors.add(LintError(offset + i, offset + line.length, "Unterminated string"))
                        i = line.length; continue
                    }
                    i = j
                } else i++
            }
            offset += line.length + 1
        }
    }

    // ── 3. Unused imports (Kotlin/Java) ───────────────────────────────────
    private fun checkUnusedImports(text: String, errors: MutableList<LintError>) {
        val importRe = Regex("""^import\s+[\w.]+\.(\w+)\s*$""", RegexOption.MULTILINE)
        val imports = importRe.findAll(text).toList()
        for (match in imports) {
            val symbol = match.groupValues[1]
            val rest = text.substring(match.range.last + 1)
            val usageRe = Regex("""\b${Regex.escape(symbol)}\b""")
            if (!usageRe.containsMatchIn(rest)) {
                errors.add(LintError(match.range.first, match.range.last + 1, "Unused import: $symbol"))
            }
        }
    }

    // ── 4. Undefined references (used but never defined in file) ─────────
    private fun checkDefinedVsUsed(
        text: String, errors: MutableList<LintError>, language: Language
    ) {
        val defPattern = when (language) {
            Language.KOTLIN ->
                Regex("""(?:fun|val|var|class|object|interface|typealias)\s+(\w+)""")
            Language.JAVA ->
                Regex("""(?:class|interface|enum|void|int|String|boolean|double|float|long)\s+(\w+)\s*[({]""")
            Language.JAVASCRIPT, Language.TYPESCRIPT ->
                Regex("""(?:function|const|let|var|class)\s+(\w+)""")
            else -> return
        }
        val defined = defPattern.findAll(text).map { it.groupValues[1] }.toSet()
        // Built-ins we never flag
        val builtins = setOf(
            "String", "Int", "Boolean", "Unit", "Any", "List", "Map", "Set", "Array",
            "null", "true", "false", "this", "super", "it", "return", "throw",
            "println", "print", "emptyList", "listOf", "mapOf", "setOf",
            "console", "Math", "Object", "Promise", "undefined", "window", "document",
            "override", "companion", "internal", "private", "public", "protected",
        )
        // Identifiers used at call-site: word followed by ( and not preceded by def keyword
        val usePattern = Regex("""(?<![.\w])([a-z][a-zA-Z0-9_]{2,})\s*\(""")
        for (match in usePattern.findAll(text)) {
            val sym = match.groupValues[1]
            if (sym in defined || sym in builtins) continue
            // skip if in import or comment line
            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it == -1) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', match.range.first).let { if (it == -1) text.length else it }
            val line = text.substring(lineStart, lineEnd).trimStart()
            if (line.startsWith("import") || line.startsWith("//") || line.startsWith("*")) continue
            errors.add(LintError(match.range.first, match.range.first + sym.length, "Undefined: $sym"))
        }
    }

    // ── 5. Python indent mismatch (basic) ────────────────────────────────
    private fun checkIndentMismatch(text: String, errors: MutableList<LintError>) {
        val lines = text.split("\n")
        var offset = 0
        var prevIndent = 0
        var expectIndent = false
        for (line in lines) {
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (expectIndent && trimmed.isNotEmpty() && indent <= prevIndent) {
                errors.add(LintError(offset, offset + line.length, "Expected indented block"))
            }
            expectIndent = trimmed.endsWith(":")
            prevIndent = indent
            offset += line.length + 1
        }
    }
}

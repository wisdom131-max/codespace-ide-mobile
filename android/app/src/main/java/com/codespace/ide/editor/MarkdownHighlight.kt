package com.codespace.ide.editor

/**
 * Offline syntax highlighting for Markdown code fences.
 *
 * Why hand-rolled: the preview must work with no network and no CDN, and a full highlighter
 * (highlight.js) is a multi-hundred-kilobyte JS payload for a feature that reads best as a
 * single pass in Kotlin. This produces escaped HTML with `<span class="hl-*">` runs, so the
 * renderer stays the only thing that knows what HTML is.
 *
 * Correctness rules this follows:
 *  - one left-to-right pass, no regex backtracking over the whole document
 *  - every character is escaped exactly once (the emitted spans contain already-escaped text)
 *  - an unterminated string or block comment ends at end-of-input rather than swallowing the rest
 *  - an unknown language still gets plain escaped code, never raw text
 */
object MarkdownHighlight {

    enum class Token { KEYWORD, STRING, COMMENT, NUMBER, FUNCTION }

    private val KOTLIN = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "enum", "sealed", "if", "else",
        "when", "while", "for", "in", "return", "try", "catch", "finally", "throw", "is", "as",
        "null", "true", "false", "package", "import", "private", "public", "internal", "protected",
        "override", "open", "abstract", "companion", "suspend", "inline", "const", "lateinit",
        "by", "init", "this", "super", "typealias", "vararg", "operator", "infix", "reified",
    )
    private val JS = setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "new", "class", "extends", "import", "export", "from", "default",
        "await", "async", "try", "catch", "finally", "throw", "typeof", "instanceof", "delete",
        "null", "undefined", "true", "false", "this", "super", "yield", "of", "in", "static", "get",
        "set", "interface", "type", "enum", "implements", "readonly", "public", "private",
    )
    private val PYTHON = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as",
        "try", "except", "finally", "raise", "with", "lambda", "pass", "break", "continue", "and",
        "or", "not", "in", "is", "None", "True", "False", "global", "nonlocal", "assert", "yield",
        "async", "await", "self",
    )
    private val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
        "return", "export", "local", "readonly", "source", "echo", "cd", "sudo", "apt", "npm", "git",
    )
    private val GENERIC = KOTLIN + JS + PYTHON

    /** Languages whose `#` starts a line comment. */
    private val HASH_COMMENT = setOf("python", "py", "sh", "bash", "shell", "yaml", "yml", "rb", "ruby", "toml", "ini", "conf", "dockerfile")
    /** Languages where a double quote is a string but `'` is an apostrophe (JS/TS). */
    private val SINGLE_QUOTE_STRING = setOf("js", "javascript", "jsx", "ts", "typescript", "tsx", "json", "java", "kotlin", "kt", "c", "cpp", "cs", "go", "rust", "rs", "swift", "dart", "php")

    private fun keywordsFor(lang: String): Set<String> = when (lang.lowercase()) {
        "kt", "kotlin", "kts", "java" -> KOTLIN
        "js", "javascript", "jsx", "ts", "typescript", "tsx", "json" -> JS
        "py", "python" -> PYTHON
        "sh", "bash", "shell", "zsh", "console", "terminal" -> SHELL
        else -> GENERIC
    }

    fun isSupported(lang: String): Boolean =
        lang.lowercase() in setOf(
            "kt", "kotlin", "kts", "java", "js", "javascript", "jsx", "ts", "typescript", "tsx",
            "json", "py", "python", "sh", "bash", "shell", "zsh", "console", "terminal", "sql",
            "yaml", "yml", "toml", "xml", "html", "css", "scss", "c", "cpp", "cs", "go", "rs",
        )

    /**
     * Highlights [code] for [lang]. Returns HTML ready to sit inside `<code>`: all text is
     * escaped, and tokens are wrapped in `<span class="hl-...">`.
     */
    fun highlight(code: String, lang: String): String {
        val keywords = keywordsFor(lang)
        val hashComments = lang.lowercase() in HASH_COMMENT
        val singleQuoted = lang.lowercase() in SINGLE_QUOTE_STRING
        val out = StringBuilder(code.length + 64)
        var i = 0
        while (i < code.length) {
            val c = code[i]

            // Line comments
            if ((hashComments && c == '#') || (!hashComments && c == '/' && code.getOrNull(i + 1) == '/')) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                span(out, Token.COMMENT, escape(code.substring(i, end)))
                i = end
                continue
            }
            // Block comment
            if (c == '/' && code.getOrNull(i + 1) == '*') {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                span(out, Token.COMMENT, escape(code.substring(i, end)))
                i = end
                continue
            }
            // Strings
            if (c == '"' || c == '`' || (c == '\'' && singleQuoted)) {
                val end = stringEnd(code, i, c)
                span(out, Token.STRING, escape(code.substring(i, end)))
                i = end
                continue
            }
            // Numbers
            if (c.isDigit()) {
                var j = i
                while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j] == '_' ||
                            code[j].lowercaseChar() in 'a'..'f' && j > i && (code[i] == '0'))
                ) j++
                span(out, Token.NUMBER, escape(code.substring(i, j)))
                i = j
                continue
            }
            // Identifiers / keywords / call sites
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val next = code.getOrNull(j)
                when {
                    word in keywords -> span(out, Token.KEYWORD, escape(word))
                    next == '(' -> span(out, Token.FUNCTION, escape(word))
                    else -> out.append(escape(word))
                }
                i = j
                continue
            }
            out.append(escape(c.toString()))
            i++
        }
        return out.toString()
    }

    /** End index (exclusive) of the string starting at [start] with quote [quote]. */
    private fun stringEnd(code: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i + 1
            if (c == '\n' && quote != '`') return i   // unterminated: stop at the line break
            i++
        }
        return code.length
    }

    private fun span(out: StringBuilder, token: Token, escaped: String) {
        out.append("<span class=\"hl-").append(token.name.lowercase()).append("\">")
        out.append(escaped)
        out.append("</span>")
    }

    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }
}

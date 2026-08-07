package com.codespace.ide.lsp

import com.codespace.ide.domain.Language

/**
 * P41-V: Context-aware completion suppression and filtering.
 *
 * Analyzes the cursor position in the source text to determine what kind of
 * context the user is in, and whether/how completions should be triggered.
 *
 * This mirrors VS Code's behavior where:
 * - Completions are suppressed inside string literals (except template expressions)
 * - Completions are suppressed inside comments
 * - After ".", only member completions are shown (no keyword mixing)
 * - After "new ", class/constructor completions are boosted
 * - After ":", "extends ", "implements ", "throws ", type completions are boosted
 * - After "return ", "if ", "for ", etc., keyword completions are suppressed
 */
object CompletionContextDetector {

    /**
     * The type of context at the cursor position.
     */
    enum class CompletionContext {
        /** Normal code position — all completion sources active */
        CODE,
        /** Inside a string literal — suppress all completions */
        STRING,
        /** Inside a template expression ${...} inside a string — allow completions */
        TEMPLATE_EXPR,
        /** Inside a comment — suppress all completions */
        COMMENT,
        /** After a dot (.) — member access, LSP-only, no keyword mixing */
        MEMBER_ACCESS,
        /** After "new " keyword — boost class/constructor completions */
        NEW_KEYWORD,
        /** After type annotation (":", "extends", "implements", "throws") — boost types */
        TYPE_CONTEXT,
        /** After "import ", "from ", "package " — path context (handled separately) */
        IMPORT_CONTEXT,
        /** After control-flow keyword (return, if, for, while, etc.) — suppress keywords */
        AFTER_KEYWORD,
        /** Inside function call parentheses — parameter context */
        CALL_ARGUMENTS,
    }

    data class ContextInfo(
        val context: CompletionContext,
        /** Whether completions should be shown at all */
        val shouldShowCompletions: Boolean,
        /** Whether to suppress keyword/buffer completions (only LSP) */
        val lspOnly: Boolean,
        /** LSP CompletionItemKind to boost (0 = no boost) */
        val boostKind: Int,
        /** Extra penalty for non-matching kinds */
        val nonMatchKindPenalty: Float,
    )

    /**
     * Analyze the context at the given cursor position.
     */
    fun detect(
        text: String,
        cursor: Int,
        language: Language,
    ): ContextInfo {
        val pos = cursor.coerceIn(0, text.length)

        // Check if inside string or comment first — these override everything
        val lexicalContext = detectLexicalContext(text, pos, language)
        if (lexicalContext == CompletionContext.STRING) {
            return ContextInfo(CompletionContext.STRING, shouldShowCompletions = false, lspOnly = true, boostKind = 0, nonMatchKindPenalty = 0f)
        }
        if (lexicalContext == CompletionContext.COMMENT) {
            return ContextInfo(CompletionContext.COMMENT, shouldShowCompletions = false, lspOnly = true, boostKind = 0, nonMatchKindPenalty = 0f)
        }
        if (lexicalContext == CompletionContext.TEMPLATE_EXPR) {
            return ContextInfo(CompletionContext.TEMPLATE_EXPR, shouldShowCompletions = true, lspOnly = false, boostKind = 0, nonMatchKindPenalty = 0f)
        }

        // Check the text before cursor for keyword/syntax context
        val beforeCursor = text.substring(0, pos)
        val lastWord = getLastWord(beforeCursor)

        // Dot-triggered: member access
        if (pos > 0 && text.getOrElse(pos - 1) { ' ' } == '.') {
            return ContextInfo(CompletionContext.MEMBER_ACCESS, shouldShowCompletions = true, lspOnly = true, boostKind = 0, nonMatchKindPenalty = 0f)
        }

        // Check for "new " keyword — boost class/constructor kinds
        if (isAfterKeyword(beforeCursor, "new")) {
            // LSP kinds: 7=Class, 9=Constructor, 6=Interface, 22=Struct
            return ContextInfo(CompletionContext.NEW_KEYWORD, shouldShowCompletions = true, lspOnly = false, boostKind = 7, nonMatchKindPenalty = 10f)
        }

        // Type context: after ":", "extends ", "implements ", "throws ", "throw "
        if (isAfterKeyword(beforeCursor, "extends") || isAfterKeyword(beforeCursor, "implements") ||
            isAfterKeyword(beforeCursor, "throws") || isAfterKeyword(beforeCursor, "throw")) {
            // LSP kinds: 8=Interface, 7=Class, 22=Struct, 23=Enum
            return ContextInfo(CompletionContext.TYPE_CONTEXT, shouldShowCompletions = true, lspOnly = false, boostKind = 8, nonMatchKindPenalty = 8f)
        }
        // Kotlin/TypeScript type annotation: "var: " or "fun foo(): "
        if (pos > 0 && text.getOrElse(pos - 1) { ' ' } == ':' && language in listOf(Language.KOTLIN, Language.TYPESCRIPT, Language.JAVA)) {
            return ContextInfo(CompletionContext.TYPE_CONTEXT, shouldShowCompletions = true, lspOnly = false, boostKind = 8, nonMatchKindPenalty = 8f)
        }

        // Import context: after "import ", "from ", "package ", "require("
        if (isAfterKeyword(beforeCursor, "import") || isAfterKeyword(beforeCursor, "from") ||
            isAfterKeyword(beforeCursor, "package") || beforeCursor.trim().endsWith("require(")) {
            return ContextInfo(CompletionContext.IMPORT_CONTEXT, shouldShowCompletions = true, lspOnly = true, boostKind = 0, nonMatchKindPenalty = 0f)
        }

        // After control-flow keywords — suppress keyword suggestions
        val controlFlowKeywords = setOf("return", "if", "else", "for", "while", "when", "switch", "case", "break", "continue", "throw", "try", "catch", "finally", "yield", "await")
        if (lastWord != null && lastWord.lowercase() in controlFlowKeywords) {
            return ContextInfo(CompletionContext.AFTER_KEYWORD, shouldShowCompletions = true, lspOnly = true, boostKind = 0, nonMatchKindPenalty = 0f)
        }

        // Inside function call parentheses — parameter context
        if (isInsideCallParens(text, pos)) {
            // Boost: 6=Variable, 5=Value, 3=Function, 20=EnumMember, 13=Field
            return ContextInfo(CompletionContext.CALL_ARGUMENTS, shouldShowCompletions = true, lspOnly = false, boostKind = 6, nonMatchKindPenalty = 3f)
        }

        // Default: normal code context
        return ContextInfo(CompletionContext.CODE, shouldShowCompletions = true, lspOnly = false, boostKind = 0, nonMatchKindPenalty = 0f)
    }

    // ── Lexical context detection (string/comment) ─────────────

    private fun detectLexicalContext(text: String, pos: Int, language: Language): CompletionContext {
        // Simple state machine: scan from start to pos, tracking string/comment state
        var inString = false
        var stringChar: Char = '"'
        var inComment = false
        var inLineComment = false
        var inTemplateExpr = false
        var templateDepth = 0
        var i = 0

        while (i < pos && i < text.length) {
            val ch = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '

            when {
                inLineComment -> {
                    if (ch == '\n') { inLineComment = false }
                }
                inComment -> {
                    // Block comment: check for */
                    if (ch == '*' && next == '/') { inComment = false; i++ }
                }
                inString -> {
                    if (ch == '\\' && next != '\n') { i++ } // escape
                    else if (ch == stringChar && !isTripleQuote(text, i, stringChar)) { inString = false }
                    else if (language == Language.KOTLIN && ch == '$' && next == '{') {
                        inTemplateExpr = true; templateDepth = 1; i++
                    } else if (language == Language.TYPESCRIPT && ch == '$' && next == '{') {
                        inTemplateExpr = true; templateDepth = 1; i++
                    }
                }
                inTemplateExpr -> {
                    if (ch == '{') templateDepth++
                    else if (ch == '}') { templateDepth--; if (templateDepth == 0) inTemplateExpr = false }
                }
                else -> {
                    when {
                        ch == '/' && next == '/' -> { inLineComment = true; i++ }
                        ch == '/' && next == '*' -> { inComment = true; i++ }
                        ch == '"' || ch == '\'' -> { inString = true; stringChar = ch }
                        // Python triple strings
                        language == Language.PYTHON && ch == '"' && next == '"' && text.getOrElse(i + 2) { ' ' } == '"' -> {
                            inString = true; stringChar = '"'; i += 2
                        }
                        language == Language.PYTHON && ch == '\'' && next == '\'' && text.getOrElse(i + 2) { ' ' } == '\'' -> {
                            inString = true; stringChar = '\''; i += 2
                        }
                        // Kotlin/TS backtick strings
                        (language == Language.KOTLIN || language == Language.TYPESCRIPT) && ch == '`' -> {
                            inString = true; stringChar = '`'
                        }
                    }
                }
            }
            i++
        }

        return when {
            inTemplateExpr -> CompletionContext.TEMPLATE_EXPR
            inString -> CompletionContext.STRING
            inLineComment || inComment -> CompletionContext.COMMENT
            else -> CompletionContext.CODE
        }
    }

    private fun isTripleQuote(text: String, pos: Int, quoteChar: Char): Boolean {
        // Check if this is the end of a triple-quoted string (Python, Kotlin raw)
        if (pos < 2) return false
        return text.getOrElse(pos - 1) { ' ' } == quoteChar && text.getOrElse(pos - 2) { ' ' } == quoteChar
    }

    // ── Keyword detection helpers ──────────────────────────────

    private fun getLastWord(text: String): String? {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return null
        var start = end
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return if (start < end) text.substring(start, end) else null
    }

    private fun isAfterKeyword(text: String, keyword: String): Boolean {
        val trimmed = text.trimEnd()
        // Check if text ends with "keyword " (with space after)
        val pattern = Regex("""\b${keyword}\s*$""")
        return pattern.containsMatchIn(trimmed)
    }

    // ── Call parentheses detection ─────────────────────────────

    private fun isInsideCallParens(text: String, pos: Int): Boolean {
        var depth = 0
        for (i in 0 until pos.coerceAtMost(text.length)) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        return depth > 0
    }
}

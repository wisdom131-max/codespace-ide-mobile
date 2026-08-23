package com.codespace.ide.editor

import com.codespace.ide.domain.Language

/**
 * A bracket pair definition: an opening character, its closing character,
 * and whether it's a quote (which auto-closes to itself).
 */
data class BracketPair(
    val open: Char,
    val close: Char,
    val isQuote: Boolean = false,
    /** Whether auto-close is enabled for this pair. */
    val autoClose: Boolean = true,
    /** Whether skip-over is enabled (jump past existing closer instead of inserting). */
    val skipOver: Boolean = true,
    /** Whether surround-selection is enabled (wrap selected text with this pair). */
    val surround: Boolean = true,
)

/**
 * Per-language bracket pair configuration.
 *
 * Replaces all hardcoded bracket maps throughout CodeEditor.kt with a single
 * configurable source of truth. Each language can define which bracket pairs
 * are active, which auto-close, which support skip-over, and which wrap selections.
 *
 * Usage:
 *   BracketPairConfig.forLanguage(language)  → List<BracketPair>
 *   BracketPairConfig.getCloser(language, '(')  → ')' or null
 *   BracketPairConfig.getPair(language, '(')  → BracketPair or null
 *   BracketPairConfig.isOpener(language, '(')  → true
 *   BracketPairConfig.isCloser(language, ')')  → true
 */
object BracketPairConfig {

    // ── Standard pairs (shared by most languages) ──────────────────
    private val PARENTHESES = BracketPair('(', ')')
    private val BRACKETS = BracketPair('[', ']')
    private val BRACES = BracketPair('{', '}')

    // ── Quote pairs ────────────────────────────────────────────────
    private val DOUBLE_QUOTE = BracketPair('"', '"', isQuote = true)
    private val SINGLE_QUOTE = BracketPair('\'', '\'', isQuote = true)
    private val BACKTICK = BracketPair('`', '`', isQuote = true)

    // ── Language-specific pairs ────────────────────────────────────

    // C-style languages: (), [], {}, "", ''  (no backtick auto-close)
    private val C_STYLE = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Python: (), [], {}, "", ''  (triple-quotes handled by syntax highlighter)
    private val PYTHON = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Rust: (), [], {}, "", ''  (no backtick auto-close)
    private val RUST = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Go: (), [], {}, "", ''  (backtick is raw string, but auto-close could interfere)
    private val GO = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Shell/Bash: (), [], {}, "", ''  (backtick is command substitution)
    private val SHELL = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Ruby: (), [], {}, "", ''  (no backtick auto-close)
    private val RUBY = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Lua: (), [], {}, "", '' 
    private val LUA = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // HTML/XML: < > as a bracket pair (angle brackets), plus quotes
    private val MARKUP = listOf(
        PARENTHESES, BRACKETS, BRACES,
        DOUBLE_QUOTE, SINGLE_QUOTE,
        BracketPair('<', '>', autoClose = false, surround = true),  // < > wraps but doesn't auto-close
    )

    // Markdown: standard brackets plus backtick for code spans
    private val MARKDOWN = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE, BACKTICK)

    // SQL: (), [], {}, "", ''  (single quotes for strings)
    private val SQL = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Plain text: just quotes, no brackets auto-close
    private val PLAIN = listOf(DOUBLE_QUOTE.copy(autoClose = false), SINGLE_QUOTE.copy(autoClose = false))

    // Kotlin/Java/Scala: C-style + backtick (Kotlin uses backtick for identifiers)
    private val KOTLIN_STYLE = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // JavaScript/TypeScript: C-style + backtick (template literals)
    private val JS_STYLE = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE, BACKTICK)

    // PHP: C-style + backtick (shell exec, rarely used)
    private val PHP = listOf(PARENTHESES, BRACKETS, BRACES, DOUBLE_QUOTE, SINGLE_QUOTE)

    // Default (unknown languages): C-style
    private val DEFAULT = C_STYLE

    private val configs: Map<Language, List<BracketPair>> = mapOf(
        Language.JAVASCRIPT to JS_STYLE,
        Language.TYPESCRIPT to JS_STYLE,
        Language.VUE to JS_STYLE,
        Language.SVELTE to JS_STYLE,
        Language.KOTLIN to KOTLIN_STYLE,
        Language.JAVA to KOTLIN_STYLE,
        Language.SCALA to KOTLIN_STYLE,
        Language.CSHARP to C_STYLE,
        Language.SWIFT to C_STYLE,
        Language.DART to C_STYLE,
        Language.CPP to C_STYLE,
        Language.C to C_STYLE,
        Language.PYTHON to PYTHON,
        Language.RUST to RUST,
        Language.GO to GO,
        Language.PHP to PHP,
        Language.SHELL to SHELL,
        Language.RUBY to RUBY,
        Language.LUA to LUA,
        Language.HTML to MARKUP,
        Language.XML to MARKUP,
        Language.CSS to C_STYLE,
        Language.JSON to C_STYLE,
        Language.MARKDOWN to MARKDOWN,
        Language.YAML to C_STYLE,
        Language.TOML to C_STYLE,
        Language.SQL to SQL,
        Language.POWERSHELL to C_STYLE,
        Language.R to C_STYLE,
        Language.PLAINTEXT to PLAIN,
        Language.PLAIN to PLAIN,
    )

    /** Get all bracket pairs for a language. */
    fun forLanguage(language: Language): List<BracketPair> {
        return configs[language] ?: DEFAULT
    }

    /** Get the closing character for an opening character, or null if not a bracket opener. */
    fun getCloser(language: Language, char: Char): Char? {
        val pairs = forLanguage(language)
        for (pair in pairs) {
            if (pair.open == char && pair.autoClose) return pair.close
        }
        return null
    }

    /** Get the BracketPair for a character (either open or close), or null. */
    fun getPair(language: Language, char: Char): BracketPair? {
        val pairs = forLanguage(language)
        for (pair in pairs) {
            if (pair.open == char || pair.close == char) return pair
        }
        return null
    }

    /** Get the BracketPair where the open char matches, or null. */
    fun getPairByOpen(language: Language, char: Char): BracketPair? {
        val pairs = forLanguage(language)
        for (pair in pairs) {
            if (pair.open == char) return pair
        }
        return null
    }

    /** Check if a character is an opening bracket for the given language. */
    fun isOpener(language: Language, char: Char): Boolean {
        val pairs = forLanguage(language)
        return pairs.any { it.open == char }
    }

    /** Check if a character is a closing bracket for the given language. */
    fun isCloser(language: Language, char: Char): Boolean {
        val pairs = forLanguage(language)
        return pairs.any { it.close == char }
    }

    /** Check if a character is any bracket (open or close) for the given language. */
    fun isBracket(language: Language, char: Char): Boolean {
        val pairs = forLanguage(language)
        return pairs.any { it.open == char || it.close == char }
    }

    /** Check if a character is a quote (string delimiter) for the given language. */
    fun isQuote(language: Language, char: Char): Boolean {
        val pairs = forLanguage(language)
        return pairs.any { (it.open == char || it.close == char) && it.isQuote }
    }

    /** Get the matching bracket character for bracket match highlighting. */
    fun getMatchingBracket(language: Language, char: Char): Char? {
        val pairs = forLanguage(language)
        for (pair in pairs) {
            if (pair.open == char) return pair.close
            if (pair.close == char) return pair.open
        }
        return null
    }

    /** Get all bracket chars (open + close) for the highlighter. */
    fun getAllBracketChars(language: Language): Set<Char> {
        val pairs = forLanguage(language)
        return pairs.flatMap { listOf(it.open, it.close) }.toSet()
    }

    /** Check if surround-selection is enabled for a character. */
    fun canSurround(language: Language, char: Char): Boolean {
        val pairs = forLanguage(language)
        return pairs.any { it.open == char && it.surround }
    }
}

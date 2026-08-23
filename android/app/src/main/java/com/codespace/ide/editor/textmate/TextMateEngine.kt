package com.codespace.ide.editor.textmate

import android.content.Context
import android.util.Log
import java.io.InputStreamReader
import java.io.File

/**
 * TextMate engine — the main entry point for TextMate-based syntax highlighting.
 *
 * Written from scratch. Architecture reference: sora-editor's TextMateLanguage +
 * GrammarRegistry + TextMateAnalyzer (LGPL 2.1 — study only).
 *
 * Usage:
 *   val engine = TextMateEngine(context)
 *   engine.loadGrammar("kotlin", "grammars/kotlin.tmLanguage.json")
 *   engine.loadTheme("grammars/dark-plus.tmTheme.json")
 *
 *   // Per-line tokenization with state
 *   var state = TmStateStack.NULL
 *   for (line in lines) {
 *       val result = engine.tokenizeLine("kotlin", line, state)
 *       state = result.newState
 *       // Use result.tokens for highlighting
 *   }
 *
 * Features:
 * - Loads .tmLanguage.json grammars from assets or filesystem
 * - Caches grammars and tokenizers by scope name
 * - Per-line tokenization with state stack for incremental highlighting
 * - Theme-based color mapping (scope names → colors)
 * - Falls back to the existing SyntaxHighlighter when no grammar is available
 */
class TextMateEngine(private val context: Context) {

    companion object {
        private const val TAG = "TextMateEngine"
        private const val GRAMMARS_DIR = "grammars"
    }

    /** Loaded grammars keyed by scope name (e.g., "source.kotlin"). */
    private val grammars = mutableMapOf<String, TmGrammar>()

    /** Tokenizers keyed by scope name. */
    private val tokenizers = mutableMapOf<String, TmTokenizer>()

    /** Mapping from file extension to scope name. */
    private val extensionToScope = mutableMapOf<String, String>()

    /** Current theme (for color mapping). */
    private var theme: TmTheme? = null

    /** Whether the engine has been initialized. */
    private var initialized = false

    /**
     * Initialize the engine by loading bundled grammars from assets.
     * Safe to call multiple times — only loads once.
     */
    fun initialize() {
        if (initialized) return
        initialized = true

        // Load bundled grammars from assets/grammars/
        try {
            val assetFiles = context.assets.list(GRAMMARS_DIR) ?: emptyArray()
            for (file in assetFiles) {
                if (file.endsWith(".tmLanguage.json")) {
                    try {
                        context.assets.open("$GRAMMARS_DIR/$file").use { stream ->
                            val grammar = TmGrammarLoader.load(InputStreamReader(stream))
                            grammars[grammar.scopeName] = grammar
                            tokenizers[grammar.scopeName] = TmTokenizer(grammar)
                            // Register file type extensions
                            for (ext in grammar.fileTypes) {
                                extensionToScope[ext.lowercase()] = grammar.scopeName
                            }
                            Log.d(TAG, "Loaded grammar: ${grammar.scopeName} (${grammar.fileTypes.size} extensions)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load grammar $file", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled grammars directory found", e)
        }
    }

    /**
     * Load a grammar from a filesystem path.
     */
    fun loadGrammarFromPath(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false
            file.reader().use { reader ->
                val grammar = TmGrammarLoader.load(reader)
                grammars[grammar.scopeName] = grammar
                tokenizers[grammar.scopeName] = TmTokenizer(grammar)
                for (ext in grammar.fileTypes) {
                    extensionToScope[ext.lowercase()] = grammar.scopeName
                }
                Log.d(TAG, "Loaded grammar from path: ${grammar.scopeName}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load grammar from $path", e)
            false
        }
    }

    /**
     * Load a theme from a filesystem path.
     */
    fun loadThemeFromPath(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false
            file.reader().use { reader ->
                theme = TmTheme.load(reader)
                Log.d(TAG, "Loaded theme: ${theme?.name}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load theme from $path", e)
            false
        }
    }

    /**
     * Load a theme from a JSON string.
     */
    fun loadThemeFromString(json: String) {
        theme = TmTheme.loadFromString(json)
    }

    /**
     * Get the scope name for a file extension.
     * Returns null if no grammar is loaded for that extension.
     */
    fun scopeForExtension(ext: String): String? {
        return extensionToScope[ext.lowercase()]
    }

    /**
     * Get the scope name for a file path.
     */
    fun scopeForPath(path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty()) scopeForExtension(ext) else null
    }

    /**
     * Check if a grammar is available for the given scope name.
     */
    fun hasGrammar(scopeName: String): Boolean = grammars.containsKey(scopeName)

    /**
     * Tokenize a single line.
     *
     * @param scopeName The grammar's scope name (e.g., "source.kotlin")
     * @param lineText The line text (without trailing newline)
     * @param prevState The state stack from the previous line
     * @return TokenizeResult with tokens and new state, or null if grammar not found
     */
    fun tokenizeLine(
        scopeName: String,
        lineText: String,
        prevState: TmStateStack,
    ): TmTokenizer.TokenizeResult? {
        val tokenizer = tokenizers[scopeName] ?: return null
        return tokenizer.tokenizeLine(lineText, prevState)
    }

    /**
     * Tokenize an entire text (multiple lines).
     * Returns a flat list of tokens with absolute positions.
     */
    fun tokenizeText(
        scopeName: String,
        text: String,
    ): List<TmTokenizer.TmToken>? {
        val tokenizer = tokenizers[scopeName] ?: return null
        val lines = text.split("\n")
        var state = TmStateStack.NULL
        val allTokens = mutableListOf<TmTokenizer.TmToken>()

        for ((lineIdx, line) in lines.withIndex()) {
            val result = tokenizer.tokenizeLine(line, state)
            state = result.newState
            // Convert line-relative positions to absolute positions
            val lineStart = if (lineIdx == 0) 0 else text.indexOf("\n", 0).let { 
                // Compute line start offset
                var offset = 0
                for (i in 0 until lineIdx) {
                    val nextNl = text.indexOf("\n", offset)
                    if (nextNl == -1) break
                    offset = nextNl + 1
                }
                offset
            }
            for (token in result.tokens) {
                allTokens.add(TmTokenizer.TmToken(
                    start = lineStart + token.start,
                    end = lineStart + token.end,
                    scopes = token.scopes,
                ))
            }
        }

        return allTokens
    }

    /**
     * Get the current theme.
     */
    fun getTheme(): TmTheme? = theme

    /**
     * Get all loaded scope names.
     */
    fun getLoadedScopes(): Set<String> = grammars.keys.toSet()

    /**
     * Check if any grammars are loaded.
     */
    fun hasAnyGrammars(): Boolean = grammars.isNotEmpty()
}

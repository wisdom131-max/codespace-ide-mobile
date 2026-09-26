package com.codespace.ide.editor.textmate

import android.content.Context
import android.util.Log
import java.io.InputStreamReader

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

    /** Loaded grammars keyed by scope name (e.g., "source.kotlin").
     *  TM04 (2026-09-26): the singleton is read from tokenize threads while
     *  initialize() may run on main — these are now ConcurrentHashMaps. */
    private val grammars = java.util.concurrent.ConcurrentHashMap<String, TmGrammar>()

    /** Tokenizers keyed by scope name. */
    private val tokenizers = java.util.concurrent.ConcurrentHashMap<String, TmTokenizer>()

    /** Mapping from file extension to scope name. */
    private val extensionToScope = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Current theme (for color mapping). TM04: volatile — set once at initialize. */
    @kotlin.jvm.Volatile
    private var theme: TmTheme? = null

    /** Whether the engine has been initialized. */
    private var initialized = false

    /**
     * Initialize the engine by loading bundled grammars from assets.
     * Safe to call multiple times — only loads once.
     */
    @kotlin.jvm.Synchronized
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

        // TM03 (2026-09-26): the theme pipeline was DEAD — dark-plus.tmTheme.json
        // shipped in assets but no code ever loaded it, so getTheme() always
        // returned null and consumers silently fell back to the palette. The
        // bundled Dark+ theme is now the ONE theme source, loaded here.
        try {
            context.assets.open("$GRAMMARS_DIR/dark-plus.tmTheme.json").use { stream ->
                theme = TmTheme.load(InputStreamReader(stream))
                Log.d(TAG, "Loaded theme: ${theme?.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled theme missing — palette fallback in effect", e)
        }
    }

    // TM02 (2026-09-26): loadGrammarFromPath is DELETED — a zero-caller, LATENT
    // untrusted-grammar API (it compiled arbitrary filesystem joni patterns and
    // could shadow bundled grammars). If an extension system ever needs it
    // (XG01-04 remain owner-gated), it must come back behind a TrustState gate
    // with scoped grammar validation — not as a free public API.
    //
    // TM03: loadThemeFromPath / loadThemeFromString are DELETED too — also zero
    // callers. The bundled Dark+ theme (loaded in initialize) is the one theme
    // source; the palette fallback covers everything it does not map.

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

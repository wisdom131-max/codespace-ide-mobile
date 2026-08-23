package com.codespace.ide.editor.textmate

import android.content.Context
import com.codespace.ide.editor.ProjectSettingsStore

/**
 * Singleton holder for the TextMateEngine — initialized once with app context.
 *
 * Written from scratch. Provides a single shared TextMateEngine instance
 * that can be accessed from SyntaxHighlighter and SyntaxTransformation
 * without passing it through every composable.
 */
object TextMateEngineHolder {

    @Volatile
    private var engine: TextMateEngine? = null

    @Volatile
    private var initialized = false

    /**
     * Get the shared TextMateEngine, initializing it if needed.
     * Thread-safe — safe to call from any thread.
     */
    fun get(context: Context): TextMateEngine {
        if (engine == null || !initialized) {
            synchronized(this) {
                if (engine == null || !initialized) {
                    engine = TextMateEngine(context.applicationContext)
                    engine!!.initialize()
                    initialized = true
                }
            }
        }
        return engine!!
    }

    /**
     * Get the engine if already initialized, or null.
     * Use this in composables where you don't have a Context.
     */
    fun getIfInitialized(): TextMateEngine? = engine

    /**
     * Check if TextMate highlighting is enabled and the engine has grammars loaded.
     */
    fun isActive(): Boolean {
        if (!ProjectSettingsStore.textMateHighlightingEnabled.value) return false
        val e = engine ?: return false
        return e.hasAnyGrammars()
    }
}

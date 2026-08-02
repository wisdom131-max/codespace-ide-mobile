package com.codespace.ide.lsp

import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P37-3fix: Shared cache for LSP documentSymbol results.
 * Prevents duplicate textDocument/documentSymbol requests when both
 * EditorPane and OutlinePanel need the same file's symbols.
 *
 * EditorPane writes to this cache after fetching. OutlinePanel reads
 * from it first, and only fetches independently if the cache is empty
 * or stale (different file or older than 5 seconds).
 */
object DocumentSymbolCache {
    private val mutex = Mutex()

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var cachedSymbols: JSONArray? = null

    @Volatile
    private var cachedAt: Long = 0L

    private val STALE_MS = 5_000L  // 5 seconds

    /** Called by EditorPane after fetching document symbols from LSP. */
    suspend fun put(filePath: String, symbols: JSONArray?) {
        mutex.withLock {
            cachedPath = filePath
            cachedSymbols = symbols
            cachedAt = System.currentTimeMillis()
        }
    }

    /**
     * Called by OutlinePanel. Returns cached symbols if available and fresh.
     * Returns null if cache miss (caller should fetch independently).
     */
    suspend fun get(filePath: String): JSONArray? {
        return mutex.withLock {
            if (cachedPath == filePath && cachedSymbols != null) {
                val age = System.currentTimeMillis() - cachedAt
                if (age < STALE_MS) cachedSymbols else null
            } else null
        }
    }

    /** Clear cache when file changes significantly. */
    suspend fun invalidate(filePath: String) {
        mutex.withLock {
            if (cachedPath == filePath) {
                cachedPath = null
                cachedSymbols = null
                cachedAt = 0L
            }
        }
    }
}

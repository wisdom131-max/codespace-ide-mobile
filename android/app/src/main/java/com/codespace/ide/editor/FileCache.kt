package com.codespace.ide.editor

import java.io.File
import java.util.LinkedHashMap

/**
 * P9-2: Smart file caching — LRU cache for recently opened files.
 * Avoids re-reading from disk on tab switches and file reopens.
 * Thread-safe, max 20 files, invalidates on write.
 */
object FileCache {

    private const val MAX_ENTRIES = 20
    private const val LARGE_FILE_THRESHOLD = 1_048_576  // 1MB

    data class CachedFile(
        val content: String,
        val sizeBytes: Int,
        val lastModified: Long,
        val isLargeFile: Boolean,
    )

    private val cache = object : LinkedHashMap<String, CachedFile>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CachedFile>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val lock = Any()

    /**
     * Get file content from cache, or read from disk if not cached or stale.
     * Returns content + metadata about whether it's a large file.
     */
    fun get(path: String): CachedFile {
        synchronized(lock) {
            val file = File(path)
            val lastMod = file.lastModified()
            cache[path]?.let { cached ->
                if (cached.lastModified == lastMod) {
                    return cached  // cache hit
                }
            }
            // Cache miss or stale — read from disk
            val size = file.length().toInt()
            val isLarge = size > LARGE_FILE_THRESHOLD
            val content = try {
                file.readText()
            } catch (e: Exception) {
                "// Could not read file: ${e.message}"
            }
            val cached = CachedFile(content, size, lastMod, isLarge)
            cache[path] = cached
            return cached
        }
    }

    /** Invalidate a single file's cache entry (call after writing). */
    fun invalidate(path: String) {
        synchronized(lock) {
            cache.remove(path)
        }
    }

    /** Clear all cached files. */
    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    /** Check if a file exceeds the large file threshold without loading it. */
    fun isLargeFile(path: String): Boolean {
        return File(path).length() > LARGE_FILE_THRESHOLD
    }

    /** Get cache stats for the status bar / debug. */
    fun stats(): String {
        synchronized(lock) {
            val totalSize = cache.values.sumOf { it.sizeBytes }
            return "${cache.size} files, ${totalSize / 1024}KB cached"
        }
    }
}

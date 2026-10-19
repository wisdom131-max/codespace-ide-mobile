package com.codespace.ide.terminal

import java.util.concurrent.ConcurrentHashMap

/**
 * BATCH11-S3 (advisor approved design item 3): ONE small cache of recent
 * existence checks, positive AND negative, short TTL, cleared when a terminal
 * command finishes — and ONE fresh re-check on a miss before the resolver
 * reports "not found" (a file created a moment earlier must not be lost to a
 * stale negative entry).
 *
 * Deliberately NO directory-listing cache (advisor ruling: listing a big folder
 * on shared storage can cost more than it saves — revisit only with
 * measurements).
 *
 * Thread-safe: the resolver probes on Dispatchers.IO; clearAll() fires from the
 * shell-integration reader thread (COMMAND_COMPLETE).
 */
object LinkExistenceCache {

    /** Short TTL: a few seconds, per the approved design. */
    const val TTL_MS = 4_000L

    /** Small: one cache for the whole app, bounded. */
    const val MAX_ENTRIES = 256

    data class Entry(val exists: Boolean, val directory: Boolean, val recordedAtMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** Called when a terminal command finishes (ShellIntegration COMMAND_COMPLETE). */
    @JvmStatic
    fun clearAll() {
        map.clear()
    }

    private fun record(path: String, exists: Boolean, directory: Boolean) {
        if (map.size >= MAX_ENTRIES && !map.containsKey(path)) {
            // Cheap bound: on overflow drop the whole cache rather than tracking
            // LRU order — entries are seconds-old anyway.
            map.clear()
        }
        map[path] = Entry(exists, directory, System.currentTimeMillis())
    }

    private fun freshExists(probe: LinkFileProbe, path: String): Boolean {
        val ex = probe.exists(path)
        record(path, ex, if (ex) probe.isDirectory(path) else false)
        return ex
    }

    /**
     * Cached existence. A POSITIVE entry inside the TTL short-circuits (zero I/O).
     * A NEGATIVE (or stale) entry NEVER decides alone: exactly ONE fresh probe
     * re-checks before the caller may treat the path as missing.
     */
    fun exists(probe: LinkFileProbe, path: String): Boolean {
        val e = map[path]
        val now = System.currentTimeMillis()
        if (e != null && e.exists && now - e.recordedAtMs < TTL_MS) return true
        return freshExists(probe, path)
    }

    /** Cached kind. Only meaningful after a positive exists(); re-probes when stale. */
    fun isDirectory(probe: LinkFileProbe, path: String): Boolean {
        val e = map[path]
        val now = System.currentTimeMillis()
        if (e != null && e.exists && now - e.recordedAtMs < TTL_MS) return e.directory
        return freshExists(probe, path) && probe.isDirectory(path)
    }

    /** Test hook: number of cached entries. */
    fun size(): Int = map.size
}

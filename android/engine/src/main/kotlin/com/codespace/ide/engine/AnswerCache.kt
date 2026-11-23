package com.codespace.ide.engine

/**
 * PROJECT NINE 9.16 (catalog 88) - answer an identical question without paying for it twice.
 *
 * The duplicate-send guard stops the same request being fired twice at once. It cannot help with
 * the same question asked again a minute later, which is the common case when a user re-opens a
 * session.
 *
 * The dangerous part of a cache is serving an answer the project has moved past, so every entry
 * carries a stamp from the caller - a file-set version, a project id, anything that changes when
 * the world does. A stamp mismatch is a miss AND an eviction, so stale answers do not linger.
 */
object AnswerCache {

    data class Entry(val answer: String, val stamp: String, val atMs: Long)

    class Cache(private val maxEntries: Int = 32) {

        private val entries = LinkedHashMap<String, Entry>()

        var hits: Int = 0
            private set
        var misses: Int = 0
            private set

        fun put(request: String, modelId: String, stamp: String, answer: String, atMs: Long) {
            val key = SemanticCacheKey.of(request, modelId)
            entries.remove(key)
            entries[key] = Entry(answer, stamp, atMs)
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }

        fun get(request: String, modelId: String, stamp: String): String? {
            val key = SemanticCacheKey.of(request, modelId)
            val entry = entries[key]
            if (entry == null || entry.stamp != stamp) {
                if (entry != null) entries.remove(key)
                misses += 1
                return null
            }
            // Refresh recency so a repeatedly asked question is not evicted by one-off noise.
            entries.remove(key)
            entries[key] = entry
            hits += 1
            return entry.answer
        }

        fun size(): Int = entries.size
    }

    /**
     * One cache for the process, so the engine can consult it without a new constructor argument.
     * Tests keep their own instance.
     */
    val shared = Cache()
}

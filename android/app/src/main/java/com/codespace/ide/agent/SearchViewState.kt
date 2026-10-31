package com.codespace.ide.agent

/**
 * get_search_view_results. The search view keeps its hits in composable state, where no tool
 * can see them. This is the one place the view publishes what it found, so a tool can read the
 * SAME results instead of running a second, silently divergent search.
 *
 * In memory only, and deliberately so: search hits are a view of the current session's work,
 * not something to persist or to report as fresh after a restart.
 */
object SearchViewState {

    data class Hit(val file: String, val line: Int, val text: String)

    data class Snapshot(val query: String, val hits: List<Hit>, val truncated: Boolean, val atMs: Long)

    /** The view caps at 500 files; 200 hits is enough for a tool answer and keeps the payload sane. */
    private const val MAX_HITS = 200

    @Volatile
    private var snapshot: Snapshot? = null

    fun publish(query: String, hits: List<Hit>, nowMs: Long = System.currentTimeMillis()) {
        val capped = hits.take(MAX_HITS)
        snapshot = Snapshot(
            query = query,
            hits = capped,
            truncated = hits.size > capped.size,
            atMs = nowMs,
        )
    }

    fun current(): Snapshot? = snapshot

    fun clear() {
        snapshot = null
    }
}

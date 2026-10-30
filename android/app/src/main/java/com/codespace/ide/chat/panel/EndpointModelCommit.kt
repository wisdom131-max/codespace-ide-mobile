package com.codespace.ide.chat.panel

import com.codespace.ide.chat.CustomEndpointStore

/**
 * P0-A / P0-B (U9 follow-up, 2026-10-30): the ONE place the endpoint editor writes model lists.
 *
 * Two data-integrity rules live here, once, so the dialog cannot get them wrong:
 *
 * 1. DISCOVERY NEVER PERSISTS. A fetch only fills editor state; this commit runs on Save. So
 *    Cancel discards discovery results, and a fetch cannot change the live cache behind the
 *    user's back. (VS Code comparison: its Language Models configuration service exposes only
 *    add/update/remove and has no fetch write path at all.)
 * 2. A FAILED READ IS NOT AN EMPTY LIST. When the stored manual list could not be read, the
 *    commit is refused rather than writing an assumed-empty list over ids we never saw.
 *    `setManualModels` REPLACES the row, so an empty write is a destructive write.
 *
 * Kept free of Compose and of Android types so it is unit-testable with the memory storage seam.
 */
object EndpointModelCommit {

    data class Result(
        /** true when the manual row was written (or there was nothing to write). */
        val manualSaved: Boolean,
        /** true when the live cache was written, false when that write failed. */
        val liveSaved: Boolean,
        /** true when the commit was skipped because the initial read never resolved. */
        val skipped: Boolean,
    )

    /** Save is only allowed when the manual list is KNOWN (a real read, possibly empty). */
    fun canSave(manual: CustomEndpointStore.ModelRead): Boolean =
        manual is CustomEndpointStore.ModelRead.Loaded

    /**
     * Writes the manual list always, and the discovered/live list only when this session's fetch
     * produced ids. An empty [fetched] means "nothing new was discovered", so the existing live
     * cache is left alone - that is what keeps cached models usable offline.
     */
    fun commit(
        store: CustomEndpointStore,
        id: String,
        manual: List<String>,
        fetched: List<String>,
        readWasUnresolved: Boolean,
    ): Result {
        if (readWasUnresolved) return Result(manualSaved = false, liveSaved = false, skipped = true)
        val manualOk = store.setManualModels(id, manual.joinToString("\n"))
        if (!manualOk) return Result(manualSaved = false, liveSaved = false, skipped = false)
        if (fetched.isEmpty()) return Result(manualSaved = true, liveSaved = true, skipped = false)
        val liveOk = try { store.setLiveModels(id, fetched) } catch (_: Exception) { false }
        return Result(manualSaved = true, liveSaved = liveOk, skipped = false)
    }
}

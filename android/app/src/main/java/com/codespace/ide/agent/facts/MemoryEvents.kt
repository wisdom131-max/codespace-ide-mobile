package com.codespace.ide.agent.facts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * STAGE 1 (2026-11-19) — ONE signal that memory changed, so the Facts panel cannot go stale.
 *
 * The panel used to own a private reload counter that only its own edit/add/delete handlers bumped.
 * A fact saved by Copilot while the panel was open therefore did not appear until something else
 * happened to recreate the screen: the store was correct and the view was a snapshot.
 *
 * The rule that makes this safe is WHO publishes: only [FactsStore] after a write that actually
 * landed. A refused save, a full store or a failed write publishes nothing, so the panel can never
 * show a "saved" state for a fact that is not on disk.
 *
 * Deliberately a process-wide object with no lifecycle: it holds one Int, it costs nothing to keep,
 * and it is already correct after process death because every reader re-reads the file.
 */
object MemoryEvents {

    private val _revision = MutableStateFlow(0)

    /** Monotonic count of successful persistence operations since process start. */
    val revision: StateFlow<Int> = _revision

    /** Called ONLY after a write returned success. */
    fun publish() {
        _revision.update { it + 1 }
    }
}

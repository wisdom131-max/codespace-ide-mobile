package com.codespace.ide.debug

/**
 * BATCH7-P3 (advisor 6b, 2026-10-18, device evidence): the last PAUSED snapshot
 * (stack + variables) lives HERE — OUTSIDE any collapsible section or composable —
 * so it is the single durable record of "what was on screen when the program
 * stopped". The owner's repro: after a pause the VARIABLES panel showed nothing
 * until the section dropdown was tapped, and after collapse+expand it showed
 * nothing again. READ (VariableInspectorPanel.kt): pausedVars/pausedStack were
 * `remember` state declared INSIDE the `if (section in expandedSections)` block,
 * so collapsing the section destroyed the state AND unregistered the paused
 * listener (DisposableEffect onDispose) — events while collapsed were missed, and
 * a pause that happened before the panel was composed never appeared at all.
 *
 * Semantics (owner requirement):
 *  - record() on every pause (UDM.notifyPaused stores BEFORE firing listeners);
 *  - a step records the NEW values (replaces);
 *  - any non-PAUSED state transition for that session (continue/step-out/stop/
 *    crash/fail) clears it — UDM.transitionState calls clear();
 *  - snapshots are keyed by sessionId: a NEW session never sees the PREVIOUS
 *    session's variables (currentFor(sessionId) filters stale snapshots);
 *  - pure Kotlin, no Android imports — JVM-testable (DebugPauseStoreTest).
 */
object DebugPauseStore {

    data class Snapshot(
        val sessionId: String,
        val stack: List<DebugStackFrame>,
        val vars: List<DebugVariable>,
        val atMs: Long = System.currentTimeMillis(),
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** UDM.notifyPaused calls this BEFORE firing paused listeners. */
    fun record(sessionId: String, stack: List<DebugStackFrame>, vars: List<DebugVariable>) {
        snapshot = Snapshot(sessionId, stack, vars)
    }

    /** Cleared on any non-PAUSED transition of the SAME session (never touches another session's). */
    fun clear(sessionId: String) {
        val s = snapshot
        if (s != null && s.sessionId == sessionId) snapshot = null
    }

    fun clearAll() {
        snapshot = null
    }

    fun current(): Snapshot? = snapshot

    /** The snapshot for THIS session only — a different session never inherits values. */
    fun currentFor(sessionId: String?): Snapshot? {
        val s = snapshot ?: return null
        return if (sessionId != null && s.sessionId == sessionId) s else null
    }
}

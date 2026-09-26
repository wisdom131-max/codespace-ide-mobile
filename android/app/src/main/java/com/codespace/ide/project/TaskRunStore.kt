package com.codespace.ide.project

import android.content.Context

/**
 * PR13 (P4a-2): durable TaskRunner run state.
 *
 * WAS: TaskRunner's runs map lived only in memory, so the app dying mid-build
 * lost every run state — and worse, a UI-scope cancellation (the task panel's
 * composition scope) could leave a RUNNING entry forever in the process-lifetime
 * singleton while nothing could ever clear it. This store persists the LAST
 * KNOWN state per task so a fresh process restores it; the startup sweep in
 * [TaskRunner.restorePersistedState] converts any persisted RUNNING (the app
 * died mid-run) into FAILED with an honest interruption message. A possibly
 * orphaned gradle process inside proot is NOT cleaned here — that is the
 * TP06/DG13 family, tracked separately.
 *
 * Storage: plain SharedPreferences (state name + timestamp per task). The full
 * BuildResult (output) is intentionally NOT persisted — only tile state is.
 */
object TaskRunStore {

    private const val PREFS = "taskrun_state"

    @Volatile private var prefs: Context? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext
    }

    private fun enabled(): Boolean = prefs != null

    fun saveState(id: TaskRunner.TaskId, state: TaskRunner.RunState, atMs: Long) {
        if (!enabled()) return
        try {
            val p = prefs!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (state == TaskRunner.RunState.IDLE) {
                p.edit().remove(id.name).apply()
            } else {
                p.edit().putString(id.name, state.name).putLong(id.name + "_at", atMs).apply()
            }
        } catch (_: Exception) {
            // Persistence is best-effort: a failed write degrades to the old
            // in-memory behavior and must never crash a build state change.
        }
    }

    /** Last persisted state per task (IDLE entries are absent by construction). */
    fun savedStates(): Map<TaskRunner.TaskId, TaskRunner.RunState> {
        val out = mutableMapOf<TaskRunner.TaskId, TaskRunner.RunState>()
        if (!enabled()) return out
        try {
            val p = prefs!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for (id in TaskRunner.TaskId.values()) {
                val name = p.getString(id.name, null) ?: continue
                val state = TaskRunner.RunState.values().firstOrNull { it.name == name } ?: continue
                out[id] = state
            }
        } catch (_: Exception) {
        }
        return out
    }
}

package com.codespace.ide.terminal

/**
 * U-W1c (2026-11-11, Wave 1 Group C): the app's OWN terminal sessions, described for tools.
 *
 * WHY A REGISTRY AND NOT A SCAN: a tool that listed `/proc` would be listing unrelated processes,
 * and a tool that killed a pid would be one argument away from killing the IDE. The terminal pane
 * is the only place that knows which shells this app started, which of them are still running and
 * what each one's working directory is. So the pane PUBLISHES that set here, and the tools may only
 * see - and stop - what was published. An id that is not in this registry cannot be killed by name.
 *
 * The pane owns the truth: it calls [publish] whenever its tabs change and [clear] when it goes
 * away. Nothing here starts, restarts or reaps a session; it is a view with ONE optional action.
 */
object TerminalSessionRegistry {

    /** One published session. [id] is the pane's tab id, stable for the life of the tab. */
    data class Session(
        val id: String,
        val name: String,
        val running: Boolean,
        val cwd: String?,
        val pid: Int,
        val lockedRoot: String?,
        val isActive: Boolean,
        val updatedAtMs: Long,
    )

    private val sessions = ArrayList<Session>()

    /** Stop one session. Supplied by the pane so the kill uses the SAME teardown as a tab close. */
    private var stopper: ((String) -> Boolean)? = null

    @Synchronized
    fun publish(next: List<Session>, stop: ((String) -> Boolean)? = null) {
        sessions.clear()
        sessions.addAll(next)
        if (stop != null) stopper = stop
    }

    @Synchronized
    fun clear() {
        sessions.clear()
        stopper = null
    }

    @Synchronized
    fun list(): List<Session> = sessions.toList()

    @Synchronized
    fun find(id: String): Session? = sessions.firstOrNull { it.id == id }

    /**
     * Stop a session the app itself started. Returns false when the id is unknown (nothing is
     * killed on a guess) or when the pane has no stopper wired (headless test / no pane open).
     */
    @Synchronized
    fun stop(id: String): Boolean {
        val known = sessions.firstOrNull { it.id == id } ?: return false
        if (!known.running) return true   // already stopped: idempotent, not an error
        val action = stopper ?: return false
        return try { action(id) } catch (_: Exception) { false }
    }
}

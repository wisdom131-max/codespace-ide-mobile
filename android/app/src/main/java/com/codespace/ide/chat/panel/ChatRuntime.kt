package com.codespace.ide.chat.panel

import android.content.Context
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader

/**
 * M3 — ONE CHAT RUNTIME PER PROCESS.
 *
 * Two windows (the right-hand sidebar and the "new window" task) must show the same
 * conversation and must not become two writers of the same transcript. The session manager
 * already caches exactly one `ChatSession` per (projectId, conversationId), so the safe design
 * is one manager shared by every host, reference counted so that closing one window cannot
 * cancel a stream running in the other.
 *
 * Not a leak: the last release closes the manager, which is the pre-M3 behaviour.
 */
object ChatRuntime {

    private var manager: ChatSessionManager? = null
    private val hosts = RefCounter()

    /** @return the shared manager, creating it on first use. */
    @Synchronized
    fun acquire(ctx: Context, tokenStore: com.codespace.ide.data.SecureTokenStore): ChatSessionManager {
        val existing = manager
        if (existing != null) {
            hosts.acquire()
            return existing
        }
        ChatSettingsPrefs.ensure(ctx)
        // Item 19: saved undo history lives beside the transcripts, under the app's own storage.
        com.codespace.ide.agent.AgentWriteJournal.configure(
            java.io.File(ctx.applicationContext.filesDir, "chat/journal"),
        )
        val created = ChatSessionManager(
            StoredCredentialsReader(tokenStore, CustomEndpointStore),
            appContext = ctx.applicationContext,
        )
        manager = created
        // The counter is already at 1 for this first host; keep the two in step.
        hosts.acquire()
        return created
    }

    /**
     * Drops one host's claim. The manager (and any stream it owns) survives while another
     * window is still using it, and closes exactly when the last host leaves - which is what
     * the single-window build already did.
     */
    @Synchronized
    fun release() {
        if (!hosts.release()) return
        val m = manager ?: return
        // PHASE 2 (owner approval 2026-11-14): closing the last window must not cancel a turn.
        // PARK keeps the manager (and its stream) alive; the reaper closes it when the work ends.
        when (RuntimeLifecycle.onRelease(hosts.count(), m.anyStreaming())) {
            RuntimeLifecycle.Action.KEEP -> Unit
            RuntimeLifecycle.Action.PARK -> scheduleIdleReap()
            RuntimeLifecycle.Action.CLOSE -> { closeNow() }
        }
    }

    /** Closes the shared manager. Separate so PARK/CLOSE share one exit path. */
    @Synchronized
    private fun closeNow() {
        manager?.close()
        manager = null
    }

    /**
     * A parked manager has no window, so nothing would otherwise notice the turn ending. One
     * short tick while parked is enough; it stops the moment the manager is closed or a window
     * returns, and it never runs when a host is present.
     */
    private fun scheduleIdleReap() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val parked = synchronized(this@ChatRuntime) { manager }
                if (parked == null) return
                if (RuntimeLifecycle.canCloseWhileParked(hostCount(), parked.anyStreaming())) {
                    synchronized(this@ChatRuntime) { if (manager === parked) closeNow() }
                    return
                }
                handler.postDelayed(this, IDLE_TICK_MS)
            }
        }
        handler.postDelayed(tick, IDLE_TICK_MS)
    }

    /** The live manager, or null when no window is open. Used by the editor-tab host. */
    @Synchronized
    fun current(): ChatSessionManager? = manager

    @Synchronized
    fun hostCount(): Int = hosts.count()

    /** How often a parked runtime checks whether its work has finished. */
    private const val IDLE_TICK_MS = 1_500L
}

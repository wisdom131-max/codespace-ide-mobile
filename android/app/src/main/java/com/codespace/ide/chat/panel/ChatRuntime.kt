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
        manager?.close()
        manager = null
    }

    /** The live manager, or null when no window is open. Used by the editor-tab host. */
    @Synchronized
    fun current(): ChatSessionManager? = manager

    @Synchronized
    fun hostCount(): Int = hosts.count()
}

package com.codespace.ide.chat.panel

/**
 * PROJECT FOUR, STAGE 4.7 COMPLETION (2026-11-16): suitability signal 11, provider context.
 *
 * [WorkspaceContext] already answers "what is in the open project" for the model's instructions. What
 * a ranking needed was narrower: is the SERVICE behind a tool usable right now. This holds that as a
 * snapshot replaced whole, so a workspace or provider switch cannot leave a half-old view.
 *
 * Names and states only. No token, key, account or credential is stored here, so nothing in this object
 * can leak one into a ranking explanation, a log or a transcript. Nothing here grants a permission
 * either: a connected provider changes a score, never eligibility.
 */
internal object ProviderContext {

    /** What is actually known about the service behind a tool. */
    enum class State {
        CONNECTED,
        DISCONNECTED,
        NOT_CONFIGURED,
        UNKNOWN,
        NOT_APPLICABLE,
    }

    data class Snapshot(
        /** Stable identity of the open project, never a display title. */
        val workspaceId: String?,
        val providers: Map<String, State>,
        val at: Long,
    ) {
        val known: Boolean get() = workspaceId != null
    }

    /** Beyond this a remembered snapshot is not current and ranks nothing. */
    const val STALE_MS = 5 * 60 * 1000L

    private var current: Snapshot? = null

    @Synchronized
    fun update(workspaceId: String?, providers: Map<String, State>, now: Long = System.currentTimeMillis()) {
        current = Snapshot(workspaceId?.takeIf { it.isNotBlank() }, HashMap(providers), now)
    }

    @Synchronized
    fun clear() {
        current = null
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot? {
        val snapshot = current ?: return null
        if (now - snapshot.at > STALE_MS) return null
        return snapshot
    }

    /** The service a tool depends on, or null when it depends on none. Names only. */
    fun providerOf(tool: String): String? = when (tool) {
        "slack_message", "slack_send" -> "slack"
        "github_api", "github_pr", "git_commit_push", "git_push" -> "github"
        "connector_call", "connector_list", "list_connectors", "use_connector" -> "connectors"
        "mcp_call", "mcp_list" -> "mcp"
        else -> null
    }

    /**
     * Signal 11 for one tool: NOT_APPLICABLE when it needs no service, the recorded state when the
     * snapshot knows it, and null (unknown) otherwise. Never CONNECTED by default.
     */
    @Synchronized
    fun stateOf(tool: String, now: Long = System.currentTimeMillis()): State? {
        val snapshot = snapshot(now) ?: return null
        val provider = providerOf(tool)
        if (provider == null) return if (snapshot.known) State.NOT_APPLICABLE else null
        if (!snapshot.known) return null
        return snapshot.providers[provider] ?: State.UNKNOWN
    }

    /** What a state means in a sentence, for a ranking explanation. */
    fun phrase(state: State): String = when (state) {
        State.CONNECTED -> "its provider is connected"
        State.DISCONNECTED -> "its provider is disconnected"
        State.NOT_CONFIGURED -> "its provider is not set up"
        State.NOT_APPLICABLE -> "it needs no connected service"
        State.UNKNOWN -> "provider availability is unknown"
    }
}

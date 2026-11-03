package com.codespace.ide.security

import android.content.Context

/**
 * Project root lookup. This object used to carry per-project workspace trust as well: a
 * first-gated-action prompt, a "Trust project" button on the approval card, a persisted
 * trusted-path set and a one-time migration that grandfathered existing projects.
 *
 * Owner instruction 2026-11-03: the untrusted-project trust prompt is removed. It had already
 * been neutralised on 2026-10-31 (every check returned true), which left a prompt that could not
 * fire, a card button that could not render, and four surfaces whose refusal messages told the
 * user to approve a prompt that no longer existed. All of that is deleted here rather than
 * hidden, so nothing can claim a gate that is not there. What remains is the one thing that was
 * never about trust: resolving the active project's root for path translation.
 */
object TrustState {

    /** The active project's root path (last opened project), or null. */
    fun activeProjectRoot(context: Context): String? {
        return try {
            val pid = com.codespace.ide.data.SessionStateStore(context).lastProjectId()
            if (pid != null)
                com.codespace.ide.util.ProjectPathResolver.resolveProjectRoot(context, pid)
            else null
        } catch (_: Exception) { null }
    }
}

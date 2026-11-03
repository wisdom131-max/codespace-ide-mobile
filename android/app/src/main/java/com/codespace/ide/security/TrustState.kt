package com.codespace.ide.security

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.codespace.ide.util.CanonicalPaths
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * P2c — TrustState (2026-09-25): per-project workspace trust, keyed by CANONICAL
 * PATH (not project ID) so trust survives delete-and-re-register at the same
 * folder — same identity family as PLAN A's other fixes.
 *
 * Model (owner rulings 2026-09-24/25):
 *  - MIGRATION: every project in the local index at first launch after update is
 *    grandfathered TRUSTED, one time, no prompt (matches VS Code's launch of
 *    workspace trust; existing habits are not disrupted).
 *  - NEW projects: source-agnostic (VS Code's model) — default UNTRUSTED; the
 *    user is prompted ONCE on the first gated action and the choice is
 *    remembered for the folder forever.
 *  - UNATTENDED surfaces (AgentScheduler run, AgentApiServer /tool/) ride trust
 *    only: no prompt, fail closed with a typed message.
 *  - INTERACTIVE surfaces (chat tool loop, task/debug launch, MCP session
 *    spawn) prompt via the global TrustPromptDialog.
 *
 * Choke points consumed at: AgentScheduler.runCommand, TaskRunner.run,
 * UniversalDebugManager.startDebugAsync, AttachDebugDialog, McpClientManager
 * (session spawn + callTool), the chat tool loop (CopilotChatPanelOverlay),
 * use_connector (per-call consent, IG15), and the AgentApiServer /tool/ route.
 */
object TrustState {

    /*
     * NEUTRALISED (2026-10-31, owner instruction: "I want the trust neutralized").
     *
     * The gate used to ask once per folder. It then defaulted to "trusted" unless the
     * "Confirm Project Trust" in-project toggle was on — which is why it could block work while
     * reading as switched off: a BLANK or unresolvable project root still failed closed, and the
     * toggle lived in in-project settings, not app-wide ones.
     *
     * Everything below now allows, unconditionally, with no dialog and no stored state. The
     * functions stay because twenty call sites read them and because the folder-resolution helper
     * (activeProjectRoot) lives here: deleting the object would drag path resolution into the same
     * change, which is a far larger risk than the thing being removed. There is no setting, no
     * prompt and no stored trust left to turn back on.
     */

    /** Kept for source compatibility with the prompt-free API; nothing renders it any more. */
@Suppress("unused")
    private val neutralised: Boolean = true

    // ── Storage ────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("project_trust", Context.MODE_PRIVATE)

    /** Trusted == canonical path in the persisted set. Blank/null path is NEVER trusted.
     *  Runs the one-time migration first so pre-P2c projects are grandfathered
     *  trusted on every path, including bare isTrusted() checks. */
    /**
     * NEUTRALISED: always true. A blank path is allowed too — the old fail-closed blank check was
     * a silent block in exactly the headless surfaces (scheduler, MCP, agent server) where nothing
     * could be shown to explain it.
     */
    fun isTrusted(context: Context, path: String?): Boolean = true

    /** Persist (or clear) trust for a folder, keyed by canonical path. */
    fun setTrusted(context: Context, path: String?, trusted: Boolean) {
        if (path.isNullOrBlank()) return
        val canonical = CanonicalPaths.canonical(File(path))
        val set = (prefs(context).getStringSet("trusted_paths", emptySet()) ?: emptySet()).toMutableSet()
        if (trusted) set.add(canonical) else set.remove(canonical)
        prefs(context).edit().putStringSet("trusted_paths", set).apply()
    }

    // ── Active project ──────────────────────────────────────────────────────

    /** The active project's root path (last opened project), or null. */
    fun activeProjectRoot(context: Context): String? {
        return try {
            val pid = com.codespace.ide.data.SessionStateStore(context).lastProjectId()
            if (pid != null)
                com.codespace.ide.util.ProjectPathResolver.resolveProjectRoot(context, pid)
            else null
        } catch (_: Exception) { null }
    }

    /**
     * Headless rule: is the ACTIVE project trusted? No active project == NOT
     * trusted (fail closed — there is no folder to prompt about).
     */
    fun isActiveProjectTrusted(context: Context): Boolean = true

    // ── Interactive gate ─────────────────────────────────────────────────────

    /**
     * INTERACTIVE gate: suspend until the project is trusted. Returns false if
     * the user cancels the prompt or the path is blank (fail closed). On "Trust",
     * the choice persists — this exact prompt never fires again for this folder.
     * B (2026-10-02): with gated mode OFF (default), isTrusted short-circuits
     * true and this prompt never fires at all.
     */
    suspend fun awaitTrusted(context: Context, path: String?): Boolean = true

    // ── Migration (one-time grandfathering) ─────────────────────────────────

    /**
     * One-time migration: every project in the local index becomes TRUSTED at
     * first launch after update (canonical paths), persisted `migrated` flag so
     * later delete+re-register decisions are NOT silently re-grandfathered.
     * Deleting and re-adding the SAME folder later keeps trust only because the
     * path was already marked here — new folders created afterwards start
     * untrusted and get the first-gated-action prompt.
     */
    fun ensureMigrated(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("migrated", false)) return
        val grandparented = mutableSetOf<String>()
        try {
            // Same index format HomeScreen.saveProjectsLocal writes: prefs
            // "projects" → "list" → JSONArray of {id,name,kind,pathOrUrl}.
            val arr = context.applicationContext
                .getSharedPreferences("projects", Context.MODE_PRIVATE)
                .getString("list", null)
            if (!arr.isNullOrBlank()) {
                val projects = org.json.JSONArray(arr)
                for (i in 0 until projects.length()) {
                    val o = projects.getJSONObject(i)
                    val path = o.optString("pathOrUrl", "")
                    // URLs (GIT/cloud projects) have no folder to trust.
                    if (path.isNotBlank() && !path.startsWith("http")) {
                        val f = File(path)
                        if (f.exists()) grandparented.add(CanonicalPaths.canonical(f))
                    }
                }
            }
        } catch (_: Exception) { }
        val set = (p.getStringSet("trusted_paths", emptySet()) ?: emptySet()).toMutableSet()
        set.addAll(grandparented)
        p.edit().putStringSet("trusted_paths", set).putBoolean("migrated", true).apply()
    }
}

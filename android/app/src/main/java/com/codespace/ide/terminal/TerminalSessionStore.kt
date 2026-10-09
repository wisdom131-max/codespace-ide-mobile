package com.codespace.ide.terminal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 4 — Terminal Session Restore
 *
 * Persists the tab list (name + working dir + crash counter) to SharedPreferences as JSON.
 * Intentionally avoids Room/DataStore to keep the I/O path trivial and crash-safe.
 *
 * Restore policy (from AGENTS.md Phase 4 spec):
 *  - Never restore before 8-second startup headstart has elapsed
 *  - Skip any entry with crashCount >= 2 (auto-disabled)
 *  - Max 1 restore attempt per launch (loop guard)
 *  - Corrupted JSON → wipe and continue, do NOT crash
 *
 * PROJECT-SCOPED STORE (Group A fix, 2026-09-06): the store is keyed per project
 * ("saved_tabs__<projectId>") instead of one global slot. WHY: the old single
 * global key let one project's terminal set bleed into another — tabs saved while
 * in project B overwrote project A's saved set, so launching into A restored B's
 * terminals. The projectId is enforced by the STORAGE KEY (a projectId field on
 * SavedTab would be informational only and could not prevent the bleed). The
 * legacy global "saved_tabs" key is adopted exactly ONCE by the first project
 * that loads with no per-project key (one-time migration so existing users keep
 * their tabs in the project they were last in), then cleared so no other project
 * can also claim it.
 */
object TerminalSessionStore {

    private const val PREFS_NAME = "terminal_session_store"
    private const val KEY_TABS   = "saved_tabs"              // legacy global (pre-project-scoping) — adoption source only
    private const val KEY_TABS_PREFIX = "saved_tabs__"      // per-project keys: KEY_TABS_PREFIX + projectId
    private const val KEY_RESTORE_ATTEMPT = "restore_attempt_this_launch"
    private const val MAX_CRASH_COUNT = 2
    private const val TAG = "TerminalSessionStore"

    data class SavedTab(
        val id: String,
        val name: String,
        val workingDir: String,
        val lockedRoot: String? = null,
        val crashCount: Int = 0,
    )

    /** F3 (2026-10-09, owner-approved): decoded store payload. */
    data class DecodedTabs(
        /** The tab that was ACTIVE at save time (null = legacy store: use the first tab). */
        val activeId: String?,
        val tabs: List<SavedTab>,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun projectKey(projectId: String): String = KEY_TABS_PREFIX + projectId

    // ── Pure codec (F3: JVM-tested round-trip; no Context, no android APIs) ──

    /**
     * F3 store format: {"activeId": "...", "tabs": [...]}. The bare-ARRAY format
     * (pre-F3) remains readable — decodeTabs treats it as activeId=null.
     */
    fun encodeTabs(activeId: String?, tabs: List<SavedTab>): String {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(JSONObject().apply {
                put("id",         t.id)
                put("name",       t.name)
                put("workingDir", t.workingDir)
                put("lockedRoot", t.lockedRoot ?: JSONObject.NULL)
                put("crashCount", t.crashCount)
            })
        }
        return JSONObject().apply {
            put("activeId", activeId ?: JSONObject.NULL)
            put("tabs", arr)
        }.toString()
    }

    /**
     * @return null when the payload is unreadable/corrupt — the CALLER then starts
     *         with one default tab and logs it (owner F3 condition a).
     */
    fun decodeTabs(raw: String?): DecodedTabs? {
        if (raw == null) return null
        return try {
            val arr: JSONArray
            val activeId: String?
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                arr = obj.optJSONArray("tabs") ?: return null
                activeId = if (obj.isNull("activeId")) null else obj.optString("activeId", null)
            } else {
                // Legacy pre-F3 format: a bare array of tab objects, no active id.
                arr = JSONArray(trimmed)
                activeId = null
            }
            val result = mutableListOf<SavedTab>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(SavedTab(
                    id         = obj.getString("id"),
                    name       = obj.getString("name"),
                    workingDir = obj.optString("workingDir", "/root"),
                    lockedRoot = if (obj.isNull("lockedRoot")) null else obj.optString("lockedRoot", null),
                    crashCount = obj.optInt("crashCount", 0),
                ))
            }
            DecodedTabs(activeId, result)
        } catch (_: Exception) {
            null
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────

    /**
     * Persist the tab list UNDER THE CURRENT PROJECT (count, order, names, active
     * tab, per-tab CWD). Call on every structural change (open/close/rename) and
     * on DEBOUNCED 633 P;Cwd changes — never only on clean exit (owner F3 condition a).
     *
     * Atomicity: SharedPreferences.edit().apply() writes the whole XML via a temp
     * file + atomic rename inside Android's SharedPreferencesImpl — the temp-file-
     * then-rename contract the owner asked for, without a second hand-rolled I/O
     * path that could diverge from the reader.
     */
    suspend fun save(ctx: Context, projectId: String, activeId: String?, tabs: List<SavedTab>) = withContext(Dispatchers.IO) {
        try {
            prefs(ctx).edit().putString(projectKey(projectId), encodeTabs(activeId, tabs)).apply()
            Log.d(TAG, "Saved ${tabs.size} tabs (active=$activeId) for project $projectId")
        } catch (e: Exception) {
            Log.w(TAG, "save() failed: ${e.message}")
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────

    /**
     * Returns the CURRENT project's saved payload (active tab id + tab list),
     * filtered to only entries eligible for restore (crashCount < MAX_CRASH_COUNT).
     * Returns empty tabs if nothing saved or JSON is corrupted (corrupt → wipe +
     * one default tab at the caller).
     *
     * One-time legacy adoption: if this project has no per-project key yet but the
     * old global store exists, the global list is adopted for THIS project and the
     * global key is cleared — so exactly one project (the first to load) inherits it.
     */
    suspend fun load(ctx: Context, projectId: String): DecodedTabs = withContext(Dispatchers.IO) {
        try {
            val p = prefs(ctx)
            var raw = p.getString(projectKey(projectId), null)
            if (raw == null) {
                val legacy = p.getString(KEY_TABS, null)
                if (legacy != null) {
                    // Adopt the legacy global list for THIS project, then consume the
                    // global key so no other project can also claim it.
                    p.edit().putString(projectKey(projectId), legacy).remove(KEY_TABS).apply()
                    Log.d(TAG, "Adopted legacy global session list for project $projectId (one-time)")
                }
                raw = legacy
            }
            if (raw == null) return@withContext DecodedTabs(null, emptyList())
            val decoded = decodeTabs(raw)
            if (decoded == null) {
                // TP15 (2026-09-26): ACCEPTED TRADEOFF, recorded per the audit row — a
                // corrupted store wipes this project's tab list (names/locks vanish) rather
                // than risking a half-parsed restore. The warning log makes the wipe
                // non-silent for diagnostics; rebuilding the tab layout is cheap and the
                // store holds layout only, never session contents. The CALLER starts
                // with one default tab (owner F3 condition a).
                Log.w(TAG, "load() corrupted JSON — wiping project store; starting with one default tab")
                wipe(ctx, projectId)
                return@withContext DecodedTabs(null, emptyList())
            }
            val result = mutableListOf<SavedTab>()
            decoded.tabs.forEach { entry ->
                if (entry.crashCount < MAX_CRASH_COUNT) {
                    result.add(entry)
                } else {
                    Log.w(TAG, "Skipping tab '${entry.name}' — crashed ${entry.crashCount}x")
                }
            }
            Log.d(TAG, "Loaded ${result.size}/${decoded.tabs.size} eligible tabs for project $projectId (active=${decoded.activeId})")
            DecodedTabs(decoded.activeId, result)
        } catch (e: Exception) {
            Log.w(TAG, "load() failed — wiping project store: ${e.message}")
            wipe(ctx, projectId)
            DecodedTabs(null, emptyList())
        }
    }

    // ── Restore loop guard ────────────────────────────────────────────────

    /**
     * Returns true and marks the attempt if this is the first restore attempt
     * this process lifetime. Returns false if already attempted (loop guard).
     */
    fun claimRestoreAttempt(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_RESTORE_ATTEMPT, false)) return false
        p.edit().putBoolean(KEY_RESTORE_ATTEMPT, true).apply()
        return true
    }

    /** Call on clean app exit so the guard resets for next launch. */
    fun resetRestoreGuard(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_RESTORE_ATTEMPT, false).apply()
    }

    // ── Crash tracking ────────────────────────────────────────────────────

    /**
     * Increment crash count for a tab id. If >= MAX_CRASH_COUNT the tab will
     * be skipped on next restore (auto-disabled).
     */
    suspend fun incrementCrashCount(ctx: Context, projectId: String, tabId: String) = withContext(Dispatchers.IO) {
        try {
            val key = projectKey(projectId)
            val raw = prefs(ctx).getString(key, null) ?: return@withContext
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") == tabId) {
                    obj.put("crashCount", obj.optInt("crashCount", 0) + 1)
                    break
                }
            }
            prefs(ctx).edit().putString(key, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "incrementCrashCount() failed: ${e.message}")
        }
    }

    // ── Manual restore ────────────────────────────────────────────────────

    /**
     * Wipe the CURRENT project's saved sessions (called from ⋮ menu
     * "Clear saved sessions" — clears the current project's key plus the legacy
     * global key so nothing stale survives).
     */
    suspend fun wipe(ctx: Context, projectId: String) = withContext(Dispatchers.IO) {
        prefs(ctx).edit().remove(projectKey(projectId)).remove(KEY_TABS).apply()
        Log.d(TAG, "Session store wiped for project $projectId")
    }

    /** Check if there is anything saved worth restoring for this project. */
    suspend fun hasSavedSessions(ctx: Context, projectId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val raw = prefs(ctx).getString(projectKey(projectId), null) ?: return@withContext false
            JSONArray(raw).length() > 0
        } catch (_: Exception) { false }
    }
}

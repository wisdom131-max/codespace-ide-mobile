package com.codespace.ide.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * ROUND 5 (2026-09-12) — VS Code-style agent permission levels + per-tool
 * auto-approve, replacing the flat MANUAL/AUTO FlowMode binary.
 *
 * Levels:
 *  - MANUAL    — every tool call pauses for approval (old "Manual").
 *  - AUTO_SAFE — read-only tools run immediately; anything that can change
 *                state still asks (VS Code "Assisted"-ish middle ground).
 *  - AUTO_ALL  — everything executes immediately (old "Auto", default).
 *
 * On top of the level, the user can auto-approve INDIVIDUAL tools from the
 * approval card ("Always allow <tool>") — remembered in a persisted set.
 * The allowlist applies in EVERY level (in AUTO_ALL it is redundant but kept
 * so the Settings chips still show what the user opted into).
 *
 * Migration: the first read copies the old ProjectSettingsStore "flow_mode"
 * pref (MANUAL -> MANUAL, AUTO -> AUTO_ALL) so existing users keep their
 * behavior. ProjectSettingsStore.flowMode keeps syncing for back-compat.
 */
enum class ChatFlowLevel { MANUAL, AUTO_SAFE, AUTO_ALL }

object ChatPermissionStore {
    private const val PREFS = "chat_permissions"
    private const val KEY_LEVEL = "flow_level"
    private const val KEY_AUTO_TOOLS = "auto_approved_tools"
    private const val OLD_PREFS = "project_settings" // ProjectSettingsStore prefs name
    private const val OLD_KEY_FLOW = "flow_mode"

    /**
     * Tools AUTO_SAFE lets through: read-only local + git inspection + memory
     * reads + connector listings. Anything that writes, executes, deletes,
     * spends money, or reaches the network as a mutation stays gated.
     *
     * R6-PENDING-EDITS (decision #1): `write_file` is NO LONGER GATED AT ANY
     * LEVEL in AGENT mode — it stages into PendingChangesStore (content that
     * cannot reach disk needs no approval gate; the human gate is the
     * user-initiated Apply). The tool loop skips awaitApproval() for staged
     * writes; everything else (run_command especially) is unchanged.
     */
    val SAFE_TOOLS: Set<String> = setOf(
        "read_file", "list_files", "search_files",
        "git_status", "git_diff",
        "read_memory", "read_entities", "list_tasks", "list_connectors",
        "detect_secrets",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun level(context: Context): ChatFlowLevel {
        try {
            val stored = prefs(context).getString(KEY_LEVEL, null)
            if (stored != null) return ChatFlowLevel.valueOf(stored)
            // First run after the R5 upgrade: inherit the old FlowMode pref.
            val old = context.applicationContext
                .getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
                .getString(OLD_KEY_FLOW, null)
            val migrated = when (old) {
                "MANUAL" -> ChatFlowLevel.MANUAL
                "AUTO" -> ChatFlowLevel.AUTO_ALL
                else -> ChatFlowLevel.AUTO_ALL
            }
            setLevel(context, migrated)
            return migrated
        } catch (_: Exception) { return ChatFlowLevel.AUTO_ALL }
    }

    fun setLevel(context: Context, level: ChatFlowLevel) {
        try {
            prefs(context).edit().putString(KEY_LEVEL, level.name).apply()
        } catch (_: Exception) { }
    }

    /** Tools the user explicitly marked "Always allow" from the approval card. */
    fun autoApprovedTools(context: Context): Set<String> =
        try {
            prefs(context).getString(KEY_AUTO_TOOLS, "")!!.split(',')
                .filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }

    fun allowTool(context: Context, toolName: String) {
        try {
            val cur = autoApprovedTools(context).toMutableSet()
            if (cur.add(toolName)) {
                prefs(context).edit().putString(KEY_AUTO_TOOLS, cur.joinToString(",")).apply()
            }
        } catch (_: Exception) { }
    }

    fun disallowTool(context: Context, toolName: String) {
        try {
            val cur = autoApprovedTools(context).toMutableSet()
            if (cur.remove(toolName)) {
                prefs(context).edit().putString(KEY_AUTO_TOOLS, cur.joinToString(",")).apply()
            }
        } catch (_: Exception) { }
    }

    /**
     * True when [toolName] may execute WITHOUT pausing for approval under the
     * current level + allowlist. Consulted by AgentFlowGate.awaitApproval.
     */
    fun isAutoApproved(context: Context, toolName: String): Boolean {
        if (toolName.startsWith("mcp_")) return false // external MCP tools always respect the level
        val lvl = level(context)
        return when {
            lvl == ChatFlowLevel.AUTO_ALL -> true
            toolName in autoApprovedTools(context) -> true
            lvl == ChatFlowLevel.AUTO_SAFE && toolName in SAFE_TOOLS -> true
            else -> false
        }
    }
}

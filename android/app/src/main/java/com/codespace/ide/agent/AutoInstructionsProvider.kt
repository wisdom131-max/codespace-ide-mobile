package com.codespace.ide.agent

import android.content.Context
import java.io.File

/**
 * AUTO-INSTRUCTIONS PROVIDER (Round 2, Copilot-chat parity):
 *
 * VS Code's `ComputeAutomaticInstructions` auto-attaches per-project instruction
 * files to every Copilot request. This is our generic port: it detects the
 * conventional instruction files in ANY user project root —
 *
 *   AGENTS.md                        (cross-tool convention, ours + agent CLIs)
 *   copilot-instructions.md          (VS Code classic, project root)
 *   .github/copilot-instructions.md  (VS Code default location)
 *   CLAUDE.md                        (Claude convention, read-compat)
 *
 * — and prepends them to the chat system prompt (in-app panel) and to the
 * CLI /system-prompt endpoint. All detection is HOST-side File reads; no
 * proot involvement. Per-project on/off persists in SharedPreferences
 * (default ON), keyed by the resolved project root path.
 */
object AutoInstructionsProvider {

    data class AutoInstructionFile(
        val path: String,
        val name: String,
        val content: String,
        val truncated: Boolean,
    )

    /** Priority order = first match is the "primary" file; all found files attach. */
    private val CANDIDATES = listOf(
        "AGENTS.md",
        "copilot-instructions.md",
        ".github/copilot-instructions.md",
        "CLAUDE.md",
    )

    private const val MAX_FILE_CHARS = 8000
    private const val MAX_TOTAL_CHARS = 16000
    private const val PREFS = "auto_instructions"

    private fun prefKey(projectRoot: String): String =
        "enabled_" + projectRoot.replace('/', '_').replace('\\', '_')

    /** Per-project attach toggle. Default ON — instructions are opt-OUT. */
    fun isEnabled(context: Context, projectRoot: String): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(prefKey(projectRoot), true)
    } catch (_: Exception) { true }

    fun setEnabled(context: Context, projectRoot: String, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(prefKey(projectRoot), enabled).apply()
        } catch (_: Exception) { }
    }

    /** Names of the instruction files found in [projectRoot] (chip UI uses this). */
    fun names(projectRoot: String): List<String> =
        detect(projectRoot).map { it.name }

    /**
     * Read all candidate files that exist and are non-empty. Content is capped
     * per-file and in total so a giant AGENTS.md can't eat the whole context
     * window. Never throws — a broken read just skips that file.
     */
    fun detect(projectRoot: String): List<AutoInstructionFile> {
        val out = ArrayList<AutoInstructionFile>()
        if (projectRoot.isBlank()) return out
        var total = 0
        for (name in CANDIDATES) {
            try {
                val f = File(projectRoot, name)
                if (!f.isFile || !f.canRead()) continue
                val text = f.readText().trim()
                if (text.isEmpty()) continue
                val remainingTotal = MAX_TOTAL_CHARS - total
                if (remainingTotal <= 0) break
                val capped = text.take(minOf(MAX_FILE_CHARS, remainingTotal))
                val truncated = capped.length < text.length
                out.add(AutoInstructionFile(f.absolutePath, name, capped, truncated))
                total += capped.length
            } catch (_: Exception) {
                // unreadable/oversized/encoding issue — skip the file silently
            }
        }
        return out
    }

    /**
     * The system-prompt block. Returns "" when the project has no instruction
     * files (the common case) — callers append it unconditionally.
     */
    fun buildBlock(projectRoot: String): String {
        val files = detect(projectRoot)
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## PROJECT INSTRUCTIONS (auto-attached)\n")
        sb.append("The user's project contains instruction files. They are the user's standing\n")
        sb.append("preferences for THIS project and take precedence over general style choices.\n")
        for (file in files) {
            sb.append("\n### ").append(file.name).append('\n')
            sb.append(file.content)
            if (file.truncated) sb.append("\n(file truncated to fit context)")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}

package com.codespace.ide.agent

import org.json.JSONObject

/**
 * PHASE 5b (2026-11-15): a write stays inside the open project, and says so when it cannot.
 *
 * The containment check existed ([FileSystemTools.isInsideEntry]) but only copy and move used it,
 * so a write, an edit or a delete could name any path the process can reach - the workspace was a
 * suggestion, not a boundary. This is the one list of which tools are writes and which argument
 * carries the path, so the check is applied by a single call site instead of being remembered
 * per tool.
 *
 * Pure: the classification is tested without a Context or a file system.
 */
internal object WriteBoundary {

    /** The tools whose effect is a change to a file on disk. Mirrors WritePolicy's writer set. */
    private val writers: Set<String> = setOf(
        "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
        "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path",
    )

    fun isWriter(toolName: String): Boolean = toolName in writers

    /** Every argument of this call that names a destination path, in the order they must be checked. */
    fun pathsOf(toolName: String, args: JSONObject): List<String> {
        if (!isWriter(toolName)) return emptyList()
        val out = ArrayList<String>(2)
        runCatching { args.optString("path") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        if (toolName == "copy_path" || toolName == "move_path") {
            runCatching { args.optString("from") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            runCatching { args.optString("to") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }

    /** What the model is told. It must not read as "try again somewhere else". */
    fun refusal(toolName: String, path: String, root: String): String =
        "REFUSED: `$toolName` targets $path, which is outside the open project ($root). " +
            "Nothing was written. Open that project first, or name a path inside it."
}

package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect

/**
 * Area 5 - CAN THIS TOOL CHANGE THE WORLD?
 *
 * Authority: this table and the registry it mirrors, never a tool's own description and never a
 * model's claim about itself. An unlisted name is SIDE_EFFECTING, because the safe mistake is to
 * treat an unknown tool as dangerous and reconcile, not to re-run a write because its name looked
 * harmless.
 */
object ToolEffectIndex {

    /** Every tool that can write, spawn, delete, move or transmit. Registry order honoured. */
    private val sideEffecting: Set<String> = setOf(
        // files
        "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
        "move_path", "delete_path", "copy_path", "apply_patch", "edit_file",
        // processes and terminals
        "run_command", "run_in_terminal", "terminal_write", "kill_process",
        // git
        "git_commit", "git_stash", "git_tag", "git_checkout", "git_branch_create",
        "git_reset", "git_merge", "git_conflict_resolve", "git_checkpoint", "git_restore",
        // archives and packages
        "zip_create", "zip_extract", "install_package", "uninstall_package",
        // journal / task board / notebooks
        "journal_write", "manage_todo_list", "plan", "create_notebook", "edit_cell", "run_cell",
        // editor actions and anything that leaves the device
        "run_vscode_command", "http_request", "npm_info", "pypi_info", "shared_update",
    )

    fun effectOf(toolName: String): ToolEffect =
        if (toolName in sideEffecting) ToolEffect.SIDE_EFFECTING else readOnly(toolName)

    /**
     * Names the registry declares read-only. Anything outside both sets is treated as
     * side-effecting by [effectOf], so a new tool defaults to the safe classification.
     */
    private val readOnlyNames: Set<String> = setOf(
        "read_file", "list_dir", "grep_search", "file_search", "semantic_search",
        "search_workspace_symbols", "read_project_structure", "read_cell_output",
        "checksum", "diff_files", "zip_list", "lockfile_parse", "get_diagnostics",
        "git_log", "git_status", "git_diff", "list_terminals",
    )

    private fun readOnly(toolName: String): ToolEffect =
        if (toolName in readOnlyNames) ToolEffect.READ_ONLY else ToolEffect.SIDE_EFFECTING
}

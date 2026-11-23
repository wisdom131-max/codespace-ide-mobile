package com.codespace.ide.engine

/**
 * VS Code parity for the thinking section's collapsed header (owner request, 2026-11-23).
 *
 * Their chat panel does not count seconds at the user. Its collapsed header NAMES the thing that is
 * happening right now - the source gives the literal example "Editing files" while a replace or
 * patch streams - and the name is taken from the LATEST item in the section, not the first.
 *
 * This file is only the naming rule. It is pure, so it can be tested without a device, and the card
 * that draws it stays a drawing job.
 */
object ThinkingTitles {

    /** Fallback when there is nothing to name yet. */
    const val IDLE = "Thinking"

    private val RULES: List<Pair<List<String>, String>> = listOf(
        listOf(
            "write_file", "create_file", "apply_patch", "replace_string_in_file",
            "multi_replace_string_in_file", "insert_edit_into_file", "create_directory",
            "copy_path", "move_path", "delete", "create_new_jupyter_notebook",
            "edit_notebook_file", "zip_create", "zip_extract", "docx_create", "docx_edit",
            "xlsx_write", "pptx_create",
        ) to "Editing files",
        listOf(
            "read_file", "list_files", "list_dir", "file_search", "search_files",
            "grep_search", "grep_replace_all", "read_project_structure", "file_stat",
            "semantic_search", "search_workspace_symbols", "read_entities", "read_notebook_cell_output",
        ) to "Reading the project",
        listOf("test_") to "Running tests",
        listOf(
            "run_command", "run_in_terminal", "terminal_", "create_and_run_task",
            "get_task_output", "run_task", "install_package", "run_vncode_command",
        ) to "Running commands",
        listOf("web_search", "web_fetch") to "Searching the web",
        listOf("git_", "github_repo") to "Checking git",
        listOf("memory_", "save_memory", "read_memory", "delete_memory") to "Checking memory",
        listOf("image_", "video_", "media_info", "upload_file") to "Looking at files",
        listOf("plan", "task_plan", "manage_todo_list", "delegate") to "Planning the work",
        listOf("use_connector", "request_connector", "connect_service", "service_read", "service_write") to "Talking to a service",
        listOf("debug_") to "Debugging",
    )

    /**
     * The verb phrase for one step line. Step lines arrive as user-facing summaries such as
     * "read_file: 42 lines" or "write_file(path)", so the tool name is what comes before the first
     * colon or bracket.
     */
    fun titleFor(step: String): String {
        val raw = step.trim()
        if (raw.isEmpty()) return IDLE
        val name = raw.substringBefore(':').substringBefore('(').trim().lowercase()
        if (name.isEmpty()) return IDLE
        for ((prefixes, title) in RULES) {
            for (p in prefixes) {
                if (name == p || name.startsWith(p)) return title
            }
        }
        return IDLE
    }

    /**
     * What the collapsed header says: the name of the LATEST step, because that is the one the user
     * is watching. VS Code does the same, and the source says why - a section whose title names an
     * older step looks stuck.
     */
    fun runningTitle(steps: List<String>, fallback: String = IDLE): String {
        val last = steps.lastOrNull { it.isNotBlank() } ?: return fallback
        val title = titleFor(last)
        return if (title == IDLE) fallback else title
    }
}

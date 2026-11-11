package com.codespace.ide.chat.panel

/**
 * L1 (owner approval 2026-11-11): what a tool IS, beside what it does.
 *
 * The registry already knew a name, a description and a schema. It did not know that `grep_search`
 * and `file_search` are both "find", that `diff_files` answers "compare", or that a tool writes to
 * the user's repository. Without that, "which tools does this request need" could only be answered
 * by sending all of them.
 *
 * Nothing here can EXECUTE or EXPOSE anything: a category is metadata, and [ToolMetaIndex.core]
 * still returns names that must pass the same registry, mode and approval checks they always did.
 */
internal enum class ToolCategory {
    FILES, SEARCH, EDIT, GIT, TERMINAL, MEMORY, WEB, CONNECTORS, DATA, TASKS, NOTEBOOKS, SKILLS,
    AGENT, TESTING, MCP,
}

/** How expensive a tool is for the model to use, not how expensive it is to run. */
internal enum class ToolCost { CHEAP, NORMAL, HEAVY }

internal data class ToolMeta(
    val category: ToolCategory,
    /** Words a user or a model might use for this capability, beyond its own name. */
    val keywords: List<String> = emptyList(),
    /** True when the tool changes the project, the repository or the user's stored state. */
    val destructive: Boolean = false,
    val cost: ToolCost = ToolCost.NORMAL,
)

/**
 * The index: metadata, deterministic search and the core sets behind Lean Tool Mode.
 * Pure Kotlin, so every ranking rule below is asserted by a JVM test.
 */
internal object ToolMetaIndex {

    private const val NONE_MARK = "-"

    /** Explicit metadata for the tools where a name alone is not enough. */
    private val EXPLICIT: Map<String, ToolMeta> = mapOf(
        "read_file" to ToolMeta(ToolCategory.FILES, listOf("read", "open", "view", "show", "cat", "inspect")),
        "read_multiple_files" to ToolMeta(ToolCategory.FILES, listOf("read", "several", "batch", "files")),
        "list_files" to ToolMeta(ToolCategory.FILES, listOf("list", "files", "folder", "directory")),
        "list_dir" to ToolMeta(ToolCategory.FILES, listOf("list", "directory", "ls", "folder")),
        "create_directory" to ToolMeta(ToolCategory.FILES, listOf("mkdir", "folder", "create"), destructive = true),
        // Wave 1 Group A: the answer instead of the payload.
        "json_query" to ToolMeta(ToolCategory.DATA, listOf("json", "jsonpath", "query", "field", "extract", "jq", "key"), cost = ToolCost.CHEAP),
        "csv_parse" to ToolMeta(ToolCategory.DATA, listOf("csv", "spreadsheet", "rows", "columns", "table", "parse", "dataset"), cost = ToolCost.CHEAP),
        "base64_codec" to ToolMeta(ToolCategory.DATA, listOf("base64", "b64", "encode", "decode", "binary", "blob"), cost = ToolCost.CHEAP),
        "grep_replace_all" to ToolMeta(
            ToolCategory.EDIT,
            listOf("replace all", "bulk replace", "across files", "everywhere", "refactor", "rename string", "find and replace"),
            destructive = true,
        ),
        "move_path" to ToolMeta(ToolCategory.FILES, listOf("move", "rename", "relocate"), destructive = true),
        "checksum" to ToolMeta(ToolCategory.FILES, listOf("hash", "sha256", "md5", "verify", "integrity", "digest")),
        "diff_files" to ToolMeta(ToolCategory.FILES, listOf("diff", "compare", "difference", "changes", "versus")),
        "view_image" to ToolMeta(ToolCategory.FILES, listOf("image", "screenshot", "picture", "png")),
        "search_files" to ToolMeta(ToolCategory.SEARCH, listOf("find", "search", "grep", "content", "text")),
        "grep_search" to ToolMeta(ToolCategory.SEARCH, listOf("find", "search", "grep", "regex", "content")),
        "file_search" to ToolMeta(ToolCategory.SEARCH, listOf("find", "file", "name", "glob", "locate")),
        "semantic_search" to ToolMeta(ToolCategory.SEARCH, listOf("concept", "meaning", "similar", "semantic")),
        "search_workspace_symbols" to ToolMeta(ToolCategory.SEARCH, listOf("symbol", "class", "function", "definition")),
        "read_project_structure" to ToolMeta(ToolCategory.SEARCH, listOf("tree", "structure", "layout", "overview")),
        "get_search_view_results" to ToolMeta(ToolCategory.SEARCH, listOf("results", "matches")),
        "write_file" to ToolMeta(ToolCategory.EDIT, listOf("create", "write", "save", "new", "file"), true),
        "create_file" to ToolMeta(ToolCategory.EDIT, listOf("create", "write", "new", "file"), true),
        "replace_string_in_file" to ToolMeta(ToolCategory.EDIT, listOf("edit", "replace", "change", "modify"), true),
        "multi_replace_string_in_file" to ToolMeta(ToolCategory.EDIT, listOf("edit", "replace", "several", "batch"), true),
        "insert_edit_into_file" to ToolMeta(ToolCategory.EDIT, listOf("insert", "add", "edit"), true),
        "apply_patch" to ToolMeta(ToolCategory.EDIT, listOf("patch", "diff", "apply", "fix"), true),
        "get_errors" to ToolMeta(ToolCategory.EDIT, listOf("error", "diagnostic", "compile", "warning", "lint")),
        "get_changed_files" to ToolMeta(ToolCategory.GIT, listOf("changed", "modified", "git", "status", "diff")),
        "git_status" to ToolMeta(ToolCategory.GIT, listOf("status", "changed", "modified", "dirty")),
        "git_diff" to ToolMeta(ToolCategory.GIT, listOf("diff", "changes", "compare", "patch")),
        "git_branch" to ToolMeta(ToolCategory.GIT, listOf("branch", "checkout", "switch"), true),
        "git_commit_push" to ToolMeta(ToolCategory.GIT, listOf("commit", "push", "save", "publish"), true),
        "git_pull_rebase" to ToolMeta(ToolCategory.GIT, listOf("pull", "rebase", "update", "sync"), true),
        "git_stash" to ToolMeta(ToolCategory.GIT, listOf("stash", "set aside", "shelve", "temporarily"), true),
        "git_tag" to ToolMeta(ToolCategory.GIT, listOf("tag", "release", "version"), true),
        "github_repo" to ToolMeta(ToolCategory.GIT, listOf("github", "issues", "pull request", "repository")),
        // Wave 1 Groups B/C/D (owner approval 2026-11-11).
        "file_stat" to ToolMeta(ToolCategory.FILES, listOf("stat", "size", "metadata", "modified", "timestamp", "exists", "info", "mtime", "permissions"), cost = ToolCost.CHEAP),
        "disk_usage" to ToolMeta(ToolCategory.FILES, listOf("du", "size", "space", "usage", "how big", "bytes", "disk"), cost = ToolCost.CHEAP),
        "copy_path" to ToolMeta(ToolCategory.FILES, listOf("copy", "cp", "duplicate", "clone", "backup"), destructive = true),
        "terminal_list" to ToolMeta(ToolCategory.TERMINAL, listOf("terminals", "sessions", "shells", "open terminals", "tabs", "list"), cost = ToolCost.CHEAP),
        "terminal_kill" to ToolMeta(ToolCategory.TERMINAL, listOf("kill", "stop", "terminate", "session", "terminal", "close"), destructive = true),
        "job_status" to ToolMeta(ToolCategory.TASKS, listOf("job", "status", "running", "background", "scheduled", "progress", "exit code", "finished"), cost = ToolCost.CHEAP),
        "coverage_read" to ToolMeta(ToolCategory.TESTING, listOf("coverage", "jacoco", "lcov", "untested", "lines covered", "percent"), cost = ToolCost.CHEAP),
        "entity_schema_read" to ToolMeta(ToolCategory.DATA, listOf("entity", "schema", "fields", "columns", "model", "types", "records"), cost = ToolCost.CHEAP),
        "memory_search" to ToolMeta(ToolCategory.MEMORY, listOf("memory", "recall", "search memory", "facts", "remember", "preference"), cost = ToolCost.CHEAP),
        "run_command" to ToolMeta(ToolCategory.TERMINAL, listOf("shell", "run", "command", "bash", "exec"), true, ToolCost.HEAVY),
        "run_in_terminal" to ToolMeta(ToolCategory.TERMINAL, listOf("terminal", "shell", "run", "command"), true, ToolCost.HEAVY),
        "get_terminal_output" to ToolMeta(ToolCategory.TERMINAL, listOf("terminal", "output", "log", "result")),
        "terminal_last_command" to ToolMeta(ToolCategory.TERMINAL, listOf("last", "command", "history")),
        "terminal_selection" to ToolMeta(ToolCategory.TERMINAL, listOf("selection", "copy", "terminal")),
        "save_memory" to ToolMeta(ToolCategory.MEMORY, listOf("remember", "memory", "fact", "preference"), true),
        "read_memory" to ToolMeta(ToolCategory.MEMORY, listOf("remember", "memory", "recall", "context")),
        "delete_memory" to ToolMeta(ToolCategory.MEMORY, listOf("forget", "memory", "remove"), true),
        "journal_write" to ToolMeta(ToolCategory.MEMORY, listOf("note", "progress", "journal", "log", "record"), true),
        "web_search" to ToolMeta(ToolCategory.WEB, listOf("search", "google", "news", "latest", "research", "trending")),
        "web_fetch" to ToolMeta(ToolCategory.WEB, listOf("fetch", "page", "url", "read", "article", "research")),
        "list_connectors" to ToolMeta(ToolCategory.CONNECTORS, listOf("connector", "integration", "service")),
        "use_connector" to ToolMeta(ToolCategory.CONNECTORS, listOf("connector", "api", "call", "service"), true, ToolCost.HEAVY),
        "connect_service" to ToolMeta(ToolCategory.CONNECTORS, listOf("connect", "sign in", "authorize"), true),
        "request_connector" to ToolMeta(ToolCategory.CONNECTORS, listOf("request", "add", "connector"), true),
        "save_secret" to ToolMeta(ToolCategory.CONNECTORS, listOf("secret", "key", "credential", "token"), true),
        "get_secret" to ToolMeta(ToolCategory.CONNECTORS, listOf("secret", "key", "credential")),
        "detect_secrets" to ToolMeta(ToolCategory.CONNECTORS, listOf("secret", "scan", "leak", "credential")),
        "create_entity" to ToolMeta(ToolCategory.DATA, listOf("record", "entity", "database", "create"), true),
        "read_entities" to ToolMeta(ToolCategory.DATA, listOf("records", "entity", "query", "database")),
        "update_entity" to ToolMeta(ToolCategory.DATA, listOf("record", "entity", "update"), true),
        "delete_entity" to ToolMeta(ToolCategory.DATA, listOf("record", "entity", "delete"), true),
        "schedule_task" to ToolMeta(ToolCategory.TASKS, listOf("schedule", "later", "reminder", "cron"), true),
        "list_tasks" to ToolMeta(ToolCategory.TASKS, listOf("scheduled", "tasks", "jobs")),
        "cancel_task" to ToolMeta(ToolCategory.TASKS, listOf("cancel", "scheduled", "stop"), true),
        "get_task_output" to ToolMeta(ToolCategory.TASKS, listOf("task", "output", "result")),
        "run_task" to ToolMeta(ToolCategory.TASKS, listOf("run", "task", "script"), true),
        "create_and_run_task" to ToolMeta(ToolCategory.TASKS, listOf("task", "run", "create"), true),
        "manage_todo_list" to ToolMeta(ToolCategory.TASKS, listOf("todo", "plan", "steps", "checklist"), true),
        "create_new_jupyter_notebook" to ToolMeta(ToolCategory.NOTEBOOKS, listOf("notebook", "ipynb", "jupyter"), true),
        "edit_notebook_file" to ToolMeta(ToolCategory.NOTEBOOKS, listOf("notebook", "cell", "edit"), true),
        "copilot_getNotebookSummary" to ToolMeta(ToolCategory.NOTEBOOKS, listOf("notebook", "summary", "cells")),
        "read_notebook_cell_output" to ToolMeta(ToolCategory.NOTEBOOKS, listOf("notebook", "output", "cell")),
        "run_notebook_cell" to ToolMeta(ToolCategory.NOTEBOOKS, listOf("notebook", "run", "cell"), true),
        "create_skill" to ToolMeta(ToolCategory.SKILLS, listOf("skill", "script", "create"), true),
        "list_skills" to ToolMeta(ToolCategory.SKILLS, listOf("skill", "scripts", "list")),
        "read_skill" to ToolMeta(ToolCategory.SKILLS, listOf("skill", "read", "script")),
        "delete_skill" to ToolMeta(ToolCategory.SKILLS, listOf("skill", "delete", "remove"), true),
        "tool_search" to ToolMeta(ToolCategory.AGENT, listOf("tools", "capabilities", "discover", "load", "what can you do")),
        "plan" to ToolMeta(ToolCategory.AGENT, listOf("plan", "steps", "approach")),
        "vncode_askQuestions" to ToolMeta(ToolCategory.AGENT, listOf("ask", "question", "clarify")),
        "vncode_get_confirmation" to ToolMeta(ToolCategory.AGENT, listOf("confirm", "approve", "yes no")),
        "vncode_get_confirmation_with_options" to ToolMeta(ToolCategory.AGENT, listOf("choose", "options", "confirm")),
        "run_vncode_command" to ToolMeta(ToolCategory.AGENT, listOf("command", "editor", "save", "format", "goto"), true),
        "get_project_setup_info" to ToolMeta(ToolCategory.AGENT, listOf("setup", "project", "build system")),
        "install_package" to ToolMeta(ToolCategory.AGENT, listOf("install", "package", "dependency", "npm", "pip"), true, ToolCost.HEAVY),
        "upload_file" to ToolMeta(ToolCategory.AGENT, listOf("upload", "share", "file"), true),
        "test_search" to ToolMeta(ToolCategory.TESTING, listOf("test", "find", "spec", "suite")),
        "test_failure" to ToolMeta(ToolCategory.TESTING, listOf("test", "failure", "failing", "error")),
        "runTests" to ToolMeta(ToolCategory.TESTING, listOf("test", "run", "suite", "verify"), false, ToolCost.HEAVY),
    )

    /** Every registered tool gets metadata: explicit when quality needs it, derived otherwise. */
    fun metaFor(spec: ToolSpec): ToolMeta = EXPLICIT[spec.name] ?: derived(spec)

    private fun derived(spec: ToolSpec): ToolMeta {
        val name = spec.name.lowercase()
        val category = when {
            name.startsWith("git_") || name.startsWith("github_") -> ToolCategory.GIT
            name.startsWith("mcp_") -> ToolCategory.MCP
            name.contains("search") || name.contains("grep") || name.contains("symbol") -> ToolCategory.SEARCH
            name.contains("test") -> ToolCategory.TESTING
            name.startsWith("read_") || name.startsWith("list_") || name.startsWith("view_") -> ToolCategory.FILES
            name.contains("write") || name.contains("edit") || name.contains("replace") -> ToolCategory.EDIT
            name.contains("notebook") -> ToolCategory.NOTEBOOKS
            name.contains("skill") -> ToolCategory.SKILLS
            else -> ToolCategory.AGENT
        }
        val words = name.split('_', '-')
            .filter { it.length > 2 }
            .map { it } + spec.displayName.lowercase().split(' ').filter { it.length > 3 }
        val destructive = name.startsWith("delete") || name.startsWith("remove") ||
            name.startsWith("write") || name.startsWith("create") || name.startsWith("apply") ||
            name.startsWith("send") || name.startsWith("update")
        return ToolMeta(category, words.distinct(), destructive)
    }

    data class Hit(val spec: ToolSpec, val score: Int)

    /**
     * Deterministic search. Scores are additive and bounded, the registry's own order breaks ties,
     * and nothing is invented: only registered names are ever returned.
     */
    fun search(
        query: String,
        mode: ChatMode,
        limit: Int = 8,
        extraNames: List<String> = emptyList(),
    ): List<Hit> {
        val q = query.trim().lowercase()
        val exposed = ToolRegistry.exposed(mode)
        val qTokens = tokens(q)
        val hits = ArrayList<Hit>(exposed.size)
        exposed.forEachIndexed { index, spec ->
            val meta = metaFor(spec)
            var score = 0
            val name = spec.name.lowercase()
            if (q.isNotEmpty()) {
                if (name == q) score += 100
                else if (name.contains(q)) score += 55
                if (meta.keywords.any { it.equals(q, true) }) score += 45
                if (meta.keywords.any { k -> qTokens.any { t -> k.contains(t) && t.length > 2 } }) score += 22
                if (meta.category.name.equals(q, true)) score += 30
                val desc = spec.description.lowercase()
                score += qTokens.count { it.length > 2 && desc.contains(it) } * 6
                score += qTokens.count { it.length > 2 && name.contains(it) } * 8
            }
            if (score > 0) hits.add(Hit(spec, score * 1000 - index))
        }
        // MCP tools are searchable but never in the registry: they are described by the manager.
        for (n in extraNames) {
            val nm = n.lowercase()
            if (q.isEmpty()) continue
            val base = if (nm.contains(q)) 900_000 else if (tokens(nm).any { it.startsWith(q) }) 500_000 else 0
            if (base > 0) {
                val placeholder = ToolSpec(
                    name = n, displayName = n, description = "External MCP tool",
                    schema = "", modes = setOf(mode),
                )
                hits.add(Hit(placeholder, base - 500))
            }
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 1 }

    // ── L2: the core sets ────────────────────────────────────────────────────

    /**
     * The tools a mode cannot do its job without. Small on purpose: everything else is one
     * tool_search away, and the prompt says so.
     */
    private val ASK_CORE = listOf(
        "read_file", "list_files", "file_search", "grep_search", "read_project_structure",
        "get_errors", "tool_search", "web_search", "vncode_askQuestions",
    )
    private val EDIT_CORE = ASK_CORE + listOf(
        "replace_string_in_file", "create_file", "multi_replace_string_in_file", "get_changed_files",
        "diff_files", "checksum",
    )
    private val AGENT_CORE = EDIT_CORE + listOf(
        // 2026-11-11 (Wave 1 Group A): write_file was only ever advertised because the protocol
        // used to ignore the offer. Agent's job includes whole-file writes, so it is core, not a
        // relevance lottery entry. Edit keeps replace/create, which is what its job needs.
        "write_file",
        "run_command", "run_in_terminal", "get_terminal_output", "git_status", "git_diff",
        "git_commit_push", "git_branch", "apply_patch",
    )

    fun core(mode: ChatMode): List<String> = when (mode) {
        ChatMode.ASK -> ASK_CORE
        ChatMode.EDIT -> EDIT_CORE
        ChatMode.AGENT -> AGENT_CORE
    }

    /** Target sizes the owner proposed, evaluated rather than assumed. */
    fun target(mode: ChatMode): Int = when (mode) {
        ChatMode.ASK -> 10
        ChatMode.EDIT -> 16
        ChatMode.AGENT -> 25
    }
}

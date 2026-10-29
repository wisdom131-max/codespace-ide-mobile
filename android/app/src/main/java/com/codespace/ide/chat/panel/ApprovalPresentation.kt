package com.codespace.ide.chat.panel

/**
 * Section 6 — how an approval request DESCRIBES itself.
 *
 * Pure and unit-tested so the row can be reviewed without a device: the dangerous failure
 * here is not layout, it is telling the user "Install Extension / rust-analyzer" when the
 * pending operation is actually something else. Every field is derived from the real pending
 * request, and when the tool is unknown the label degrades to the raw tool name rather than
 * to a friendly-but-wrong sentence.
 *
 * Reference: the VS Code tool-approval card, which leads with a glyph + verb + the HIGHLIGHTED
 * target, then one line of plain description, then Allow (with a scope dropdown) and Skip.
 */
internal object ApprovalPresentation {

    data class View(
        /** Verb-phrase headline, e.g. "Run command", "Write file". */
        val title: String,
        /** The specific thing acted on, emphasised in a pill; null when it would be noise. */
        val target: String?,
        /** One line: what will happen if the user allows it. */
        val description: String,
        /** Glyph for the leading icon (kept as text: no vector asset exists yet). */
        val glyph: String,
    )

    private val titles = mapOf(
        "run_command" to "Run command",
        "write_file" to "Write file",
        "read_file" to "Read file",
        "list_files" to "List files",
        "search_files" to "Search files",
        "git_commit_push" to "Commit and push",
        "git_pull_rebase" to "Pull with rebase",
        "git_branch" to "Branch operation",
        "git_status" to "Git status",
        "git_diff" to "Show diff",
        "install_package" to "Install package",
        "web_fetch" to "Fetch a URL",
        "web_search" to "Search the web",
        "save_secret" to "Save a secret",
        "use_connector" to "Use a connector",
        "create_entity" to "Create data",
        "read_entities" to "Read data",
        "update_entity" to "Update data",
        "delete_entity" to "Delete data",
        "schedule_task" to "Schedule a task",
        "upload_file" to "Upload a file",
    )

    private val glyphs = mapOf(
        "run_command" to "\u2318",
        "write_file" to "\u270E",
        "read_file" to "\u25A4",
        "install_package" to "\u2692",
        "git_commit_push" to "\u2387",
        "web_fetch" to "\u2601",
        "web_search" to "\u2601",
        "save_secret" to "\u26BF",
    )

    /** Build the row's text from the gate's real pending values. */
    fun describe(toolName: String, argsSummary: String): View {
        val title = titles[toolName] ?: toolName
        val summary = argsSummary.trim()
        val target = targetOf(toolName, summary)
        val description = when {
            summary.isEmpty() -> "This operation needs your approval before it runs."
            else -> summary
        }
        return View(
            title = title,
            target = target,
            description = description,
            glyph = glyphs[toolName] ?: "\u2699",
        )
    }

    /**
     * The pill's content: the shortest phrase in the summary that identifies the object -
     * first quoted string if present, else the first path-like token, else the first
     * whitespace-delimited token. Null when there is nothing meaningful to show.
     */
    fun targetOf(toolName: String, summary: String): String? {
        if (summary.isBlank()) return null
        // A shell command must NEVER get a pill. Its first token ("npm", "git", "cd") is not
        // a target: the pill would read "npm" for a build, which describes nothing and invites
        // approval based on the wrong fact. The full command is already the description line.
        if (toolName == "run_command") return null
        val quoted = Regex("\"([^\"]{1,80})\"").find(summary)?.groupValues?.get(1)
        if (quoted != null) return quoted
        val token = summary.split(' ', '\n').firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = token.trim().trimEnd(',', ';', ':')
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > 60) cleaned.take(57) + "\u2026" else cleaned
    }

    /** Labels for the scope menu. Kept here so the text and the semantics stay together. */
    data class ScopeOption(val label: String, val hint: String)

    val scopeOptions: List<ScopeOption> = listOf(
        ScopeOption("Allow once", "Just this operation"),
        ScopeOption("Allow for this session", "Until the app restarts"),
        ScopeOption("Always allow", "Saved in Settings for this tool"),
    )
}

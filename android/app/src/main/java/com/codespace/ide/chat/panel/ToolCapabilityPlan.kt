package com.codespace.ide.chat.panel

/**
 * PROJECT 4.1 (2026-11-16): does the session have what this request actually needs?
 *
 * [ToolRelevance] answers "which tool is best for this request" and does it well. What it cannot
 * answer is the question that decides whether a turn succeeds: a request can need TWO capabilities
 * at once. "Rename this class everywhere" needs a search AND a replace. In Ask mode the replace half
 * does not exist, and today the only way to learn that is to be refused halfway through the work.
 *
 * This object reads the request, names the capabilities it needs, and then classifies each one:
 *
 *  - [State.OFFERED]      a tool that can do it is in this turn's offer;
 *  - [State.LOADABLE]     it exists in this mode but is not offered yet, so load it by name;
 *  - [State.OTHER_MODE]   it exists, but not where the user is: say which mode has it;
 *  - [State.MISSING]      nothing implements it, so the honest answer is "not possible here".
 *
 * The distinction matters because the three cases have three different answers, and collapsing them
 * into "tool unavailable" is what sends a model round the same loop three times.
 *
 * Pure Kotlin: registry and mode are passed in, so every rule below is a JVM test.
 */
internal object ToolCapabilityPlan {

    enum class State { OFFERED, LOADABLE, OTHER_MODE, MISSING }

    /** One thing the request needs, and the tools that could do it, best first. */
    data class Need(val id: String, val label: String, val candidates: List<String>)

    data class Finding(val need: Need, val state: State, val tool: String, val mode: String?)

    data class Assessment(
        val needs: List<Need>,
        val findings: List<Finding>,
        /** True when every need is met by something the turn can actually use. */
        val covered: Boolean,
        val notes: List<String>,
    )

    /**
     * Capability names, each with the tools that provide it. Deliberately small and explicit: a
     * capability table that guesses is worse than none, because a wrong "you can do this" is the
     * failure mode this whole object exists to prevent.
     */
    private val NEEDS: List<Need> = listOf(
        Need("find", "finding text or files", listOf("grep_search", "file_search", "search_files")),
        Need("replace", "changing matched text", listOf("grep_replace_all", "multi_replace_string_in_file", "replace_string_in_file")),
        Need("edit", "editing a file", listOf("write_file", "replace_string_in_file", "multi_replace_string_in_file")),
        Need("read", "reading a file", listOf("read_file")),
        Need("diff", "comparing changes", listOf("diff_files", "git_diff")),
        Need("git", "git history or state", listOf("git_status", "git_log", "git_diff", "git_commit")),
        Need("test", "running tests", listOf("runTests", "run_command")),
        Need("build", "building the project", listOf("run_command", "build_project")),
        Need("web", "searching the web", listOf("web_search", "fetch_url")),
        Need("shell", "running a command", listOf("run_command")),
        Need("structure", "listing or outlining a project", listOf("list_files", "list_directory", "project_overview", "get_project_setup_info")),
    )

    /** Requests that need more than one capability, which is the case ranking cannot see. */
    private fun required(request: String): List<String> {
        val t = request.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        val ids = ArrayList<String>()
        // Reads almost always come first in real work, and a read that was not offered is a wasted
        // round trip rather than a failure, so it is only required when the request says "read".
        // 4.2: a plain search is a capability too. "Where is this used?" asked for nothing before
        // this line, so the coverage report had nothing to say about a one-tool request.
        if (has("find", "grep", "locate", "where is", "where the", "used in", "search files") ||
            (has("search") && !has("search the web", "look up", "documentation", "docs for"))
        ) {
            ids.add("find")
        }
        if (has("rename", "refactor", "replace", "substitute")) {
            ids.add("find")
            ids.add("replace")
        }
        if (has("rename", "refactor") && has("everywhere", "project", "all files", "whole")) ids.add("find")
        if (has("fix", "change", "edit", "update", "write", "add ", "remove ")) ids.add("edit")
        if (has("test", "tests", "spec", "suite")) ids.add("test")
        if (has("build", "compile", "assemble", "gradle")) ids.add("build")
        if (has("commit", "branch", "merge", "stash", "status", "log", "diff")) ids.add("git")
        if (has("diff", "compare")) ids.add("diff")
        if (has("search the web", "look up", "latest", "documentation", "docs for", "release notes")) ids.add("web")
        if (has("run ", "shell", "command", "terminal")) ids.add("shell")
        if (has("structure", "outline", "list files", "what files", "tree")) ids.add("structure")
        if (has("read ", "open the file", "show me")) ids.add("read")
        return ids.distinct()
    }

    /**
     * Read the request, then describe what stands between it and the session.
     *
     * [modesWith] answers "which modes expose this tool", so the note can say "Agent has it" rather
     * than the useless "not available".
     */
    fun assess(
        request: String,
        mode: ChatMode,
        offered: Set<String>,
        modesWith: (String) -> List<ChatMode> = { name -> ChatMode.entries.filter { ToolRegistry.allows(it, name) } },
        exists: (String) -> Boolean = { name -> ToolRegistry.spec(name) != null },
    ): Assessment {
        val ids = required(request)
        val needs = ids.mapNotNull { id -> NEEDS.firstOrNull { it.id == id } }
        val findings = needs.map { need ->
            val present = need.candidates.filter { exists(it) }
            val inMode = present.filter { name -> modesWith(name).contains(mode) }
            val tool = inMode.firstOrNull()
            when {
                tool != null && tool in offered -> Finding(need, State.OFFERED, tool, mode.label)
                tool != null -> Finding(need, State.LOADABLE, tool, mode.label)
                present.isNotEmpty() -> {
                    val other = modesWith(present.first()).firstOrNull()
                    Finding(need, State.OTHER_MODE, present.first(), other?.label)
                }
                else -> Finding(need, State.MISSING, "", null)
            }
        }
        val notes = findings.mapNotNull { finding ->
            when (finding.state) {
                State.OFFERED -> null
                State.LOADABLE -> "load " + finding.tool + " for " + finding.need.label
                State.OTHER_MODE -> finding.need.label + " needs " + finding.tool + ", which " +
                    (finding.mode?.let { "only " + it + " exposes" } ?: "is not exposed") +
                    " (this turn is " + mode.label + ")"
                State.MISSING -> "nothing here can do " + finding.need.label
            }
        }
        return Assessment(needs, findings, notes.isEmpty(), notes)
    }

    /** One line for the offer note, or blank when nothing is missing. */
    fun note(assessment: Assessment): String =
        if (assessment.covered) "" else assessment.notes.joinToString("; ")
}

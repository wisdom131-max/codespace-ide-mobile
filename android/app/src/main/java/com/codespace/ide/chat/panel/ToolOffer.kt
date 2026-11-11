package com.codespace.ide.chat.panel

/**
 * L2 + L3 (owner approval 2026-11-11): which tools a turn OFFERS, and why.
 *
 * Kept beside the loadout but in its own file: the offer is a pure decision about a request, while
 * the loadout is per-task mutable state. One is tested by scoring, the other by lifecycle.
 */
internal object ToolRelevance {

    /** Words a request can use for a capability, mapped to the tool that provides it. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "find" to listOf("grep_search", "file_search", "search_files"),
        "search" to listOf("grep_search", "file_search", "search_files"),
        "locate" to listOf("file_search", "list_files"),
        "compare" to listOf("diff_files", "git_diff"),
        "diff" to listOf("diff_files", "git_diff"),
        "replace" to listOf("grep_replace_all", "replace_string_in_file", "multi_replace_string_in_file"),
        "refactor" to listOf("grep_replace_all", "multi_replace_string_in_file"),
        "everywhere" to listOf("grep_replace_all"),
        "rename" to listOf("move_path", "grep_replace_all"),
        "move" to listOf("move_path"),
        "hash" to listOf("checksum"),
        "verify" to listOf("checksum", "runTests"),
        "integrity" to listOf("checksum"),
        "commit" to listOf("git_commit_push", "git_status"),
        "branch" to listOf("git_branch"),
        "stash" to listOf("git_stash"),
        "tag" to listOf("git_tag"),
        "release" to listOf("git_tag"),
        "history" to listOf("git_diff", "git_status", "github_repo"),
        "research" to listOf("web_search", "web_fetch"),
        "news" to listOf("web_search"),
        "latest" to listOf("web_search", "web_fetch"),
        "trending" to listOf("web_search"),
        "url" to listOf("web_fetch"),
        "page" to listOf("web_fetch"),
        "database" to listOf("read_entities", "create_entity"),
        "notebook" to listOf("copilot_getNotebookSummary", "edit_notebook_file"),
        "test" to listOf("runTests", "test_failure"),
        "build" to listOf("run_command", "install_package"),
        "install" to listOf("install_package"),
        "remember" to listOf("save_memory", "read_memory"),
        "forget" to listOf("delete_memory"),
        "summary" to listOf("read_project_structure", "read_file"),
        "overview" to listOf("read_project_structure"),
    )

    /** A coarse task kind. It exists to make the offer explainable, not to replace scoring. */
    enum class TaskKind { DISCOVER, COMPARE, MODIFY, GIT, RESEARCH, TEST, EXPLAIN, UNKNOWN }

    fun kindOf(request: String): TaskKind {
        val t = request.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("research", "news", "latest", "trending", "what is happening") -> TaskKind.RESEARCH
            has("commit", "branch", "stash", "tag", "rebase", "pull", "git ") -> TaskKind.GIT
            has("compare", "diff", "versus", " vs ") -> TaskKind.COMPARE
            has("test", "failing", "build fails", "verify") -> TaskKind.TEST
            has("find all", "search", "where is", "which files", "locate", "grep") -> TaskKind.DISCOVER
            has("fix", "add", "create", "change", "rename", "edit", "implement", "refactor") -> TaskKind.MODIFY
            has("explain", "how does", "why", "summarize", "summarise", "what does") -> TaskKind.EXPLAIN
            else -> TaskKind.UNKNOWN
        }
    }

    /**
     * Score one tool for one request. Bounded, additive, and stable: the same request always
     * produces the same order, which is what makes the behaviour explainable in a bug report.
     */
    fun score(spec: ToolSpec, meta: ToolMeta, request: String, kind: TaskKind): Int {
        val t = request.lowercase()
        val words = ToolMetaIndex.tokens(t).filter { it.length > 2 }.toSet()
        var s = 0
        if (t.contains(spec.name.lowercase())) s += 60
        SYNONYMS.forEach { (word, tools) ->
            if (t.contains(word) && tools.contains(spec.name)) s += 40
        }
        s += meta.keywords.count { k -> words.any { w -> k.contains(w) || w.contains(k) } } * 10
        s += ToolMetaIndex.tokens(spec.name).count { it in words } * 12
        s += when (kind) {
            TaskKind.DISCOVER -> if (meta.category == ToolCategory.SEARCH || spec.name == "read_file") 18 else 0
            TaskKind.COMPARE -> if (spec.name == "diff_files" || spec.name == "read_file") 22 else 0
            TaskKind.MODIFY -> if (meta.category == ToolCategory.EDIT) 16 else 0
            TaskKind.GIT -> if (meta.category == ToolCategory.GIT) 20 else 0
            TaskKind.RESEARCH -> if (meta.category == ToolCategory.WEB) 22 else 0
            TaskKind.TEST -> if (meta.category == ToolCategory.TESTING || spec.name == "run_command") 16 else 0
            TaskKind.EXPLAIN -> if (meta.category == ToolCategory.FILES || meta.category == ToolCategory.SEARCH) 12 else 0
            TaskKind.UNKNOWN -> 0
        }
        // A cheap tool that answers the same question beats an expensive one.
        s += when (meta.cost) {
            ToolCost.CHEAP -> 4
            ToolCost.NORMAL -> 0
            ToolCost.HEAVY -> -6
        }
        return s
    }

    /** Ranked names for a request, best first, ties broken by registry order. */
    fun rank(mode: ChatMode, request: String, exclude: Set<String> = emptySet()): List<String> {
        val kind = kindOf(request)
        val exposed = ToolRegistry.exposed(mode).filter { it.name !in exclude }
        return exposed.mapIndexed { i, spec -> Triple(spec.name, score(spec, ToolMetaIndex.metaFor(spec), request, kind), i) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}

/**
 * Lean Tool Mode: the offer for one turn, and the reason for it.
 *
 * The note is user-visible, because an offer that cannot be explained is indistinguishable from a
 * bug when a tool "goes missing".
 */
internal object ToolOffer {

    data class Plan(
        val names: List<String>,
        /** One line for the transcript: what was offered and why. Null when nothing was limited. */
        val note: String?,
        val total: Int,
    )

    fun plan(mode: ChatMode, request: String, enabled: Boolean = true): Plan {
        val exposed = ToolRegistry.exposed(mode).map { it.name }
        if (!enabled) return Plan(exposed, null, exposed.size)
        val core = ToolMetaIndex.core(mode).filter { it in exposed }
        val target = ToolMetaIndex.target(mode)
        val picked = LinkedHashSet(core)
        var added = 0
        for (name in ToolRelevance.rank(mode, request, exclude = picked)) {
            if (picked.size >= target) break
            picked.add(name); added++
        }
        // The offer is a subset of what the mode allows - never a superset, and never empty.
        val names = exposed.filter { it in picked }
        val note = if (names.size < exposed.size) {
            "Offered " + names.size + " of " + exposed.size + " tools this turn" +
                (if (added > 0) " (" + added + " matched your request)" else "") +
                "; the rest can be loaded with tool_search."
        } else null
        return Plan(names, note, exposed.size)
    }
}

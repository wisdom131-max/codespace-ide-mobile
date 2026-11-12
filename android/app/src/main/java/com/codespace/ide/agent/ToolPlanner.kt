package com.codespace.ide.agent

/**
 * PROJECT 4.1 / 4.2 - THE PLAN BEFORE THE CALLS.
 *
 * The tool system could already pick a relevant tool for one request. A task that needs five calls
 * in an order had no structure: dependencies were implied by whatever the model emitted next. This
 * turns an intent into steps, edges and waves, so execution can be sequenced where it must be and
 * parallelised only where it genuinely is independent.
 *
 * The rules that matter for safety:
 *  - a wave only ever contains steps with no unfinished dependency, so nothing runs on output that
 *    does not exist yet;
 *  - anything that writes is put in a later wave than the read it must be based on;
 *  - the same tool with the same arguments appears once, and a step already completed in this task
 *    is dropped rather than repeated.
 *
 * Planning is text only. It never executes, and it never widens an approval.
 */
object ToolPlanner {

    enum class Goal { SEARCH, EDIT, VERIFY, GIT, RESEARCH, INSPECT, GENERIC }

    data class Step(
        val id: String,
        val tool: String,
        val dependsOn: List<String> = emptyList(),
        val writes: Boolean = false,
        val arguments: String = "",
        /** Why this step exists, shown in the Thinking Tree as progress, never as an answer. */
        val intent: String = "",
    )

    data class Plan(val goal: Goal, val steps: List<Step>, val notes: List<String> = emptyList()) {

        /** Dependency layers. Each inner list may run concurrently; ordering inside it is stable. */
        fun waves(): List<List<Step>> {
            val done = HashSet<String>()
            val left = steps.toMutableList()
            val out = ArrayList<List<Step>>()
            while (left.isNotEmpty()) {
                val ready = left.filter { s -> s.dependsOn.all { it in done } }.sortedBy { it.id }
                if (ready.isEmpty()) break // a cycle: nothing further can run, and the caller is told
                out.add(ready)
                ready.forEach { done.add(it.id) }
                left.removeAll(ready)
            }
            return out
        }

        fun problems(): List<String> {
            val ids = steps.map { it.id }.toSet()
            val out = ArrayList<String>()
            steps.forEach { s -> s.dependsOn.filter { it !in ids }.forEach { out.add(s.id + " needs missing " + it) } }
            val seen = HashSet<String>()
            steps.forEach { s ->
                val key = s.tool + "\u0000" + s.arguments
                if (!seen.add(key)) out.add("duplicate step: " + s.tool)
            }
            // A write that nothing read before it is the shape of a blind destructive edit.
            steps.filter { it.writes && it.dependsOn.isEmpty() && steps.none { p -> !p.writes && p.id == it.id } }
                .forEach { out.add(it.id + " writes without a preceding read") }
            if (waves().sumOf { it.size } < steps.size) out.add("dependency cycle")
            return out
        }

        /** Drop steps whose exact tool+arguments already produced a result in this task. */
        fun reuse(completed: Set<String>): Plan =
            copy(steps = steps.filter { it.tool + "\u0000" + it.arguments !in completed })

        /** The verification step a write should be followed by, when one exists in the registry. */
        fun needsVerification(available: Collection<String>): Boolean =
            steps.any { it.writes } && steps.none { !it.writes && it.tool in VERIFY_TOOLS }
    }

    /** Read-back tools that can prove a write landed. */
    val VERIFY_TOOLS = setOf(
        "read_file", "file_exists", "grep_search", "git_diff", "git_status", "zip_list",
        "get_diagnostics", "list_dir", "checksum",
    )

    fun goalOf(request: String): Goal {
        val t = request.lowercase()
        fun any(vararg w: String) = w.any { t.contains(it) }
        return when {
            any("research", "sources", "evidence", "compare claims") -> Goal.RESEARCH
            any("git ", "commit", "branch", "stash", "rebase", "merge conflict") -> Goal.GIT
            any("test", "verify", "check that", "does it compile", "diagnostics") -> Goal.VERIFY
            any("edit", "rename", "refactor", "replace", "fix", "change", "write") -> Goal.EDIT
            any("find", "search", "where is", "locate", "grep") -> Goal.SEARCH
            any("explain", "review", "inspect", "how does", "what does") -> Goal.INSPECT
            else -> Goal.GENERIC
        }
    }

    /**
     * A plan for the common shapes. Steps are drawn from [available] only: a step whose tool is not
     * offered is omitted with a note, rather than planned and then failing.
     */
    fun plan(request: String, available: Collection<String>): Plan {
        val goal = goalOf(request)
        val have = available.toHashSet()
        val notes = ArrayList<String>()
        fun has(t: String) = t in have
        val steps = ArrayList<Step>()

        fun add(id: String, tool: String, deps: List<String> = emptyList(), writes: Boolean = false, intent: String = "") {
            if (!has(tool)) { notes.add("not planned: " + tool + " is not offered here"); return }
            steps.add(Step(id, tool, deps, writes, intent = intent))
        }

        when (goal) {
            Goal.SEARCH -> {
                add("search", "grep_search", intent = "find the code that answers the request")
                add("read", "read_file", listOf("search"), intent = "read what the search pointed at")
            }
            Goal.INSPECT -> {
                add("list", "list_dir", intent = "see the shape of the project")
                add("read", "read_file", listOf("list"), intent = "read the relevant file")
                add("diagnose", "get_diagnostics", listOf("read"), intent = "check the file's state")
            }
            Goal.EDIT -> {
                add("search", "grep_search", intent = "locate the edit site")
                add("read", "read_file", listOf("search"), intent = "read before writing")
                add("write", "replace_string_in_file", listOf("read"), writes = true, intent = "make the change")
                add("verify", "get_diagnostics", listOf("write"), intent = "read back what the edit did")
            }
            Goal.VERIFY -> {
                add("diagnose", "get_diagnostics", intent = "collect real compiler state")
                add("test", "run_command", listOf("diagnose"), writes = true, intent = "run the checks")
            }
            Goal.GIT -> {
                add("status", "git_status", intent = "see what changed")
                add("diff", "git_diff", listOf("status"), intent = "read the change itself")
                add("commit", "git_commit", listOf("diff"), writes = true, intent = "record the change")
            }
            Goal.RESEARCH -> {
                add("search", "web_search", intent = "gather candidate sources")
                add("fetch", "web_fetch", listOf("search"), intent = "read the strongest sources")
            }
            Goal.GENERIC -> add("inspect", "read_project_structure", intent = "understand the task first")
        }
        return Plan(goal, steps, notes)
    }
}

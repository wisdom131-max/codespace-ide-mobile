package com.codespace.ide.agent

import org.json.JSONObject

/**
 * A project task runner, to VS Code's `tasks.json` contract (2026-10-31, owner request).
 *
 * This did not exist: the only `tasks.json` in the app is AgentScheduler's own job file in app
 * storage. This reads the PROJECT's `.vscode/tasks.json`, which is the file VS Code uses.
 *
 * What is supported, and deliberately not more:
 *  - `label`, `type` (shell/process), `command`, `args`, `options.cwd`, `group`, `dependsOn`.
 *  - Order-only `dependsOn` (`"dependsOrder": "sequence"`), which is the VS Code default when it
 *    is a list. A `parallel` order is accepted and run in list order too, because on a phone a
 *    background fan-out of build steps is a good way to lose the output that explains the failure.
 *  - `problemMatcher` is STORED but not interpreted. VS Code needs it to paint squiggles from a
 *    matcher regex; this app already has an LSP for that, and a second, weaker source of errors
 *    would be worse than none.
 *
 * What is not supported, and reports itself rather than failing silently: task `type: "npm"`,
 * `"gulp"` and the other contributed types, `presentation.*` (the panel has one presentation),
 * `inputs` with `${input:...}` prompts, and variables other than `${workspaceFolder}` and
 * `${file}`. An unknown variable is left in the command text so the failure is visible in the
 * output instead of being silently eaten.
 */
object ProjectTasksSpec {

    enum class Group { BUILD, TEST, NONE }

    data class Task(
        val label: String,
        val type: String,
        val command: String,
        val args: List<String>,
        val cwd: String?,
        val group: Group,
        val dependsOn: List<String>,
        val problemMatcher: String?,
    )

    data class ReadOk(val tasks: List<Task>) : ReadResult
    data class ReadFailed(val reason: String) : ReadResult

    sealed interface ReadResult

    /** JSON with comments is legal in tasks.json and is what VS Code writes. */
    fun stripComments(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        var inString = false
        while (i < raw.length) {
            val ch = raw[i]
            if (inString) {
                out.append(ch)
                if (ch == '\\' && i + 1 < raw.length) { out.append(raw[i + 1]); i += 2; continue }
                if (ch == '"') inString = false
                i++
                continue
            }
            if (ch == '"') { inString = true; out.append(ch); i++; continue }
            if (ch == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) i++
                    i += 2
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun parse(raw: String): ReadResult {
        val obj = try {
            JSONObject(stripComments(raw))
        } catch (t: Throwable) {
            return ReadFailed("tasks.json is not valid JSON (" + (t.message ?: "parse error") + ")")
        }
        val array = obj.optJSONArray("tasks")
            ?: return ReadFailed("tasks.json has no \"tasks\" array")
        val tasks = ArrayList<Task>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val label = o.optString("label").ifBlank { "task " + (i + 1) }
            val type = o.optString("type").ifBlank { "shell" }
            val command = o.optString("command")
            if (command.isBlank()) {
                // A task with no command (or a contributed type) cannot run here; saying so per
                // task is better than hiding it from the list.
                tasks.add(Task(label, type, "", emptyList(), null, groupOf(o), depsOf(o), null))
                continue
            }
            val args = ArrayList<String>()
            o.optJSONArray("args")?.let { a ->
                for (j in 0 until a.length()) args.add(a.optString(j))
            }
            val cwd = o.optJSONObject("options")?.optString("cwd")?.takeIf { it.isNotBlank() }
            val matcher = o.opt("problemMatcher")?.let { m ->
                when (m) {
                    is String -> m
                    is org.json.JSONArray -> m.optJSONObject(0)?.optString("owner")
                    else -> null
                }
            }
            tasks.add(Task(label, type, command, args, cwd, groupOf(o), depsOf(o), matcher))
        }
        return ReadOk(tasks)
    }

    private fun groupOf(o: JSONObject): Group {
        val g = o.opt("group")
        val name = when (g) {
            is String -> g
            is JSONObject -> g.optString("kind")
            else -> null
        } ?: return Group.NONE
        return when (name) {
            "build" -> Group.BUILD
            "test" -> Group.TEST
            else -> Group.NONE
        }
    }

    private fun depsOf(o: JSONObject): List<String> {
        val g = o.opt("dependsOn") ?: return emptyList()
        return when (g) {
            is String -> listOf(g)
            is org.json.JSONArray -> (0 until g.length()).mapNotNull { g.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
    }

    /** VS Code order: build, then test, then the rest, each alphabetical by label. */
    fun ordered(tasks: List<Task>): List<Task> = tasks.sortedWith(
        compareBy({ when (it.group) { Group.BUILD -> 0; Group.TEST -> 1; Group.NONE -> 2 } }, { it.label.lowercase() })
    )

    fun isSupported(task: Task): Boolean = task.command.isNotBlank() && (task.type == "shell" || task.type == "process")

    fun unsupportedReason(task: Task): String = when {
        task.command.isBlank() -> "This task has no command this app can run (type: " + task.type + ")"
        task.type != "shell" && task.type != "process" -> "Task type \"" + task.type + "\" is contributed by an extension, which this app does not run"
        else -> ""
    }

    /**
     * The shell line for a task. Args are quoted for a POSIX shell: a task that builds a string
     * with spaces must not become three arguments when it runs.
     */
    fun commandLine(task: Task, projectRoot: String): String {
        val parts = ArrayList<String>()
        parts.add(expand(task.command, projectRoot))
        task.args.forEach { parts.add(quote(expand(it, projectRoot))) }
        val cd = task.cwd?.let { expand(it, projectRoot) }
        val line = parts.joinToString(" ")
        return if (cd.isNullOrBlank()) line else "cd " + quote(cd) + " && " + line
    }

    private fun expand(text: String, projectRoot: String): String =
        text.replace("\${workspaceFolder}", projectRoot)

    private fun quote(value: String): String {
        if (value.isEmpty()) return "''"
        val safe = value.all { it.isLetterOrDigit() || it in "-_./=:@%+," }
        return if (safe) value else "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * dependsOn is run in order, and a cycle is broken rather than followed. A build task that
     * depends on itself would otherwise run the same command until something gives.
     */
    fun executionOrder(label: String, tasks: List<Task>): List<Task> {
        val byLabel = tasks.associateBy { it.label }
        val out = ArrayList<Task>()
        val seen = HashSet<String>()
        fun visit(current: String) {
            if (!seen.add(current)) return
            val task = byLabel[current] ?: return
            task.dependsOn.forEach { visit(it) }
            out.add(task)
        }
        visit(label)
        return out.filter { isSupported(it) }
    }
}

package com.codespace.ide.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs the tasks declared in the PROJECT's `.vscode/tasks.json` (2026-10-31, owner request).
 *
 * The existing TaskRunner panel runs a fixed catalogue: build debug, clean, lint, test. That is
 * useful, and it is not what VS Code calls tasks: VS Code reads `tasks.json` so a repository can
 * declare its own commands, its own working directory and its own ordering, and those travel with
 * the repo. That was the actual gap.
 *
 * Everything here goes through the same trust gate the catalogue uses: a task is a command, and
 * running a command in a project that has never been trusted is exactly the action the gate is
 * for. Output is captured and shown in the same log area, so a failing task explains itself.
 */
object ProjectTaskRunner {

    data class State(
        val runningLabel: String? = null,
        val lastLabel: String? = null,
        val output: String = "",
        val ok: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun tasksFile(projectPath: String) = java.io.File(projectPath, ".vscode/tasks.json")

    /** Reads the project's tasks.json. A missing file is not an error, it is an empty list. */
    fun load(projectPath: String): ProjectTasksSpec.ReadResult {
        val file = tasksFile(projectPath)
        if (!file.exists()) return ProjectTasksSpec.ReadOk(emptyList())
        val raw = try {
            file.readText()
        } catch (t: Throwable) {
            return ProjectTasksSpec.ReadFailed("Could not read .vscode/tasks.json (" + (t.message ?: "unknown") + ")")
        }
        return ProjectTasksSpec.parse(raw)
    }

    fun clear() {
        _state.value = State()
    }

    /**
     * Runs [label] and everything it depends on, in order, stopping at the first failure the way
     * VS Code's sequence order does: a build that failed is not a reason to run the test that
     * consumes it.
     */
    suspend fun run(context: Context, projectPath: String, label: String): String {
        val loaded = load(projectPath)
        if (loaded is ProjectTasksSpec.ReadFailed) {
            _state.value = State(lastLabel = label, output = loaded.reason, ok = false)
            return loaded.reason
        }
        val all = (loaded as ProjectTasksSpec.ReadOk).tasks
        val ordered = ProjectTasksSpec.executionOrder(label, all)
        if (ordered.isEmpty()) {
            val reason = "Task \"" + label + "\" has no command this app can run."
            _state.value = State(lastLabel = label, output = reason, ok = false)
            return reason
        }
        if (!com.codespace.ide.security.TrustState.awaitTrusted(context, projectPath)) {
            val refused = "Project not trusted yet - task launch was cancelled at the trust prompt."
            _state.value = State(lastLabel = label, output = refused, ok = false)
            return refused
        }
        val log = StringBuilder()
        for (task in ordered) {
            _state.value = State(runningLabel = task.label, lastLabel = task.label, output = log.toString(), ok = null)
            val line = ProjectTasksSpec.commandLine(task, projectPath)
            log.append("> ").append(task.label).append("\n$ ").append(line).append("\n")
            val out = try {
                com.codespace.ide.terminal.ProotInstaller.execOnce(
                    context, line, workdir = projectPath, timeoutSeconds = 600L, logToOutput = true,
                )
            } catch (t: Throwable) {
                "Task failed to start: " + (t.message ?: "unknown")
            }
            log.append(out.trim()).append("\n\n")
            val failed = looksFailed(out)
            _state.value = State(
                runningLabel = if (failed) null else null,
                lastLabel = task.label,
                output = log.toString(),
                ok = !failed,
            )
            if (failed) return "Task \"" + task.label + "\" failed. See the output above."
        }
        return "Ran " + ordered.size + " task" + (if (ordered.size == 1) "" else "s") + "."
    }

    /**
     * The executor returns text, not an exit code, so failure is judged from the text. Anything
     * that says "error"/"failed" is treated as a failure: a green tick on a failed build is worse
     * than asking the user to read the output.
     */
    fun looksFailed(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("error running command") ||
            lower.contains("timed out after") ||
            lower.contains("build failed") ||
            lower.startsWith("error:") ||
            lower.contains("\nerror:")
    }
}

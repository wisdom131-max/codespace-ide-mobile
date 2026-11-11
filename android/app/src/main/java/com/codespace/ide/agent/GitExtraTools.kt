package com.codespace.ide.agent

import java.io.File

/**
 * S1 (owner approval 2026-11-11): stash and tag, the two git operations the model reached for and
 * did not have. It used to run `git stash` through run_command, which means: no structured output,
 * no error classification, and a permission decision made about a shell command instead of about a
 * git operation.
 *
 * The runner is injected so the logic is tested with a fake and the app keeps the ONE executor that
 * already knows about safe.directory and credentials (GitCommandExecutor via AgentTools.gitRun).
 */
object GitExtraTools {

    /** The injected git executor: AgentTools.gitRun in the app, a fake in tests. */
    fun interface Runner {
        fun run(args: List<String>): String
    }

    fun stash(action: String, message: String, repo: File, run: Runner): String {
        return when (action.lowercase().ifBlank { "push" }) {
            "push", "save" -> {
                val args = mutableListOf("stash", "push")
                if (message.isNotBlank()) args.addAll(listOf("-m", message.trim().take(200)))
                val out = run.run(args)
                if (out.isBlank()) "Stashed your changes." else "Stashed: " + out.take(2000)
            }
            "list" -> run.run(listOf("stash", "list")).ifBlank { "No stashes." }.take(4000)
            "pop" -> run.run(listOf("stash", "pop")).take(4000).ifBlank { "Nothing to pop." }
            "apply" -> run.run(listOf("stash", "apply")).take(4000).ifBlank { "Nothing to apply." }
            "drop" -> run.run(listOf("stash", "drop")).take(4000).ifBlank { "Nothing to drop." }
            else -> "Unknown stash action '$action'. Use: push, list, pop, apply, drop"
        }
    }

    fun tag(action: String, name: String, message: String, repo: File, run: Runner): String {
        val safeName = name.trim()
        return when (action.lowercase().ifBlank { "list" }) {
            "list" -> run.run(listOf("tag", "--sort=-creatordate")).ifBlank { "No tags." }.take(4000)
            "create" -> {
                if (safeName.isEmpty()) return "A tag needs a name."
                if (!validTagName(safeName)) return "Refused: '$safeName' is not a valid tag name."
                val args = mutableListOf("tag")
                if (message.isNotBlank()) args.addAll(listOf("-a", safeName, "-m", message.trim().take(200)))
                else args.add(safeName)
                val out = run.run(args)
                if (out.isBlank()) "Created tag '$safeName'." else "Created tag '$safeName': " + out.take(1000)
            }
            "delete" -> {
                if (safeName.isEmpty()) return "Which tag should be deleted? Pass name."
                if (!validTagName(safeName)) return "Refused: '$safeName' is not a valid tag name."
                run.run(listOf("tag", "-d", safeName)).let { "Deleted tag '$safeName'." + if (it.isBlank()) "" else " " + it.take(500) }
            }
            "show" -> {
                if (safeName.isEmpty()) return "Which tag? Pass name."
                run.run(listOf("show", safeName)).take(4000)
            }
            else -> "Unknown tag action '$action'. Use: list, create, delete, show"
        }
    }

    /**
     * Reject anything git would have to quote, plus a leading dash (which git would read as a flag)
     * and the ref characters git itself forbids.
     */
    fun validTagName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/")) return false
        if (name.contains("..") || name.endsWith(".lock") || name.endsWith(".")) return false
        return name.none { it.isWhitespace() || it in "~^:?*[\\\"'" }
    }
}

package com.codespace.ide.scm

/**
 * 2026-11-05: the prompt the Source Control AI button asks the chat with.
 *
 * Pure so the wording is asserted instead of discovered, and deliberately free of diff text: the
 * chat has read_file / search_files and the repository on disk, so pasting a diff would spend the
 * context budget twice and go stale the moment a file is touched again.
 *
 * Reference for what is asked: VS Code's own commit-message generation produces a subject line that
 * fits a git log (`generateCommitMessage` in the Copilot SCM integration offers the message into the
 * input box). This app has no reply-to-panel channel yet, so the answer lands in the conversation
 * and the owner pastes it - stated here because it is a limitation, not a design choice.
 */
object CommitMessagePrompt {

    fun build(staged: List<String>, unstaged: List<String>): String {
        val all = (staged + unstaged).distinct().filter { it.isNotBlank() }
        if (all.isEmpty()) {
            return "There are no changes to describe yet. Tell me what is currently modified in " +
                "this repository, then write a commit message for it."
        }
        val shown = all.take(20).joinToString(", ")
        val more = if (all.size > 20) " and " + (all.size - 20) + " more" else ""
        val scope = if (staged.isNotEmpty()) "staged" else "unstaged"
        return "Write a conventional commit message for the " + scope + " changes in this " +
            "repository: " + shown + more + ". Read the diffs yourself before answering. " +
            "Reply with the subject line only (max 72 characters), then one blank line, then up to " +
            "three bullet points. Do not change any files."
    }
}

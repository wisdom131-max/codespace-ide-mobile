package com.codespace.ide.chat

/** A model sentence is never an execution receipt. Evidence belongs to this turn only. */
class ToolExecutionEvidence {
    private val records = mutableListOf<Pair<String, String>>()

    fun record(tool: String, approved: Boolean, staged: Boolean, result: String): String {
        val status = when {
            !approved -> "NOT EXECUTED (approval denied or timed out)"
            result.startsWith("write_file REFUSED") -> "NOT EXECUTED (staging refused)"
            tool == "write_file" && staged -> "STAGED ONLY (not written; Apply is required)"
            tool == "plan" -> "PLAN ONLY (no plan steps executed)"
            listOf("Error executing", "Error running", "Exit code ", "Timed out", "File not found:",
                "Directory not found:", "Unknown tool:", "Unknown manager:", "Unsupported cron", "Skipped", "REFUSED")
                .any { result.startsWith(it, ignoreCase = true) } -> "FAILED OR REFUSED (not a success receipt)"
            else -> "EXECUTED; result received (prose completion claims remain unverified)"
        }
        records.add(tool to status)
        return status
    }

    fun denied(tool: String) { records.add(tool to "NOT EXECUTED (custom-mode allowlist)") }

    fun finish(text: String): String {
        if (records.isEmpty()) return "**NOT EXECUTED: no tool ran in this turn.** The model text below is not an action receipt.\n\n" + text
        return "**Execution evidence for this turn:**\n" + records.joinToString("\n") { "- " + it.first + ": " + it.second } +
            "\n\n**Unverified model summary:** Only the executions listed above are evidenced. This text cannot verify additional actions or successful side effects.\n\n" + text
    }
}

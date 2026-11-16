package com.codespace.ide.agent.runtime

/**
 * PROJECT THREE RUNTIME (2026-11-16): one answer to "did that call fail?".
 *
 * The tool layer reports failures as text, not as exceptions, because a tool that returns a sentence
 * is easier for the model to read than a stack trace. That means every caller that wants to know
 * whether it should recover, retry or record a failure has to interpret the same conventions. Doing
 * that in four places is how the recovery path and the reflection path start disagreeing about
 * whether the same output was a success.
 *
 * The conventions this recognises are the ones the tool layer already writes:
 *
 *  - `Error executing <tool>:` from the dispatch catch
 *  - `Refused:` for a deliberate refusal (it did not run, which is a failure for the caller)
 *  - a bare `Unknown ...` / `Refused ...` opener used by a few older tools
 *
 * It is deliberately conservative: text that merely mentions an error inside a successful result
 * ("0 errors", "fixed the error in line 4") is NOT a failure, because treating it as one is how a
 * working call gets retried and a write happens twice.
 */
internal object ToolFailureProbe {

    private val hardPrefixes = listOf(
        "error executing ",
        "refused:",
        "refused ",
        "unknown tool",
        "tool not found",
        "nothing to write:",
    )

    fun looksFailed(output: String): Boolean {
        val text = output.trimStart()
        if (text.isEmpty()) return true
        val lower = text.lowercase()
        if (hardPrefixes.any { lower.startsWith(it) }) return true
        // A refusal on its own line, mid-result, still means the call did not do its work.
        return lower.lineSequence().any { line ->
            val t = line.trimStart()
            t.startsWith("refused:") || t.startsWith("error executing ")
        }
    }
}

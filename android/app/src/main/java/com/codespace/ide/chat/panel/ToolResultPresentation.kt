package com.codespace.ide.chat.panel

/**
 * Area 4 - how a tool result is PRESENTED, as pure data the renderer can draw.
 *
 * The transcript already stores tool calls and results with stable ids and the Thinking Tree
 * groups them. What was missing is a single answer to "what kind of result is this, what is the
 * one line that summarises it, and how much of the body is shown collapsed". Without that, a 40
 * line `run_command` result and a red error were both drawn as plain text, and a truncated
 * result looked exactly like a complete one.
 *
 * Deliberately pure: no Compose, no state. Everything here is asserted in ToolResultTest, and the
 * renderer only has to draw the fields. Bounded so a huge result cannot flood the transcript.
 */
internal object ToolResultPresentation {

    /** How a result should read at a glance. Order matters: the first match wins. */
    internal enum class Kind {
        /** The tool failed; [ToolPresentation.title] says why in words, not a stack trace. */
        ERROR,
        /** Ran, but the output was capped or the tool said it was partial. */
        PARTIAL,
        /** Ran and produced nothing worth showing (empty list, no matches). */
        EMPTY,
        /** Ran and produced output. */
        RESULT,
    }

    private const val SUMMARY_CHARS = 160
    private const val COLLAPSED_LINES = 6
    private const val BODY_LINES = 60

    /** What the renderer draws for one tool result. */
    internal data class ToolPresentation(
        val kind: Kind,
        val title: String,
        /** The always-visible line. Never longer than [SUMMARY_CHARS]. */
        val summary: String,
        /** Lines behind the chevron. Empty when the summary is the whole story. */
        val body: List<String>,
        /** True when [body] was cut, so the renderer can say so instead of pretending. */
        val bodyTruncated: Boolean,
        /** True when the result is short enough to show inline with no chevron. */
        val collapsible: Boolean,
    )

    /** Markers the tool layer appends when it caps output. Kept in sync with ToolRegistry's docs. */
    private val TRUNCATION_MARKERS = listOf(
        "truncated", "output capped", "…more", "...more", "more lines", "omitted",
    )

    /**
     * Builds the presentation for [name]'s result.
     *
     * [ok] comes from the executor, so it is trusted. Everything in [output] is untrusted data
     * from a tool and is only ever measured, never interpreted as an instruction.
     */
    fun present(name: String, ok: Boolean, output: String?): ToolPresentation {
        val text = output.orEmpty()
        val lines = text.lines().filter { it.isNotBlank() }
        val kind = when {
            !ok -> Kind.ERROR
            text.isBlank() -> Kind.EMPTY
            looksCapped(text) -> Kind.PARTIAL
            else -> Kind.RESULT
        }
        val summary = when (kind) {
            Kind.ERROR -> firstMeaningfulLine(lines).take(SUMMARY_CHARS)
            Kind.EMPTY -> "No output"
            else -> firstMeaningfulLine(lines).take(SUMMARY_CHARS)
        }
        val title = when (kind) {
            Kind.ERROR -> name + " failed"
            Kind.PARTIAL -> name + " (partial)"
            Kind.EMPTY -> name + " (empty)"
            Kind.RESULT -> name
        }
        val bodyLines = if (lines.size <= 1) emptyList() else lines.drop(1)
        val bounded = bodyLines.take(BODY_LINES)
        return ToolPresentation(
            kind = kind,
            title = title,
            summary = summary.ifBlank { "No output" },
            body = bounded,
            bodyTruncated = bodyLines.size > bounded.size,
            collapsible = bodyLines.size > COLLAPSED_LINES,
        )
    }

    private fun looksCapped(text: String): Boolean {
        // Only the tail is examined: a legitimate result may mention the word "truncated" in the
        // middle of a file listing and must not be branded partial because of it.
        val tail = text.takeLast(400).lowercase()
        return TRUNCATION_MARKERS.any { tail.contains(it) }
    }

    private fun firstMeaningfulLine(lines: List<String>): String =
        lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * The one-line state a finished turn gets in the transcript header.
     *
     * Area 6 owns the truncation evidence; this only chooses the words, so a turn that ended
     * early never reads as "done".
     */
    fun turnSummary(toolCount: Int, kind: Kind, truncated: Boolean): String = when {
        truncated -> "Stopped early: output limit reached. Continue to finish."
        kind == Kind.ERROR -> "Finished with an error after " + toolCount + " tool" + plural(toolCount) + "."
        toolCount == 0 -> "Answered."
        else -> "Done, " + toolCount + " tool" + plural(toolCount) + " used."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}

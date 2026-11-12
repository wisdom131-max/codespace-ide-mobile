package com.codespace.ide.agent

/**
 * PROJECT 4.3 - A TOOL THAT RETURNED IS NOT A TOOL THAT WORKED.
 *
 * `executeTool` reports what a body produced. That text is not proof of anything: a shell command
 * that prints "command not found" exits normally, a write can silently do nothing, a grep with no
 * matches is a legitimate empty answer, and a truncated result is not a complete one. This class
 * makes that distinction explicit so the model is told which of those it is holding.
 */
object ToolResultCheck {

    enum class Verdict {
        /** Output looks like the operation did what was asked. */
        OK,
        /** Empty because there genuinely was nothing to return (no matches, empty directory). */
        LEGITIMATELY_EMPTY,
        /** Real output, but cut off by an output limit. */
        INCOMPLETE,
        /** Returned normally while saying it failed, or empty when content was expected. */
        SUSPICIOUS_SUCCESS,
        /** Clearly an error. */
        FAILED,
        /** Expected shape absent: not JSON where JSON was asked for, no diff where a diff was asked. */
        MALFORMED,
    }

    data class Check(val verdict: Verdict, val reason: String, val needsVerify: Boolean) {
        val successful: Boolean get() = verdict == Verdict.OK || verdict == Verdict.LEGITIMATELY_EMPTY
    }

    /** Tools whose empty output means "nothing matched", not "something broke". */
    private val EMPTY_IS_FINE = setOf(
        "grep_search", "file_search", "search_files", "list_dir", "git_status", "git_log",
        "read_cell_output", "zip_list", "list_terminals", "list_processes",
    )

    /** Tools that must return content to have done anything. */
    private val CONTENT_EXPECTED = setOf(
        "read_file", "web_fetch", "web_search", "git_diff", "read_project_structure",
        "get_diagnostics", "http_request",
    )

    private val ERROR_MARKERS = listOf(
        "command not found", "no such file", "permission denied", "operation not permitted",
        "fatal:", "traceback (most recent call last)", "error:", "exception:", "failed to",
        "cannot open", "not a git repository", "unknown option", "usage:",
    )

    private val TRUNCATION_MARKERS = listOf(
        "(partial)", "truncated", "output limit", "chars omitted", "… [", "[cut at",
    )

    fun check(tool: String, output: String, threw: Throwable? = null, expectsJson: Boolean = false): Check {
        if (threw != null) {
            return Check(Verdict.FAILED, "the tool threw " + (threw::class.simpleName ?: "an error"), false)
        }
        val text = output.trim()
        if (isTruncated(text)) {
            return Check(Verdict.INCOMPLETE, "the result was cut off by an output limit", true)
        }
        if (expectsJson && text.isNotEmpty() && !looksLikeJson(text)) {
            return Check(Verdict.MALFORMED, "expected JSON and got something else", true)
        }
        val marker = ERROR_MARKERS.firstOrNull { text.lowercase().contains(it) }
        if (marker != null) {
            return Check(Verdict.SUSPICIOUS_SUCCESS, "the tool returned normally but its output says \"" + marker + "\"", true)
        }
        if (text.isEmpty()) {
            return when {
                tool in EMPTY_IS_FINE -> Check(Verdict.LEGITIMATELY_EMPTY, "nothing matched, which is a valid answer", false)
                tool in CONTENT_EXPECTED -> Check(Verdict.SUSPICIOUS_SUCCESS, "no content came back from " + tool, true)
                else -> Check(Verdict.LEGITIMATELY_EMPTY, "the tool had nothing to report", false)
            }
        }
        return Check(Verdict.OK, "the result is present and readable", false)
    }

    fun isTruncated(text: String): Boolean {
        val t = text.lowercase()
        return TRUNCATION_MARKERS.any { t.contains(it) }
    }

    private fun looksLikeJson(text: String): Boolean {
        val c = text.first()
        return (c == '{' && text.last() == '}') || (c == '[' && text.last() == ']')
    }

    /**
     * The one line to add to a result the model is about to trust, or empty when nothing needs
     * saying. Written for the model, not shown as internal detail.
     */
    fun note(tool: String, check: Check): String = when (check.verdict) {
        Verdict.OK, Verdict.LEGITIMATELY_EMPTY -> ""
        Verdict.INCOMPLETE -> "Note: this result was truncated. Do not describe the operation as complete; " +
            "ask for a narrower range if more is needed."
        Verdict.SUSPICIOUS_SUCCESS -> "Note: " + tool + " returned without failing, but " + check.reason +
            ". Verify with a read-back before treating this as done."
        Verdict.FAILED -> "Note: " + check.reason + ". The operation did not succeed."
        Verdict.MALFORMED -> "Note: the output was not in the expected form (" + check.reason +
            "). Do not parse it as a result."
    }
}

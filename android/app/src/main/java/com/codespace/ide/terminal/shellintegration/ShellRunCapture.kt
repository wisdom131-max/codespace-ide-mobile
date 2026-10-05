package com.codespace.ide.terminal.shellintegration

/**
 * D15-b B2 — SHELL-RUN CAPTURE MODEL (pure Kotlin, zero Android imports so the
 * JVM suite covers the slicing logic host-side).
 *
 * A "shell run" is one command typed at the interactive shell, observed through
 * OSC 633 marks: E (command line) -> C (output start) -> D (command complete,
 * with exit code). The pane-side recorder captures a transcript WINDOW between
 * C and D so the chat attach picker can offer "the last thing I ran" with its
 * real output — VS Code's shell-integration command history analog.
 */
object ShellRunCapture {

    /** Max chars of captured output kept per run (matches attach-picker economics). */
    const val MAX_OUTPUT_CHARS = 4000

    /** One observed shell command. [output] is null when the window was uncapturable. */
    data class ShellRun(
        val command: String,
        val exitCode: Int?,
        val cwd: String?,
        val output: String?,
        val nonceValidated: Boolean,
        val capturedAtMs: Long,
    )

    /**
     * Slice the command-output window out of the live transcript.
     *
     * [startLen] is the transcript length captured at OUTPUT_START (mark C); the
     * output window is everything appended after that, up to D. Clamp rules:
     *   - null transcript or startLen < 0  -> null (window unknowable)
     *   - transcript SHORTER than startLen -> the circular buffer evicted the
     *     window mid-command; return the whole surviving transcript (best effort,
     *     honest about the loss) rather than crashing or lying with a wrong slice.
     *   - result capped to [MAX_OUTPUT_CHARS] from the END (newest output wins).
     */
    fun sliceOutputWindow(transcript: String?, startLen: Int): String? {
        if (transcript == null) return null
        if (startLen < 0) return null
        val window = if (transcript.length < startLen) transcript else transcript.substring(startLen)
        val trimmed = window.takeLast(MAX_OUTPUT_CHARS)
        return trimmed.ifEmpty { null }
    }
}

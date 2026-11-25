package com.codespace.ide.agent

/**
 * OWNER REQUEST (2026-11-25): "You said we needed the repeated action detector I need it."
 *
 * The one self-heal mechanism the research said we were missing. Aider reflects, Cline and Roo Code
 * stop after a budget of consecutive mistakes, OpenHands detects repeated actions. This is the
 * repeated-action half of that, sized for a phone: it watches the last few dispatches and refuses the
 * third identical call, because a loop that has produced the same result twice will produce it again.
 *
 * Deliberately narrow:
 *  - it compares the tool AND its target, so reading ten different files is never a loop;
 *  - it clears its history when it fires, so one stop cannot cascade into permanent blocking;
 *  - it resets when the owner asks something new, because a new ask is a new task;
 *  - the refusal is advice, not an error: the model is told what to do instead of only that it failed.
 */
object StuckDetector {

    /** Roo Code's default budget, adopted because three is where a loop is provable. */
    const val LIMIT = 3

    /** Same error twice is a pattern; three is already late. */
    const val ERROR_LIMIT = 2

    private val recent = ArrayDeque<String>()

    @Synchronized
    fun reset() {
        recent.clear()
    }

    /**
     * Records one dispatch. Returns null to proceed, or the text that replaces the result when the
     * same call has just been made [LIMIT] times in a row.
     */
    @Synchronized
    fun check(tool: String, target: String): String? {
        if (tool.isBlank()) return null
        val signature = tool.trim().lowercase() + "|" + target.trim().lowercase()
        recent.addLast(signature)
        while (recent.size > 8) recent.removeFirst()
        if (recent.size < LIMIT) return null
        val tail = recent.toList().takeLast(LIMIT)
        if (tail.any { it != signature }) return null
        recent.clear()
        val what = if (target.isBlank()) tool else "$tool on \"$target\""
        return "STOP - you have just run $what $LIMIT times in a row and nothing changed.\n" +
            "Running it again will produce the same result. Do one of these instead:\n" +
            "- change the ARGUMENT, not the call: the failure is in what you passed;\n" +
            "- run the one thing that would explain the failure (read the file, show the error);\n" +
            "- if this target does not serve the owner's current ask, re-read the ask above;\n" +
            "- if it genuinely cannot be done, say what blocked it in one line and stop.\n"
    }

    private val errors = ArrayDeque<String>()

    /**
     * OWNER REQUEST (2026-11-25): the error half of the detector. A repeated ACTION is one shape of
     * being stuck; the same FAILURE from different calls is the other, and it is the one that burns
     * tokens, because the model keeps rephrasing while the cause never changes.
     *
     * Returns advice the caller should hand back instead of the failure, or null when the failure is
     * new. The history clears on a hit so one stop cannot cascade.
     */
    @Synchronized
    fun noteError(tool: String, result: String): String? {
        if (!looksLikeFailure(result)) {
            errors.clear()
            return null
        }
        val sig = tool.trim().lowercase() + "|" + normaliseError(result)
        errors.addLast(sig)
        while (errors.size > 8) errors.removeFirst()
        val same = errors.toList().takeLast(ERROR_LIMIT).count { it == sig }
        if (errors.size >= ERROR_LIMIT && same == ERROR_LIMIT) {
            errors.clear()
            return "STOP - " + tool + " has now failed " + ERROR_LIMIT +
                " times with the same error. The wording changed; the cause did not.
" +
                "Do not retry it. Read the error text above and change the CAUSE:
" +
                "- a missing path or file: find where it actually lives, then act on that;
" +
                "- a permission or approval refusal: tell the owner what is needed and stop;
" +
                "- bad arguments: fix the argument, not the call;
" +
                "- if it cannot be done, say what blocked it in one line and stop.\n"
        }
        return null
    }

    /**
     * OWNER REQUEST (2026-11-25): the progress metric. Attempts are not progress: three failures that
     * each say something different are the agent learning. The signal is the OUTCOME not changing.
     */
    @Synchronized
    fun noProgress(tool: String, target: String, outcome: String): Boolean {
        val sig = tool.trim().lowercase() + "|" + target.trim().lowercase() + "|" + outcome.trim().lowercase()
        progress.addLast(sig)
        while (progress.size > 6) progress.removeFirst()
        return progress.size >= LIMIT && progress.toList().takeLast(LIMIT).all { it == sig }
    }

    private val progress = ArrayDeque<String>()

    private fun looksLikeFailure(result: String): Boolean {
        val r = result.trim()
        if (r.isEmpty()) return false
        val head = r.take(240).lowercase()
        return head.startsWith("error") || head.startsWith("failed") || head.contains(": error") ||
            head.contains("exception") || head.contains("not found") || head.contains("denied") ||
            head.contains("cannot ") || head.contains("could not ")
    }

    /** Digits and paths differ between retries of the same fault; the fault itself does not. */
    private fun normaliseError(result: String): String =
        result.lowercase().replace(Regex("[0-9]+"), "#")
            .replace(Regex("/[^ \n:]+"), "<path>").take(300)

}

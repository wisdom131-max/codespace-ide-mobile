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
}

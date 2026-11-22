package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.24 (catalog 32) - a long task that dies at step 9 should resume at step 9.
 *
 * The app kept a handoff for a NEW SESSION, but nothing recorded where a task had got to WITHIN one.
 * So a killed process, a cancelled send or a context overflow lost the position and the model
 * restarted from the top, which is a large part of why long jobs felt unreliable.
 *
 * This is the record, not the storage: plain data with a stable text form, so the caller decides
 * where it lives. Round-trip is the whole contract, and a checkpoint that cannot be read back is
 * reported as unreadable rather than silently reset - resuming from the wrong position is worse
 * than resuming from none.
 */
object TaskCheckpoint {

    const val FORMAT_VERSION: Int = 1

    data class Done(val id: String, val title: String)

    data class State(
        val goal: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val done: List<Done> = emptyList(),
        val nextAction: String = "",
        val updatedAt: Long = 0L,
        val version: Int = FORMAT_VERSION,
    ) {
        val isComplete: Boolean get() = totalSteps > 0 && stepIndex >= totalSteps
        val remaining: Int get() = (totalSteps - stepIndex).coerceAtLeast(0)
    }

    sealed interface Restore {
        data class Ok(val state: State) : Restore
        data class Unreadable(val reason: String) : Restore
    }

    private const val SEP = "\u001F"
    private const val SEP_CHAR: Char = '\u001F'

    /** Line 1 is the header; each further line is one completed step. Escaped, so text cannot break it. */
    fun write(state: State): String = buildString {
        append(state.version).append(SEP)
        append(state.stepIndex).append(SEP)
        append(state.totalSteps).append(SEP)
        append(state.updatedAt).append(SEP)
        append(escape(state.goal)).append(SEP)
        append(escape(state.nextAction)).append('\n')
        state.done.forEach { append(escape(it.id)).append(SEP).append(escape(it.title)).append('\n') }
    }

    fun read(text: String): Restore {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Restore.Unreadable("empty checkpoint")
        val head = lines[0].split(SEP)
        if (head.size < 6) return Restore.Unreadable("header has ${head.size} fields, expected 6")
        val version = head[0].toIntOrNull() ?: return Restore.Unreadable("version is not a number")
        if (version > FORMAT_VERSION) {
            return Restore.Unreadable("written by a newer format (v$version, this build reads v$FORMAT_VERSION)")
        }
        val stepIndex = head[1].toIntOrNull() ?: return Restore.Unreadable("stepIndex is not a number")
        val totalSteps = head[2].toIntOrNull() ?: return Restore.Unreadable("totalSteps is not a number")
        if (stepIndex < 0 || totalSteps < 0) return Restore.Unreadable("negative step counter")
        val updatedAt = head[3].toLongOrNull() ?: return Restore.Unreadable("updatedAt is not a number")
        val done = ArrayList<Done>()
        for (line in lines.drop(1)) {
            val f = line.split(SEP)
            if (f.size < 2) continue
            done.add(Done(unescape(f[0]), unescape(f[1])))
        }
        return Restore.Ok(
            State(
                goal = unescape(head[4]),
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                done = done,
                nextAction = unescape(head[5]),
                updatedAt = updatedAt,
                version = version,
            )
        )
    }

    /**
     * Single pass, because chained replaces cannot round-trip: escaping the backslash first makes
     * the other escapes indistinguishable from text, and escaping it last doubles the backslashes
     * the other escapes just introduced. One pass has no ordering to get wrong.
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                SEP_CHAR -> sb.append("\\u")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'u' -> { sb.append(SEP_CHAR); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }
}

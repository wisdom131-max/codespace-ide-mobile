package com.codespace.ide.agent.runtime

/**
 * STAGE 4.3 (2026-11-16): what the orchestrator is doing, in the words the transcript uses.
 *
 * The Thinking Tree already groups semantic steps; this is the line-by-line detail for the same
 * block, so "waiting for a find before replacing" is visible while it happens instead of being
 * inferred from a silence. Bounded on purpose: sixteen lines is enough to explain a wait, and a
 * transcript buffer is not a log file.
 */
internal object OrchestrationTrace {

    private const val CAP = 16

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        lines.addLast(text.take(160))
        while (lines.size > CAP) lines.removeFirst()
    }

    @Synchronized
    fun lines(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}

package com.codespace.ide.scm

/**
 * 2026-11-05: what the chat's answer becomes in the Source Control message box.
 *
 * The prompt asks for a conventional subject under 72 characters, a blank line, then up to three
 * bullets. Models also like to wrap that in a fence and add a sentence before and after, and a
 * message box filled with "Sure! Here's a commit message:" is worse than an empty one.
 *
 * So: a fenced block wins if one exists; otherwise the first non-empty block; and lines that are
 * obviously commentary around the message are dropped. Deterministic and unit tested - no model call
 * decides this.
 */
object CommitMessageReply {

    private const val MAX_LINES = 8

    fun extract(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        fenced(text)?.let { inner -> if (inner.isNotBlank()) return trimLines(inner) }
        return trimLines(firstBlock(text))
    }

    /** Content of the FIRST fenced block, fence removed. Null when there is none. */
    private fun fenced(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("```") }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { it.trimStart().startsWith("```") }
        if (end < 0) return null
        return lines.subList(start + 1, start + 1 + end).joinToString("\n")
    }

    /** Everything up to the first blank line, keeping following bullet lines. */
    private fun firstBlock(text: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.isNotBlank() }
        if (start < 0) return ""
        val out = mutableListOf<String>()
        var sawBlank = false
        for (line in lines.drop(start)) {
            if (line.isBlank()) {
                sawBlank = true
                continue
            }
            val isBullet = line.trimStart().startsWith("-") || line.trimStart().startsWith("*")
            if (sawBlank && !isBullet) break
            // A commentary line BEFORE the message is skipped (it is the model introducing its
            // answer); the same shape AFTER the message ends it. Same test, different position.
            if (commentary(line)) {
                if (out.isEmpty()) continue
                break
            }
            out.add(line)
            sawBlank = false
        }
        return out.joinToString("\n")
    }

    /**
     * Sentences models put AROUND a message. Kept to unambiguous openers: a real subject line
     * rarely starts with these, and a wrong cut merely leaves the message short, never corrupted.
     */
    private fun commentary(line: String): Boolean {
        val t = line.trim()
        val openers = listOf(
            "sure", "here's", "here is", "certainly", "based on", "i read", "i looked",
            "this commit", "note:", "the following",
        )
        return openers.any { t.startsWith(it, ignoreCase = true) } &&
            !t.startsWith("updated", ignoreCase = true) &&
            !t.startsWith("noted", ignoreCase = true)
    }

    private fun trimLines(text: String): String =
        text.lines().map { it }.take(MAX_LINES).joinToString("\n").trim().trimEnd()
}

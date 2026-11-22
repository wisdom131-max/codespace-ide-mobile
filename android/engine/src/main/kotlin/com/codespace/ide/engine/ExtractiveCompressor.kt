package com.codespace.ide.engine

/**
 * PROJECT NINE 9.7 (catalog 25) - shrink long text without a second model call.
 *
 * A compression MODEL is a whole extra request per turn. For the case that actually hurts - a long
 * tool output or a long pasted document - extractive compression does most of the work for free:
 * keep the sentences that share words with the question, in their original order, and say how many
 * were dropped so nobody mistakes the result for the whole text.
 *
 * It never invents a sentence. That is the reason to prefer this over a model here: a summary that
 * quietly drops the one line that mattered is worse than a shorter text that admits it is shorter.
 */
object ExtractiveCompressor {

    data class Result(val text: String, val kept: Int, val total: Int)

    fun compress(text: String, query: String, maxSentences: Int = 5): Result {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.size <= maxSentences) return Result(text, sentences.size, sentences.size)
        val wanted = terms(query)
        val keep = sentences.indices
            .sortedWith(
                compareByDescending<Int> { terms(sentences[it]).count { wanted.contains(it) } }
                    .thenBy { it },
            )
            .take(maxSentences)
            .sorted()
        val body = keep.joinToString(" ") { sentences[it] }
        val omitted = sentences.size - keep.size
        return Result(body + "\n[" + omitted + " sentences omitted]", keep.size, sentences.size)
    }

    private fun terms(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.toSet()
}

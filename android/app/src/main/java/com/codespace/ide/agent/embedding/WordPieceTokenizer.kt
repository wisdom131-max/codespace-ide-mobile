package com.codespace.ide.agent.embedding

import java.io.File

/**
 * PROJECT NINE 10.23 - the tokenizer the trained model expects.
 *
 * A model does not read words; it reads numbers from a fixed vocabulary. Get this wrong and the
 * model still answers, confidently, about the wrong thing - which is worse than failing. So the
 * rules follow BERT's exactly: lower-case, split punctuation, and fall back to the longest piece
 * of a word that the vocabulary knows, marking the remainder with "##".
 */
class WordPieceTokenizer(vocabFile: File) {

    private val ids: Map<String, Int> = vocabFile.readLines()
        .mapIndexedNotNull { index, line -> line.trim().takeIf { it.isNotEmpty() }?.let { it to index } }
        .toMap()

    private val unk: Int = ids["[UNK]"] ?: 100
    private val cls: Int = ids["[CLS]"] ?: 101
    private val sep: Int = ids["[SEP]"] ?: 102
    private val maxWordChars = 100

    fun encode(text: String, maxLen: Int = 256): LongArray {
        val out = ArrayList<Long>(maxLen)
        out.add(cls.toLong())
        for (token in basicTokens(text)) {
            for (id in wordPieces(token)) {
                if (out.size >= maxLen - 1) break
                out.add(id.toLong())
            }
            if (out.size >= maxLen - 1) break
        }
        out.add(sep.toLong())
        return out.toLongArray()
    }

    /** Lower-case, then split on anything that is not a letter or digit, keeping punctuation alone. */
    private fun basicTokens(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch.isLetterOrDigit() -> current.append(ch)
                ch.isWhitespace() -> { if (current.isNotEmpty()) { out.add(current.toString()); current.setLength(0) } }
                else -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.setLength(0) }
                    out.add(ch.toString())
                }
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** Greedy longest-match, left to right, with "##" on continuations. */
    private fun wordPieces(token: String): List<Int> {
        if (token.length > maxWordChars) return listOf(unk)
        ids[token]?.let { return listOf(it) }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found = -1
            while (start < end) {
                val piece = if (start == 0) token.substring(start, end) else "##" + token.substring(start, end)
                val id = ids[piece]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) return listOf(unk)
            pieces.add(found)
            start = end
        }
        return if (pieces.isEmpty()) listOf(unk) else pieces
    }
}

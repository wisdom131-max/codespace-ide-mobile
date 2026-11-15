package com.codespace.ide.agent.voice

/**
 * PROJECT 2.16 (2026-11-15): turning a Copilot reply into speech, sentence by sentence.
 *
 * Three problems, one file.
 *
 * First, a reply is Markdown and Markdown is not speakable: fences, backticks, asterisks, table
 * pipes and link syntax all become noise in the ear. Code is dropped rather than read out, with a
 * short spoken stand-in so the listener knows something was there.
 *
 * Second, waiting for a 2,000-character reply before making a sound is the difference between a
 * conversation and a monologue. [segments] splits at sentence boundaries so the first sentence can
 * be spoken while the rest is still arriving.
 *
 * Third, a sentence still has to be short enough to interrupt. A 400-character "sentence" with no
 * punctuation gets broken at a comma or a space, so cancellation lands quickly.
 *
 * Pure: no Android, no audio, no provider.
 */
object VoiceScript {

    /** Spoken stand-in for a block that should not be read aloud. */
    const val CODE_PLACEHOLDER = "Code block."

    /**
     * Strip what should never be spoken. Order matters: fences are removed before inline markers,
     * otherwise the triple backticks are half-consumed and the leftovers get read out.
     */
    fun strip(markdown: String, announceCode: Boolean = true): String {
        var text = markdown.replace("\r\n", "\n")

        // Fenced blocks: replaced, not read.
        text = Regex("```[\\s\\S]*?```").replace(text) { if (announceCode) " $CODE_PLACEHOLDER " else " " }
        // Tables and horizontal rules carry no spoken value.
        text = Regex("(?m)^\\s*\\|.*$").replace(text, " ")
        text = Regex("(?m)^\\s*([-*_]\\s*){3,}$").replace(text, " ")
        // Images vanish, links keep their words.
        text = Regex("!\\[([^\\]]*)]\\([^)]*\\)").replace(text, "$1")
        text = Regex("\\[([^\\]]*)]\\([^)]*\\)").replace(text, "$1")
        // Inline code: keep the token, drop the ticks.
        text = Regex("`([^`]*)`").replace(text, "$1")
        // Emphasis and heading marks.
        text = Regex("(\\*\\*|__)(.*?)\\1").replace(text, "$2")
        text = Regex("(\\*|_)(.*?)\\1").replace(text, "$2")
        text = Regex("~~(.*?)~~").replace(text, "$1")
        text = Regex("(?m)^\\s{0,3}#{1,6}\\s*").replace(text, "")
        text = Regex("(?m)^\\s*>\\s?").replace(text, "")
        text = Regex("(?m)^\\s*[-*+]\\s+").replace(text, "")
        text = Regex("(?m)^\\s*\\d+[.)]\\s+").replace(text, "")
        // HTML and stray tool markup.
        text = Regex("<[^>]{1,120}>").replace(text, " ")
        // Collapse.
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    /**
     * Sentence-boundary split for progressive speech.
     *
     * [minChars] merges a stub like "Yes." into the next sentence so the engine is not started for
     * two words; [maxChars] is the ceiling for a single utterance, past which the split falls back to
     * the last comma, then a space, then a hard cut.
     */
    fun segments(
        markdown: String,
        minChars: Int = 24,
        maxChars: Int = 240,
        announceCode: Boolean = true,
    ): List<String> {
        val spoken = strip(markdown, announceCode)
        if (spoken.isBlank()) return emptyList()

        val raw = ArrayList<String>()
        val buffer = StringBuilder()
        for (char in spoken) {
            buffer.append(char)
            if (char == '.' || char == '!' || char == '?' || char == ';' || char == ':') {
                // Decimal points and mid-token dots are not sentence ends.
                val nextIsDigit = spoken.getOrNull(spoken.indexOf(char) + 1)?.isDigit() == true
                if (!(char == '.' && nextIsDigit)) {
                    raw.add(buffer.toString())
                    buffer.setLength(0)
                }
            } else if (char == '\n') {
                if (buffer.isBlank()) continue
                raw.add(buffer.toString())
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) raw.add(buffer.toString())

        val merged = ArrayList<String>()
        val pending = StringBuilder()
        raw.forEach { piece ->
            val clean = piece.trim()
            if (clean.isEmpty()) return@forEach
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(clean)
            val current = pending.toString()
            if (current.length >= minChars) {
                merged.add(current)
                pending.setLength(0)
            }
        }
        if (pending.isNotBlank()) {
            if (merged.isEmpty()) merged.add(pending.toString()) else merged[merged.size - 1] =
                merged[merged.size - 1] + " " + pending.toString()
        }

        return merged.flatMap { splitLong(it, maxChars) }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Break anything longer than [maxChars], preferring a comma, then a space. */
    fun splitLong(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > maxChars) {
            val window = rest.take(maxChars)
            var cut = window.lastIndexOf(", ")
            if (cut < maxChars / 3) cut = window.lastIndexOf(' ')
            if (cut < maxChars / 4) cut = maxChars
            out.add(rest.take(cut + 1).trim())
            rest = rest.drop(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** Is there anything at all worth saying in this text? */
    fun speakable(markdown: String): Boolean = strip(markdown).isNotBlank()
}

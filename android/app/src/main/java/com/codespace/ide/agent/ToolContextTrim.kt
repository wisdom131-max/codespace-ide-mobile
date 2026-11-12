package com.codespace.ide.agent

/**
 * PROJECT 4.6 / 4.7 - WHAT EARLIER TOOL OUTPUT IS STILL WORTH CARRYING.
 *
 * A long task accumulates results, and putting all of them back into every turn is how a phone-sized
 * context fills with a file that was already read. This keeps the results that still matter - the
 * latest of each tool, anything pinned as evidence, and anything with a source id - drops exact
 * repeats, and compresses the rest with an explicit truncation marker so nothing pretends to be
 * whole.
 */
object ToolContextTrim {

    data class Item(
        val tool: String,
        val output: String,
        /** Evidence the answer must cite, or a result later steps depend on. */
        val pinned: Boolean = false,
    )

    data class Trimmed(val kept: List<Item>, val droppedRepeats: Int, val truncated: Int) {
        fun render(): String = kept.joinToString("\n") { it.tool + ": " + it.output }
    }

    private val EVIDENCE = Regex("\\[S\\d+]")

    fun trim(items: List<Item>, maxChars: Int = 6_000, perItem: Int = 1_500): Trimmed {
        if (maxChars <= 0) return Trimmed(emptyList(), items.size, 0)
        val seen = HashSet<String>()
        var repeats = 0
        var truncated = 0
        val chosen = ArrayList<Item>()
        var used = 0
        // Newest first: recency is what the next step usually needs.
        for (item in items.asReversed()) {
            val key = item.tool + "\u0000" + item.output.take(400)
            if (!seen.add(key)) { repeats++; continue }
            if (!item.pinned && !EVIDENCE.containsMatchIn(item.output) && chosen.any { it.tool == item.tool }) {
                // A superseded result from the same tool: keep only the latest unless it is evidence.
                continue
            }
            val text = if (item.output.length > perItem) {
                truncated++
                item.output.take(perItem) + " \u2026 [" + (item.output.length - perItem) + " chars omitted]"
            } else {
                item.output
            }
            if (used + text.length > maxChars && !item.pinned) continue
            chosen.add(item.copy(output = text))
            used += text.length
            if (used >= maxChars) break
        }
        return Trimmed(chosen.asReversed(), repeats, truncated)
    }

    /** One line saying what happened to the context, or empty when nothing was lost. */
    fun note(t: Trimmed): String = when {
        t.droppedRepeats == 0 && t.truncated == 0 -> ""
        t.truncated > 0 -> t.truncated.toString() + " earlier result(s) were shortened to fit; ask for a " +
            "narrower range if the omitted part matters."
        else -> t.droppedRepeats.toString() + " repeated result(s) were not repeated back."
    }
}

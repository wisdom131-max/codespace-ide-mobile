package com.codespace.ide.engine

/**
 * PROJECT NINE 9.36 - search results cannot blow the prompt on their own.
 *
 * Results were appended until the tool output was truncated somewhere downstream, which cut a
 * result in half and lost the rest silently. Deciding here means the caller keeps whole results and
 * knows how many it got, instead of receiving a fragment.
 */
object SearchBudget {

    data class Item(val path: String, val text: String)

    fun fit(items: List<Item>, maxChars: Int): List<Item> {
        if (maxChars <= 0) return emptyList()
        val out = ArrayList<Item>()
        var used = 0
        for (i in items) {
            if (used + i.text.length > maxChars) break
            out.add(i)
            used += i.text.length
        }
        return out
    }
}

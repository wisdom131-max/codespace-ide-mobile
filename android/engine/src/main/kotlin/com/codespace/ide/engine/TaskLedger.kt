package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.19 (catalog 31) - the model forgets the plan between rounds.
 *
 * Each tool round re-sends the conversation, but nothing states what the TASK still owes. On a
 * long chain the model re-derives the goal from the last few messages and drifts, which reads as
 * "it does not know what to do" even though every individual step was sensible.
 *
 * The ledger is a short, bounded list of what this request still owes. It is not memory (that
 * survives sessions) and not a plan tree (that is ToolPlanner): it is the three-to-six line answer
 * to "where am I" that can be re-stated every round cheaply.
 *
 * Bounded on purpose: a ledger that grows with the task becomes another thing to summarise.
 */
class TaskLedger(private val maxItems: Int = 8) {

    data class Item(val id: String, val text: String, val done: Boolean = false)

    private val items = LinkedHashMap<String, Item>()
    private var dropped = 0

    fun isEmpty(): Boolean = items.isEmpty()
    fun size(): Int = items.size
    /** Items that were displaced by [maxItems]; surfaced so a cut list is never silently short. */
    fun droppedCount(): Int = dropped

    /** Replaces the list. Ids are positional and stable, so completing an item survives a re-set. */
    fun set(texts: List<String>) {
        val kept = ArrayList<Item>(maxItems)
        texts.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, text ->
            if (kept.size >= maxItems) { dropped++; return@forEachIndexed }
            val id = "t" + (i + 1)
            val was = items[id]
            kept.add(Item(id, text, was?.done == true && was.text == text))
        }
        items.clear()
        kept.forEach { items[it.id] = it }
    }

    /** Marks done by id. Unknown ids are ignored: a stale id must not resurrect an item. */
    fun complete(id: String): Boolean {
        val current = items[id] ?: return false
        if (current.done) return false
        items[id] = current.copy(done = true)
        return true
    }

    fun outstanding(): List<Item> = items.values.filter { !it.done }

    /**
     * The block as it enters the prompt, or "" when nothing is outstanding (an empty ledger must
     * cost nothing, or every short request pays for a feature it does not use).
     */
    fun render(): String {
        val open = outstanding()
        if (open.isEmpty()) return ""
        val sb = StringBuilder("## THIS REQUEST STILL OWES\n")
        for (item in items.values) {
            sb.append(if (item.done) "- [done] " else "- [ ] ").append(item.text).append('\n')
        }
        if (dropped > 0) sb.append("- (").append(dropped).append(" earlier step(s) not listed)\n")
        return sb.toString().trimEnd()
    }
}

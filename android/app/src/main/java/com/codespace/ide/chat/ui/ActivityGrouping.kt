package com.codespace.ide.chat.ui

/**
 * 2026-11-05 (owner: "install the activity grouping"): consecutive tool steps become ONE row.
 *
 * Today every tool the assistant runs appends its own ToolActivity row, so a turn that reads four
 * files and edits one leaves five near-identical lines in the transcript and pushes the actual
 * answer down the screen. VS Code shows its tool activity as a single collapsing block for the same
 * reason.
 *
 * Rules, kept pure so they are asserted instead of discovered:
 *  - only CONSECUTIVE ToolActivity rows group; anything else ends the group (a note or an answer is
 *    not part of the same burst of work);
 *  - a lone step stays a plain row - a group of one is noise, not structure;
 *  - grouping is a display decision only: the rows are never mutated, so dismissing or replaying
 *    state cannot lose a step.
 *
 * The owner's UI rule for the group: chevron only, never an x.
 */
sealed interface ChatDisplayRow {
    data class Single(val row: ChatRowModel) : ChatDisplayRow
    data class ActivityGroup(val items: List<ChatRowModel.ToolActivity>) : ChatDisplayRow
}

object ActivityGrouping {

    /** How many tool names the collapsed header spells out before counting the rest. */
    const val LABEL_LIMIT = 3

    fun group(rows: List<ChatRowModel>): List<ChatDisplayRow> {
        val out = ArrayList<ChatDisplayRow>(rows.size)
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row is ChatRowModel.ToolActivity) {
                var j = i
                while (j < rows.size && rows[j] is ChatRowModel.ToolActivity) j++
                val run = rows.subList(i, j).map { it as ChatRowModel.ToolActivity }
                if (run.size == 1) {
                    out.add(ChatDisplayRow.Single(row))
                } else {
                    out.add(ChatDisplayRow.ActivityGroup(run))
                }
                i = j
            } else {
                out.add(ChatDisplayRow.Single(row))
                i++
            }
        }
        return out
    }

    /**
     * The collapsed header: how many steps, then the tool names the labels actually carry.
     *
     * Labels are the engine's own step labels ("read_file: 42 lines"), so the tool name is the part
     * before the colon; falling back to the whole label keeps a label that has no colon readable
     * instead of dropping it.
     */
    fun summary(items: List<ChatRowModel.ToolActivity>): String {
        if (items.isEmpty()) return "No steps"
        if (items.size == 1) return items[0].label
        val names = items.map { toolName(it.label) }.distinct()
        val shown = names.take(LABEL_LIMIT).joinToString(", ")
        val more = if (names.size > LABEL_LIMIT) " +" + (names.size - LABEL_LIMIT) + " more" else ""
        val stepWord = if (items.size == 1) "step" else "steps"
        return items.size.toString() + " " + stepWord + " · " + shown + more
    }

    private fun toolName(label: String): String {
        val head = label.substringBefore(':').trim()
        return if (head.isEmpty()) label.trim() else head
    }
}

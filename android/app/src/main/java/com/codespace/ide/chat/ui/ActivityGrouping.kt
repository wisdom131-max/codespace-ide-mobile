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
    /** One task's work: steps, the notes they produced, and the narration between them. */
    data class ActivityGroup(val id: String, val items: List<ChatRowModel>) : ChatDisplayRow
}

/**
 * 2026-11-11 (owner: "related work renders OUTSIDE the tree"): grouping was ADJACENCY-based, so
 * anything that was not a ToolActivity ended the run. Three things therefore escaped the tree every
 * turn, and all three were observed on the device: the model's mid-task narration (an
 * AssistantStream, indistinguishable from the answer), status notes (InfoNote), and the results
 * that follow a note. The fix is to make the row say what it is - PROGRESS vs ANSWER - and to end a
 * run only on content that genuinely ends the task.
 *
 * Rules, pure so they are asserted instead of discovered:
 *  - a run STARTS at a tool step, so a reply that used no tools never grows a tree;
 *  - a run CONTINUES over steps, PROGRESS narration and notes (the work and what it said);
 *  - a run ENDS at the ANSWER, a failure, a user message, attachments, thinking, budget/offline
 *    cards. Those are turn-level, not task-level;
 *  - a lone step with no attached note or narration stays a plain row: a group of one is noise;
 *  - [id] is the first step's stable id, so a streaming update cannot reset an expanded group.
 */
object ActivityGrouping {

    /** How many tool names the collapsed header spells out before counting the rest. */
    const val LABEL_LIMIT = 3

    private fun endsRun(row: ChatRowModel): Boolean = when (row) {
        is ChatRowModel.ToolActivity -> false
        is ChatRowModel.InfoNote -> false
        is ChatRowModel.AssistantStream -> row.kind != ChatRowModel.StreamKind.PROGRESS
        else -> true
    }

    private fun continuesRun(row: ChatRowModel): Boolean = when (row) {
        is ChatRowModel.ToolActivity -> true
        is ChatRowModel.InfoNote -> true
        is ChatRowModel.AssistantStream -> row.kind == ChatRowModel.StreamKind.PROGRESS
        else -> false
    }

    fun group(rows: List<ChatRowModel>): List<ChatDisplayRow> {
        val out = ArrayList<ChatDisplayRow>(rows.size)
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            // OWNER REPORT (2026-11-23): leave the app mid-turn, come back, and the tool calls and
            // done-work notes were sitting RAW in the chat with the tree nowhere to be seen. Cause:
            // only a ToolActivity could START a group, and the restored transcript is mostly
            // InfoNote - so the notes had no housing and each one rendered as its own chat row. A
            // note starts a run now, which is what makes the tree survive a restart.
            if (row !is ChatRowModel.ToolActivity && row !is ChatRowModel.InfoNote) {
                out.add(ChatDisplayRow.Single(row))
                i++
                continue
            }
            // The narration that introduced this task belongs to it: "Reading the manifest" is
            // part of the work, not a turn-level reply. Walk back over it, but never over a row
            // already consumed or one that ends a run.
            var start = i
            while (start > 0) {
                val prev = rows[start - 1]
                val isLeadingProgress = prev is ChatRowModel.AssistantStream &&
                    prev.kind == ChatRowModel.StreamKind.PROGRESS
                if (!isLeadingProgress) break
                start--
            }
            var j = i
            while (j < rows.size && continuesRun(rows[j])) j++
            // Nothing may be swallowed after a run-ending row, even if the loop above was generous.
            while (j > i + 1 && endsRun(rows[j - 1])) j--
            val run = rows.subList(start, j).toList()
            val steps = run.count { it is ChatRowModel.ToolActivity }
            // A LONE note stays visible: hiding one line inside a tree is worse than showing it.
            if (steps == 1 && run.size == 1 || (steps == 0 && run.size == 1)) {
                out.add(ChatDisplayRow.Single(row))
            } else {
                // A run of notes carries no ToolActivity, so its id comes from the first row. The
                // old code called first() on a filtered list and would have thrown on such a run.
                val first = run.first()
                val id = if (first is ChatRowModel.ToolActivity && first.id.isNotBlank()) first.id else "run@" + i
                out.add(ChatDisplayRow.ActivityGroup(id, run))
            }
            i = j
        }
        return out
    }

    /** "5 steps: read_file, grep_search, ..." - the header a collapsed tree shows. */
    fun summary(items: List<ChatRowModel>): String {
        val all = items.filterIsInstance<ChatRowModel.ToolActivity>()
            .map { it.label.substringBefore(':').trim() }
        val noteTexts = items.filterIsInstance<ChatRowModel.InfoNote>().map { it.text }
        // A restored run has notes but no tool rows. It used to say "No steps" - a header that tells
        // the user nothing about the work it is hiding. It now names the activity instead.
        if (all.isEmpty()) {
            if (noteTexts.isEmpty()) return "No steps"
            val title = com.codespace.ide.engine.ThinkingTitles.runningTitle(noteTexts)
            return title + " \u00b7 " + noteTexts.size + (if (noteTexts.size == 1) " step" else " steps")
        }
        val count = all.size
        val names = all.distinct()          // "read_file, read_file" is noise, not information
        val head = names.take(LABEL_LIMIT).joinToString(", ")
        // "+N more" counts the STEPS not shown, so the header always adds up to the total.
        val more = if (names.size > LABEL_LIMIT) " +" + (names.size - LABEL_LIMIT) + " more" else ""
        val notes = items.count { it is ChatRowModel.InfoNote }
        val suffix = if (notes > 0) " \u00b7 " + notes + " note" + (if (notes == 1) "" else "s") else ""
        return if (count == 1) "1 step: " + head + more + suffix
        else count.toString() + " steps: " + head + more + suffix
    }
}

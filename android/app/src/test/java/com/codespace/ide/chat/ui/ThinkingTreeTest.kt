package com.codespace.ide.chat.ui

import com.codespace.ide.chat.ui.ChatRowModel.AssistantStream
import com.codespace.ide.chat.ui.ChatRowModel.Failed
import com.codespace.ide.chat.ui.ChatRowModel.InfoNote
import com.codespace.ide.chat.ui.ChatRowModel.StreamKind
import com.codespace.ide.chat.ui.ChatRowModel.ToolActivity
import com.codespace.ide.chat.ui.ChatRowModel.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11: the Thinking Tree as a SYSTEM, not a layout.
 *
 * Every test here asserts a RELATIONSHIP: which row belongs to which task, and what may never escape
 * it. The old failure was structural - work, notes and narration leaked out of the tree because
 * grouping was adjacency-based and the rows carried no identity.
 */
class ThinkingTreeTest {

    private fun step(name: String, id: String, detail: String = "ok") =
        ToolActivity(name, detail, id)

    private fun groups(rows: List<ChatRowModel>) =
        ActivityGrouping.group(rows).filterIsInstance<ChatDisplayRow.ActivityGroup>()

    @Test
    fun aSimpleTaskKeepsItsResultInsideTheTree() {
        // One step plus the note describing its result is ONE task; a lone step stays plain.
        val rows = listOf(AssistantStream("Looking", true, StreamKind.PROGRESS), step("read_file", "r1c1"),
            InfoNote("read_file: 42 lines"), AssistantStream("Here it is.", false, StreamKind.ANSWER))
        val g = groups(rows)
        assertEquals(1, g.size)
        assertEquals(3, g[0].items.size) // narration + step + note, answer NOT included
        assertTrue(g[0].items.none { it is AssistantStream && it.kind == StreamKind.ANSWER })
    }

    @Test
    fun severalToolCallsAndOutputsStayInOneTask() {
        val rows = listOf(
            step("read_file", "r1c1"), InfoNote("read_file: 42 lines"),
            step("grep_search", "r1c2"), InfoNote("grep_search: 7 hits"),
            AssistantStream("done", false, StreamKind.ANSWER),
        )
        val g = groups(rows)
        assertEquals(1, g.size)
        assertEquals(4, g[0].items.size)
    }

    @Test
    fun nestedSubtasksShareTheParentTaskAndKeepOrder() {
        // Two rounds with their own narration: parent task keeps both subtask runs in ONE tree.
        val rows = listOf(
            AssistantStream("Round one", false, StreamKind.PROGRESS), step("list_files", "r1c1"),
            AssistantStream("Round two", false, StreamKind.PROGRESS), step("read_file", "r2c1"),
            InfoNote("read_file: 10 lines"), AssistantStream("answer", false, StreamKind.ANSWER),
        )
        val g = groups(rows)
        assertEquals(1, g.size)
        assertEquals(listOf("list_files", "read_file"),
            g[0].items.filterIsInstance<ToolActivity>().map { it.label })
    }

    @Test
    fun aFailedCallAndItsRetryLiveInTheSameTree() {
        val rows = listOf(
            step("run_command", "r1c1", "exit 1"), InfoNote("run_command failed: exit 1"),
            step("run_command", "r2c1", "exit 0"), InfoNote("run_command: ok"),
            AssistantStream("fixed", false, StreamKind.ANSWER),
        )
        val g = groups(rows)
        assertEquals(1, g.size)
        assertEquals(2, g[0].items.filterIsInstance<ToolActivity>().size)
        assertEquals(2, g[0].items.count { it is InfoNote })
    }

    @Test
    fun streamingStatusUpdatesDoNotGrowDuplicateNodes() {
        // Mirrors ChatSession's delta rule: a stream UPDATE replaces the last stream row, it does
        // not append a second one. Five updates must therefore produce one node.
        var rows: List<ChatRowModel> = listOf(step("read_file", "r1c1"))
        repeat(5) { n ->
            val text = "progress " + n
            val next = AssistantStream(text, true, StreamKind.PROGRESS)
            rows = if (rows.lastOrNull() is AssistantStream) rows.dropLast(1) + next else rows + next
        }
        val display = ActivityGrouping.group(rows)
        assertEquals(1, display.size)
        assertEquals(1, groups(rows)[0].items.count { it is AssistantStream })
    }

    @Test
    fun interleavedToolEventsNeverOverwriteEachOther() {
        val rows = listOf(
            step("read_file", "r1c1", "a"), step("grep_search", "r1c2", "b"),
            step("get_errors", "r1c3", "c"), AssistantStream("done", false, StreamKind.ANSWER),
        )
        val ids = groups(rows)[0].items.filterIsInstance<ToolActivity>().map { it.id }
        assertEquals(listOf("r1c1", "r1c2", "r1c3"), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun completionAfterManySubtaskRunsIsOneAnswerOutsideTheTree() {
        val rows = (1..3).flatMap { listOf(step("tool$it", "r${it}c1"), InfoNote("tool$it ok")) } +
            AssistantStream("final", false, StreamKind.ANSWER)
        val display = ActivityGrouping.group(rows)
        val answers = display.filterIsInstance<ChatDisplayRow.Single>().map { it.row }
            .filterIsInstance<AssistantStream>()
        assertEquals(1, answers.size)
        assertEquals(StreamKind.ANSWER, answers[0].kind)
        assertEquals(1, groups(rows).size)
    }

    @Test
    fun anInterruptedTaskLeavesItsWorkInsideTheTreeWithNoAnswer() {
        // Cancelled turn: steps and notes exist, no ANSWER ever arrives.
        val rows = listOf(step("run_command", "r1c1"), InfoNote("run_command: partial output"))
        val display = ActivityGrouping.group(rows)
        assertEquals(1, display.size)
        assertTrue(groups(rows).isNotEmpty())
        assertTrue(display.none { it is ChatDisplayRow.Single && it.row is AssistantStream })
    }

    @Test
    fun aRestoredConversationRegroupsIdentically() {
        val rows = listOf(
            step("read_file", "r1c1"), InfoNote("read_file: 42 lines"),
            step("write_file", "r1c2"), InfoNote("write_file: ok"),
            AssistantStream("done", false, StreamKind.ANSWER),
        )
        val first = ActivityGrouping.group(rows)
        val restored = ActivityGrouping.group(rows.toList())
        assertEquals(first.map { it.toString() }, restored.map { it.toString() })
        assertEquals("r1c1", (first[0] as ChatDisplayRow.ActivityGroup).id)
    }

    @Test
    fun noTaskContentEscapesItsTree() {
        val rows = listOf(
            User("do it"),
            AssistantStream("planning", false, StreamKind.PROGRESS),
            step("read_file", "r1c1"), InfoNote("read_file: 42 lines"),
            step("apply_patch", "r1c2", "3 hunks"), InfoNote("apply_patch: ok"),
            AssistantStream("All three files are updated.", false, StreamKind.ANSWER),
        )
        val display = ActivityGrouping.group(rows)
        // Every step and every note must be inside the single group.
        val inGroup = groups(rows)[0].items
        assertEquals(2, inGroup.filterIsInstance<ToolActivity>().size)
        assertEquals(2, inGroup.count { it is InfoNote })
        val singles = display.filterIsInstance<ChatDisplayRow.Single>().map { it.row }
        assertTrue(singles.none { it is ToolActivity })
        assertTrue(singles.none { it is InfoNote })
    }

    @Test
    fun aFailureCardStaysOutsideTheTreeAndEndsIt() {
        val rows = listOf(step("read_file", "r1c1"), Failed("Rate limited", "6000 tpm", true))
        val display = ActivityGrouping.group(rows)
        assertEquals(2, display.size)
        assertTrue(display.last() is ChatDisplayRow.Single)
    }

    @Test
    fun aLoneStepWithNoNoteStaysAPlainRow() {
        val display = ActivityGrouping.group(listOf(step("read_file", "r1c1")))
        assertEquals(1, display.size)
        assertTrue(display[0] is ChatDisplayRow.Single)
    }
}

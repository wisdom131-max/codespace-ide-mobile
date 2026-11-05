package com.codespace.ide.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-05: consecutive tool steps fold into one row; nothing else folds. */
class ActivityGroupingTest {

    private fun step(label: String) = ChatRowModel.ToolActivity(label, "detail of $label")

    @Test
    fun consecutiveStepsBecomeOneGroup() {
        val rows = listOf(step("read_file: 42 lines"), step("search_files: 3 files"), step("edit_file: ok"))
        val out = ActivityGrouping.group(rows)
        assertEquals(1, out.size)
        val group = out[0] as ChatDisplayRow.ActivityGroup
        assertEquals(3, group.items.size)
    }

    @Test
    fun aLoneStepStaysAPlainRow() {
        val out = ActivityGrouping.group(listOf(step("read_file: 10 lines")))
        assertEquals(1, out.size)
        assertTrue("a group of one is noise", out[0] is ChatDisplayRow.Single)
    }

    @Test
    fun anAnswerEndsTheGroup() {
        val rows = listOf(
            step("a: 1"), step("b: 2"),
            ChatRowModel.AssistantStream("here is the answer", false),
            step("c: 3"), step("d: 4"),
        )
        val out = ActivityGrouping.group(rows)
        assertEquals("group, answer, group", 3, out.size)
        assertTrue(out[0] is ChatDisplayRow.ActivityGroup)
        assertTrue(out[1] is ChatDisplayRow.Single)
        assertTrue(out[2] is ChatDisplayRow.ActivityGroup)
    }

    @Test
    fun aNoteIsNeverAbsorbedIntoAGroup() {
        val rows = listOf(step("a: 1"), ChatRowModel.InfoNote("key switched"), step("b: 2"))
        val out = ActivityGrouping.group(rows)
        assertEquals(3, out.size)
    }

    @Test
    fun theSummaryCountsStepsAndNamesTheTools() {
        val rows = listOf(step("read_file: 42 lines"), step("read_file: 10 lines"), step("edit_file: ok"))
        val summary = ActivityGrouping.summary(rows.map { it })
        assertTrue(summary.startsWith("3 steps"))
        assertTrue("duplicate tool names are folded", summary.contains("read_file, edit_file"))
        assertTrue("the duplicate is not repeated", !summary.contains("read_file, read_file"))
    }

    @Test
    fun manyToolsAreCappedInTheHeader() {
        val rows = listOf(
            step("read_file: 1"), step("search_files: 1"), step("edit_file: 1"),
            step("run_command: 1"), step("write_file: 1"),
        )
        val summary = ActivityGrouping.summary(rows.map { it })
        assertTrue(summary.contains("5 steps"))
        assertTrue(summary.contains("+2 more"))
    }

    @Test
    fun anEmptyListDoesNotCrashTheHeader() {
        assertEquals("No steps", ActivityGrouping.summary(emptyList()))
    }
}

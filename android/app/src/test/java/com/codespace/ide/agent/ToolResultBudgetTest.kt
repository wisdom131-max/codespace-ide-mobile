package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4.6/4.7 wired into the turn: the carried tool output is bounded and never silently shortened. */
class ToolResultBudgetTest {

    private fun big(tool: String, chars: Int, marker: String = "body") =
        ToolContextTrim.Item(tool, marker + "x".repeat(chars))

    @Test fun theTurnsToolBudgetIsFiniteAndSmall() {
        assertTrue(ChatSession.TOOL_RESULT_BUDGET_CHARS in 1..40_000)
        assertEquals(6_000, ChatSession.TOOL_RESULT_BUDGET_CHARS)
    }

    @Test fun aLongChainIsCompressedToTheBudget() {
        val items = listOf(
            big("read_file", 4_000), big("grep_search", 4_000), big("read_file", 4_000),
            big("git_diff", 4_000), big("run_command", 4_000),
        )
        val rendered = ToolContextTrim.trim(items, ChatSession.TOOL_RESULT_BUDGET_CHARS).render()
        assertTrue("rendered was ${rendered.length}", rendered.length <= ChatSession.TOOL_RESULT_BUDGET_CHARS * 2)
        assertTrue(rendered.contains("chars omitted") || rendered.length <= ChatSession.TOOL_RESULT_BUDGET_CHARS)
    }

    @Test fun theNewestResultSurvivesAndTheShortenedOneSaysSo() {
        val items = listOf(big("read_file", 8_000, "OLD"), big("run_command", 8_000, "NEW"))
        val trimmed = ToolContextTrim.trim(items, ChatSession.TOOL_RESULT_BUDGET_CHARS)
        assertTrue(trimmed.render().contains("NEW"))
        assertTrue(trimmed.truncated >= 1)
    }

    @Test fun aCitedResultIsKeptBecauseItIsEvidence() {
        val items = listOf(
            ToolContextTrim.Item("web_search", "[S1] the source says this", pinned = true),
            ToolContextTrim.Item("web_search", "an older uncited hit"),
            ToolContextTrim.Item("read_file", "file body"),
        )
        val rendered = ToolContextTrim.trim(items, 300).render()
        assertTrue(rendered.contains("[S1]"))
    }

    @Test fun anExactRepeatIsDroppedRatherThanSentTwice() {
        val same = big("read_file", 100)
        val trimmed = ToolContextTrim.trim(listOf(same, same, same), 6_000)
        assertEquals("two of the three are exact repeats", 2, trimmed.droppedRepeats)
        assertEquals(1, trimmed.kept.size)
    }

    @Test fun aShortTurnIsNotTrimmedAtAll() {
        val items = listOf(big("read_file", 40), big("grep_search", 40))
        val trimmed = ToolContextTrim.trim(items, ChatSession.TOOL_RESULT_BUDGET_CHARS)
        assertFalse(trimmed.kept.isEmpty())
        assertEquals(0, trimmed.truncated)
    }
}

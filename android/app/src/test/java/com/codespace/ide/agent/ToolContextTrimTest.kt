package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 4.6 / 4.7: keep what matters, compress the rest, never pretend it is whole. */
class ToolContextTrimTest {

    @Test fun anExactRepeatIsDropped() {
        val t = ToolContextTrim.trim(
            listOf(
                ToolContextTrim.Item("grep_search", "hit at Main.kt:4"),
                ToolContextTrim.Item("grep_search", "hit at Main.kt:4"),
            ),
        )
        assertEquals(1, t.kept.size)
        assertEquals(1, t.droppedRepeats)
        assertTrue(ToolContextTrim.note(t).contains("repeated"))
    }

    @Test fun theLatestResultOfAToolSupersedesTheEarliest() {
        val t = ToolContextTrim.trim(
            listOf(
                ToolContextTrim.Item("read_file", "old body"),
                ToolContextTrim.Item("read_file", "new body"),
            ),
        )
        assertEquals("new body", t.kept.single().output)
    }

    @Test fun pinnedEvidenceSurvivesEvenIfShort() {
        val t = ToolContextTrim.trim(
            listOf(
                ToolContextTrim.Item("web_search", "claim with [S1] support"),
                ToolContextTrim.Item("read_file", "body"),
                ToolContextTrim.Item("read_file", "body two"),
            ),
        )
        assertTrue(t.kept.any { it.output.contains("[S1]") })
    }

    @Test fun aLongResultIsShortenedWithAnHonestMarker() {
        val long = "x".repeat(5_000)
        val t = ToolContextTrim.trim(listOf(ToolContextTrim.Item("read_file", long)), perItem = 1_000)
        assertEquals(1, t.truncated)
        assertTrue(t.kept.single().output.contains("chars omitted"))
        assertTrue(ToolContextTrim.note(t).contains("shortened"))
    }

    @Test fun theTotalBudgetIsRespected() {
        val items = (1..10).map { ToolContextTrim.Item("tool$it", "y".repeat(1_000)) }
        val t = ToolContextTrim.trim(items, maxChars = 2_500, perItem = 1_000)
        assertTrue(t.kept.sumOf { it.output.length } <= 2_500)
    }

    @Test fun nothingLostMeansNoNote() {
        val t = ToolContextTrim.trim(listOf(ToolContextTrim.Item("read_file", "ok")))
        assertEquals("", ToolContextTrim.note(t))
    }

    @Test fun anEmptyBudgetKeepsNothingRatherThanEverything() {
        val t = ToolContextTrim.trim(listOf(ToolContextTrim.Item("read_file", "ok")), maxChars = 0)
        assertTrue(t.kept.isEmpty())
    }
}

package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the VS Code parity tools. No device, no filesystem. */
class RepoSearchTest {

    @Test
    fun symbolsFindDeclarationsByPartialName() {
        val files = listOf(
            "src/a.kt" to """
                package x
                class ChatPanel
                fun renderRow() {}
                val counter = 1
            """.trimIndent(),
            "py/b.py" to "def render_row():\n    pass",
        )
        val hits = RepoSearch.symbols(files, "render")
        assertEquals(2, hits.size)
        assertTrue(hits[0].startsWith("src/a.kt:3"))
        assertTrue(hits[1].startsWith("py/b.py:1"))
    }

    @Test
    fun symbolsIgnoreUntypedMentionsAndBlankQueries() {
        val files = listOf("a.kt" to "// renderRow is mentioned here only\nval other = 1")
        assertTrue(RepoSearch.symbols(files, "render").isEmpty())
        assertTrue(RepoSearch.symbols(files, "  ").isEmpty())
    }

    @Test
    fun structureIsIndentedCappedAndSaysWhenItStopped() {
        val text = RepoSearch.structure(
            listOf("app/", "app/src/", "app/src/Main.kt", "readme.md"),
            maxDepth = 3,
        )
        assertTrue(text.contains("app/"))
        assertTrue(text.contains("  src/"))
        assertTrue(text.contains("    Main.kt"))
        assertTrue(text.contains("readme.md"))

        val deep = RepoSearch.structure(listOf("a/", "a/b/", "a/b/c/", "a/b/c/d.kt"), maxDepth = 2)
        assertTrue("beyond the depth limit must not appear", !deep.contains("d.kt"))
    }

    @Test
    fun structureReportsAnEmptyTreeHonestly() {
        assertEquals("No readable entries under this path.", RepoSearch.structure(emptyList()))
    }

    @Test
    fun replaceManyAppliesEveryEditWhenAllMatch() {
        val result = RepoSearch.replaceMany(
            "val a = 1\nval b = 2\n",
            listOf("val a = 1" to "val a = 10", "val b = 2" to "val b = 20"),
        )
        assertTrue(result is ReplaceOutcome.Ok)
        val ok = result as ReplaceOutcome.Ok
        assertEquals(2, ok.count)
        assertTrue(ok.updated.contains("val a = 10"))
        assertTrue(ok.updated.contains("val b = 20"))
    }

    @Test
    fun replaceManyChangesNothingWhenOneEditFails() {
        val original = "alpha\nbeta\n"
        val result = RepoSearch.replaceMany(
            original,
            listOf("alpha" to "ALPHA", "gamma" to "GAMMA"),
        )
        assertTrue("a partial edit must be refused, not half applied", result is ReplaceOutcome.Refused)
        val refused = result as ReplaceOutcome.Refused
        assertTrue(refused.reason.contains("Nothing was changed"))
    }

    @Test
    fun replaceManyRefusesAnEmptyList() {
        assertTrue(RepoSearch.replaceMany("text", emptyList()) is ReplaceOutcome.Refused)
    }
}

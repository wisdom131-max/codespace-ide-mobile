package com.codespace.ide.agent

import com.codespace.ide.diagnostics.DiagnosticManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS Code-parity tools, tested where the safety actually lives: the matching rules of a
 * targeted edit and the honesty rules of a diagnostics report.
 */
class VsCodeParityToolsTest {

    // ---------------------------------------------------------------- replace_string_in_file

    @Test
    fun `a unique match is replaced exactly once`() {
        val out = ReplaceText.apply("a = 1\nb = 2\n", "b = 2", "b = 3", replaceAll = false)
        assertTrue(out is ReplaceOutcome.Ok)
        assertEquals("a = 1\nb = 3\n", (out as ReplaceOutcome.Ok).updated)
        assertEquals(1, out.count)
    }

    @Test
    fun `an ambiguous match is refused rather than guessed`() {
        val out = ReplaceText.apply("x\nx\n", "x", "y", replaceAll = false)
        assertTrue(out is ReplaceOutcome.Refused)
        assertTrue((out as ReplaceOutcome.Refused).reason.contains("2 times"))
    }

    @Test
    fun `replace_all changes every occurrence and reports the count`() {
        val out = ReplaceText.apply("x\nx\n", "x", "y", replaceAll = true)
        assertEquals("y\ny\n", (out as ReplaceOutcome.Ok).updated)
        assertEquals(2, out.count)
    }

    @Test
    fun `a stale copy of the file is refused instead of silently succeeding`() {
        val out = ReplaceText.apply("new content", "old content", "other", replaceAll = false)
        assertTrue(out is ReplaceOutcome.Refused)
        assertTrue((out as ReplaceOutcome.Refused).reason.contains("No match"))
    }

    @Test
    fun `an empty or no-op replacement is refused`() {
        assertTrue(ReplaceText.apply("abc", "", "x", false) is ReplaceOutcome.Refused)
        assertTrue(ReplaceText.apply("abc", "abc", "abc", false) is ReplaceOutcome.Refused)
    }

    @Test
    fun `occurrence counting does not overlap itself`() {
        assertEquals(1, ReplaceText.countOf("aaa", "aa"))
        assertEquals(0, ReplaceText.countOf("abc", "z"))
    }

    // ---------------------------------------------------------------- get_errors

    private fun diag(
        path: String,
        line: Int,
        severity: DiagnosticManager.Severity,
        message: String = "boom",
        stale: Boolean = false,
    ) = DiagnosticManager.Diagnostic(
        id = path + line + message,
        source = DiagnosticManager.DiagnosticSource.LSP,
        sourceId = "kotlin",
        uri = "file://" + path,
        filePath = path,
        range = DiagnosticManager.DiagnosticRange(line, 1, line, 4),
        severity = severity,
        message = message,
        isStale = stale,
    )

    @Test
    fun `an empty snapshot says it is not proof the code is clean`() {
        val text = DiagnosticReport.format(emptyList())
        assertTrue(text.contains("not proof"))
    }

    @Test
    fun `errors and warnings are counted and sorted by file then line`() {
        val text = DiagnosticReport.format(
            listOf(
                diag("/b.kt", 9, DiagnosticManager.Severity.WARNING),
                diag("/a.kt", 12, DiagnosticManager.Severity.ERROR, "unresolved"),
                diag("/a.kt", 3, DiagnosticManager.Severity.ERROR, "syntax"),
            ),
        )
        assertTrue(text.startsWith("2 errors, 1 warning"))
        val lines = text.lines()
        assertTrue(lines[1].startsWith("/a.kt:3"))
        assertTrue(lines[2].startsWith("/a.kt:12"))
        assertTrue(lines[3].startsWith("/b.kt:9"))
    }

    @Test
    fun `a filter that matches nothing says the project still has problems`() {
        val text = DiagnosticReport.format(
            listOf(diag("/a.kt", 1, DiagnosticManager.Severity.ERROR)),
            pathFilter = "not-here",
        )
        assertTrue(text.contains("No diagnostics match"))
        assertTrue(text.contains("1 diagnostic(s) exist elsewhere"))
    }

    @Test
    fun `stale diagnostics are marked instead of passed off as current`() {
        val text = DiagnosticReport.format(
            listOf(diag("/a.kt", 1, DiagnosticManager.Severity.ERROR, stale = true)),
        )
        assertTrue(text.contains("1 marked stale"))
        assertTrue(text.contains("(stale)"))
    }

    @Test
    fun `a long list is capped and says how many were hidden`() {
        val many = (1..70).map { diag("/f.kt", it, DiagnosticManager.Severity.WARNING) }
        val text = DiagnosticReport.format(many, max = 60)
        assertTrue(text.contains("showing the first 60 of 70"))
        assertEquals(60, text.lines().count { it.startsWith("/f.kt") })
    }
}

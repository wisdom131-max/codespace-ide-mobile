package com.codespace.ide.ui.diag

import com.codespace.ide.agent.CopilotDiag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagHistoryModelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    @Test fun `empty state has no rows`() {
        assertTrue(DiagHistoryModel.rows().isEmpty())
        assertEquals("No diagnostic events yet", DiagHistoryModel.summary())
    }

    @Test fun `rows are newest first`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "first", true)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "second", true)
        assertEquals(listOf("second", "first"), DiagHistoryModel.rows().map { it.name })
    }

    @Test fun `filtering by kind narrows the list`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.record(CopilotDiag.Kind.RETRIEVAL, "b", true)
        val r = DiagHistoryModel.rows(kind = CopilotDiag.Kind.RETRIEVAL)
        assertEquals(1, r.size)
        assertEquals("b", r.single().name)
    }

    @Test fun `failures-only excludes successes`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "good", true)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "bad", false)
        val r = DiagHistoryModel.rows(onlyFailures = true)
        assertEquals(listOf("bad"), r.map { it.name })
    }

    @Test fun `the list is bounded even with a long history`() {
        repeat(CopilotDiag.MAX_EVENTS) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        assertTrue(DiagHistoryModel.rows().size <= DiagHistoryModel.MAX_ROWS)
    }

    @Test fun `every kind has a human label`() {
        CopilotDiag.Kind.entries.forEach {
            assertTrue(it.name, DiagHistoryModel.label(it).isNotBlank())
        }
    }

    @Test fun `time is formatted as clock time`() {
        val t = DiagHistoryModel.time(System.currentTimeMillis())
        assertTrue(t, Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(t))
    }

    @Test fun `summary counts failures`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "a", true)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "b", false)
        assertTrue(DiagHistoryModel.summary().contains("2 events"))
        assertTrue(DiagHistoryModel.summary().contains("1 failed"))
    }

    /** The integration path the campaign asks for: events recorded, persisted, restored, shown. */
    @Test fun `persisted events are visible in the history after a restart`() {
        val f = File(tmp.newFolder("d"), "events.jsonl")
        CopilotDiag.attach(f)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "write_file", true, "5ms")
        assertTrue(CopilotDiag.flush())
        // process death
        CopilotDiag.clear()
        assertTrue(DiagHistoryModel.rows().isEmpty())
        // restart
        CopilotDiag.attach(f)
        val rows = DiagHistoryModel.rows()
        assertEquals(1, rows.size)
        assertEquals("write_file", rows.single().name)
    }

    @Test fun `clearing empties the list and the stored file`() {
        val f = File(tmp.newFolder("d2"), "events.jsonl")
        CopilotDiag.attach(f)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.flush()
        DiagHistoryModel.clear()
        assertTrue(DiagHistoryModel.rows().isEmpty())
        assertEquals("No diagnostic events yet", DiagHistoryModel.summary())
    }

    @Test fun `a redacted detail stays redacted through the model`() {
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE, "gemini", false, "api_key=abcdef123456")
        assertFalse(DiagHistoryModel.rows().single().detail.contains("abcdef123456"))
    }

    @Test fun `available kinds only lists kinds that exist`() {
        CopilotDiag.record(CopilotDiag.Kind.OFFLOAD, "run_command", true, "20000 chars")
        assertEquals(listOf(CopilotDiag.Kind.OFFLOAD), DiagHistoryModel.availableKinds())
    }

    @Test fun `outcome reflects the recorded flag`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "x", false)
        assertEquals("failed", DiagHistoryModel.outcome(DiagHistoryModel.rows().single()))
    }
}

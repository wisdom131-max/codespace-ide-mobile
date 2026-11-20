package com.codespace.ide.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the same trace() the execution gate uses, end to end into the ring. */
class CopilotDiagRuntimeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() { CopilotDiag.clear(); CopilotDiag.startSession("t-1"); CopilotDiag.clear() }

    @Test fun `a successful call emits start and end`() = runBlocking {
        val out = CopilotDiag.trace("read_file") { "file contents" }
        assertEquals("file contents", out)
        val e = CopilotDiag.recent()
        assertEquals(2, e.size)
        assertEquals(CopilotDiag.Kind.TOOL_START, e[0].kind)
        assertEquals(CopilotDiag.Kind.TOOL_END, e[1].kind)
        assertTrue(e[1].ok)
    }

    @Test fun `a FAILED result is recorded as not ok`() = runBlocking {
        CopilotDiag.trace("run_command") { "FAILED: exit 1" }
        assertFalse(CopilotDiag.recent().last().ok)
    }

    @Test fun `an exception is recorded then rethrown unchanged`() {
        var thrown = false
        try {
            runBlocking { CopilotDiag.trace("grep_search") { throw IllegalStateException("boom") } }
        } catch (t: IllegalStateException) {
            thrown = t.message == "boom"
        }
        assertTrue("the exception must still reach the caller", thrown)
        assertFalse(CopilotDiag.recent().last().ok)
    }

    @Test fun `the result string is never altered by tracing`() = runBlocking {
        val payload = "x".repeat(5_000)
        assertEquals(payload, CopilotDiag.trace("big_tool") { payload })
    }

    @Test fun `duration and size metadata are recorded`() = runBlocking {
        CopilotDiag.trace("read_file") { "y".repeat(120) }
        val d = CopilotDiag.recent().last().detail
        assertTrue(d.contains("ms"))
        assertTrue(d.contains("120 chars"))
    }

    @Test fun `one transition is one event, not two`() = runBlocking {
        CopilotDiag.trace("read_file") { "ok" }
        assertEquals(1, CopilotDiag.recent().count { it.kind == CopilotDiag.Kind.TOOL_END })
    }

    @Test fun `correlation id is stable across the pair`() = runBlocking {
        CopilotDiag.trace("read_file") { "ok" }
        assertTrue(CopilotDiag.recent().all { it.session == "t-1" })
    }

    @Test fun `an offload records a real event`() {
        val root = tmp.newFolder("ws")
        ContextOffload.apply(root, "p1", "s1", "run_command", "z".repeat(20_000))
        val off = CopilotDiag.recent().filter { it.kind == CopilotDiag.Kind.OFFLOAD }
        assertEquals(1, off.size)
        assertTrue(off.single().ok)
        assertTrue(off.single().detail.contains("20000 chars"))
    }

    @Test fun `an offload write failure is recorded as not ok`() {
        val root = tmp.newFolder("ws2")
        java.io.File(root, "offload").writeText("blocked")
        ContextOffload.apply(root, "p1", "s1", "run_command", "z".repeat(20_000))
        assertFalse(CopilotDiag.recent().single { it.kind == CopilotDiag.Kind.OFFLOAD }.ok)
    }

    @Test fun `a small result records no offload event`() {
        val root = tmp.newFolder("ws3")
        ContextOffload.apply(root, "p1", "s1", "run_command", "small")
        assertTrue(CopilotDiag.recent().none { it.kind == CopilotDiag.Kind.OFFLOAD })
    }

    @Test fun `a credential in a tool name is redacted before storage`() = runBlocking {
        CopilotDiag.trace("api_key=abcdef123456") { "ok" }
        assertFalse(CopilotDiag.recent().any { it.name.contains("abcdef123456") })
    }
}

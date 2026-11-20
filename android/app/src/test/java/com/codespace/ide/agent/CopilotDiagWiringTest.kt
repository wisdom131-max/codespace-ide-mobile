package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Proves the app-level wiring path: attach, restore, periodic flush, restart survival. */
class CopilotDiagWiringTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var sink: File

    @Before fun setUp() {
        sink = File(tmp.newFolder("diag"), "copilot-events.jsonl")
        CopilotDiag.detach()
        CopilotDiag.clear()
    }

    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    @Test fun `attach restores the previous process events`() {
        CopilotDiag.attach(sink)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "read_file", true, "1ms")
        assertTrue(CopilotDiag.flush())
        CopilotDiag.clear()
        // A new process attaches the same file.
        val restored = CopilotDiag.attach(sink)
        assertEquals(1, restored)
        assertEquals("read_file", CopilotDiag.recent().last().name)
    }

    @Test fun `attach makes the sink visible`() {
        CopilotDiag.attach(sink)
        assertNotNull(CopilotDiag.sinkFile())
        assertEquals(sink, CopilotDiag.sinkFile())
    }

    @Test fun `events reach disk without an explicit flush`() {
        CopilotDiag.attach(sink)
        repeat(5) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        assertTrue(sink.isFile && sink.length() > 0)
        assertEquals(5, sink.readLines().count { it.isNotBlank() })
    }

    @Test fun `fewer than five events stay in memory until flush`() {
        CopilotDiag.attach(sink)
        repeat(4) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        assertTrue(!sink.exists() || sink.length() == 0L)
        CopilotDiag.flush()
        assertTrue(sink.length() > 0)
    }

    @Test fun `flush without a sink is safe and reports false`() {
        assertNull(CopilotDiag.sinkFile())
        assertEquals(false, CopilotDiag.flush())
    }

    @Test fun `an unattached ring still records normally`() {
        CopilotDiag.record(CopilotDiag.Kind.RETRIEVAL, "semantic_search", true, "2 results")
        assertEquals(1, CopilotDiag.recent().size)
    }

    @Test fun `a restart keeps the ordering of the previous run`() {
        CopilotDiag.attach(sink)
        (1..6).forEach { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "s" + it, true) }
        CopilotDiag.flush()
        CopilotDiag.clear()
        CopilotDiag.attach(sink)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), CopilotDiag.recent().map { it.name })
    }

    @Test fun `an unwritable sink never breaks recording`() {
        val blocked = File(tmp.newFolder("blocked"), "events.jsonl")
        blocked.mkdirs()
        CopilotDiag.attach(blocked)
        repeat(6) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        assertEquals(6, CopilotDiag.recent().size)
    }
}

package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CopilotDiagTest {

    @Before fun reset() { CopilotDiag.clear() }

    @Test fun `a session start records one event`() {
        CopilotDiag.startSession("s-1")
        assertEquals(1, CopilotDiag.recent().count { it.kind == CopilotDiag.Kind.SESSION })
    }

    @Test fun `tool start and end are both recorded`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "read_file", true)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "read_file", true, "412 chars")
        val e = CopilotDiag.recent()
        assertEquals(2, e.size)
        assertTrue(e.all { it.name == "read_file" })
    }

    @Test fun `a failure is recorded as not ok`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_END, "run_command", false, "exit 1")
        assertFalse(CopilotDiag.recent().single().ok)
    }

    @Test fun `approval, offload, retrieval and provider error are distinct kinds`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL, "write_file", true, "approved")
        CopilotDiag.record(CopilotDiag.Kind.OFFLOAD, "run_command", true, "5MB")
        CopilotDiag.record(CopilotDiag.Kind.RETRIEVAL, "grep_search", true, "7 results")
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_ERROR, "gemini", false, "429")
        assertEquals(4, CopilotDiag.counts().size)
    }

    @Test fun `the ring is bounded and keeps the newest`() {
        repeat(CopilotDiag.MAX_EVENTS + 40) { CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "t" + it, true) }
        assertEquals(CopilotDiag.MAX_EVENTS, CopilotDiag.recent(CopilotDiag.MAX_EVENTS).size)
        assertTrue(CopilotDiag.recent(1).single().name == "t" + (CopilotDiag.MAX_EVENTS + 39))
    }

    @Test fun `bearer tokens are redacted`() {
        val r = CopilotDiag.redact("Authorization: Bearer abcdef123456ghijkl")
        assertFalse(r.contains("abcdef123456"))
    }

    @Test fun `provider keys are redacted`() {
        assertFalse(CopilotDiag.redact("key sk-abcdefghijklmnop").contains("sk-abcdefghijklmnop"))
        assertFalse(CopilotDiag.redact("ghp_1234567890abcdef").contains("1234567890abcdef"))
    }

    @Test fun `key value credentials are redacted`() {
        assertFalse(CopilotDiag.redact("api_key=supersecretvalue").contains("supersecretvalue"))
        assertFalse(CopilotDiag.redact("password: hunter2").contains("hunter2"))
    }

    @Test fun `a recorded detail is already redacted`() {
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_ERROR, "openai", false, "token=abcd1234efgh")
        assertFalse(CopilotDiag.recent().single().detail.contains("abcd1234efgh"))
    }

    @Test fun `names are length bounded`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "x".repeat(500), true)
        assertTrue(CopilotDiag.recent().single().name.length <= 80)
    }

    @Test fun `events carry the session correlation id`() {
        CopilotDiag.startSession("s-9")
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "read_file", true)
        assertTrue(CopilotDiag.recent().all { it.session == "s-9" })
    }

    @Test fun `a new session clears the previous one`() {
        CopilotDiag.startSession("s-1")
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.startSession("s-2")
        assertEquals(1, CopilotDiag.recent().size)
        assertEquals("s-2", CopilotDiag.recent().single().session)
    }

    @Test fun `clear empties the ring`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.clear()
        assertTrue(CopilotDiag.recent().isEmpty())
    }

    @Test fun `counts are per kind`() {
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "a", true)
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "b", true)
        assertEquals(2, CopilotDiag.counts()[CopilotDiag.Kind.TOOL_START])
    }
}

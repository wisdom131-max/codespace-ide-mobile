package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Event-shape tests for the approval, continuation and provider kinds added in P4 completion.
 * These prove the taxonomy and redaction; the ChatSession call sites that emit them are compile
 * verified and runtime-unverified (see AGENTS.md).
 */
class CopilotDiagLifecycleTest {

    @Before fun reset() { CopilotDiag.clear(); CopilotDiag.startSession("c-1"); CopilotDiag.clear() }

    @Test fun `approval transitions are distinct kinds`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_REQUIRED, "write_file", true, "write policy")
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_GRANTED, "write_file", true, "write policy")
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_DENIED, "mcp_call", false, "write policy")
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_CANCELLED, "write_file", false, "user left sheet")
        assertEquals(4, CopilotDiag.counts().size)
    }

    @Test fun `a denial is not recorded as a grant`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_DENIED, "mcp_call", false, "hard approval")
        assertTrue(CopilotDiag.recent().none { it.kind == CopilotDiag.Kind.APPROVAL_GRANTED })
    }

    @Test fun `continuation kinds are distinct`() {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_STARTED, "turn", true, "finish LENGTH")
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_COMPLETED, "turn", true, "800 chars")
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_FAILED, "turn", false, "provider error")
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_SKIPPED, "turn", false, "not eligible: COMPLETE")
        assertEquals(4, CopilotDiag.counts().size)
    }

    @Test fun `a skipped continuation is never reported as started`() {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_SKIPPED, "turn", false, "COMPLETE")
        assertTrue(CopilotDiag.recent().none { it.kind == CopilotDiag.Kind.CONTINUATION_STARTED })
    }

    @Test fun `provider kinds exist for start, success and failure`() {
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_START, "gemini", true, "attempt 1")
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_SUCCESS, "gemini", true, "1200ms")
        CopilotDiag.record(CopilotDiag.Kind.PROVIDER_REQUEST_FAILURE, "gemini", false, "429")
        assertEquals(3, CopilotDiag.counts().size)
    }

    @Test fun `approval correlation uses the same session as the tool events`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_GRANTED, "write_file", true, "write policy")
        CopilotDiag.record(CopilotDiag.Kind.TOOL_START, "write_file", true)
        assertTrue(CopilotDiag.recent().all { it.session == "c-1" })
    }

    @Test fun `a credential in an approval detail is redacted`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_REQUIRED, "mcp_call", true, "Authorization: Bearer abcdef123456")
        assertFalse(CopilotDiag.recent().single().detail.contains("abcdef123456"))
    }

    @Test fun `a credential in a continuation detail is redacted`() {
        CopilotDiag.record(CopilotDiag.Kind.CONTINUATION_FAILED, "turn", false, "token=abcdef123456")
        assertFalse(CopilotDiag.recent().single().detail.contains("abcdef123456"))
    }

    @Test fun `one transition produces one event`() {
        CopilotDiag.record(CopilotDiag.Kind.APPROVAL_GRANTED, "write_file", true, "write policy")
        assertEquals(1, CopilotDiag.recent().count { it.kind == CopilotDiag.Kind.APPROVAL_GRANTED })
    }
}

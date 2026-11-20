package com.codespace.ide.agent

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4.3 / P8.8: the five stages stay distinct, and the MCP hard-approval rule holds under the most
 * permissive setting the app has.
 */
class ApprovalLifecycleDiagTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    private fun kinds() = CopilotDiag.recent().map { it.kind }

    // ---- MCP hard approval (the non-negotiable invariant) ----

    @Test fun `mcp tools always require approval`() {
        assertTrue(AgentFlowGate.requiresForcedApproval("mcp_filesystem_read"))
    }

    @Test fun `connector and service writes always require approval`() {
        assertTrue(AgentFlowGate.requiresForcedApproval("use_connector"))
        assertTrue(AgentFlowGate.requiresForcedApproval("service_write"))
    }

    @Test fun `AUTO_ALL plus always-allow still asks for an mcp tool`() {
        // autoApproved AND sessionAllowed both true is the most permissive state that exists.
        assertTrue(AgentFlowGate.mustAsk("mcp_github_create_issue", autoApproved = true, sessionAllowed = true))
    }

    @Test fun `AUTO_ALL does not ask for an ordinary tool`() {
        assertFalse(AgentFlowGate.mustAsk("read_file", autoApproved = true, sessionAllowed = false))
    }

    @Test fun `an mcp tool is never offered always-allow`() {
        assertFalse(AgentFlowGate.offersAlwaysAllow("mcp_slack_post"))
        assertTrue(AgentFlowGate.offersAlwaysAllow("read_file"))
    }

    // ---- user decisions ----

    @Test fun `grant records granted then authorized`() {
        AgentFlowGate.recordVerdict("write_file", AgentFlowGate.ApprovalVerdict.APPROVED)
        assertEquals(
            listOf(CopilotDiag.Kind.APPROVAL_GRANTED, CopilotDiag.Kind.EXECUTION_AUTHORIZED),
            kinds(),
        )
    }

    @Test fun `denial records denied and never authorizes`() {
        AgentFlowGate.recordVerdict("run_command", AgentFlowGate.ApprovalVerdict.REJECTED)
        assertEquals(listOf(CopilotDiag.Kind.APPROVAL_DENIED), kinds())
        assertTrue(CopilotDiag.recent().none { it.kind == CopilotDiag.Kind.EXECUTION_AUTHORIZED })
    }

    @Test fun `timeout is recorded as cancelled not granted`() {
        AgentFlowGate.recordVerdict("run_command", AgentFlowGate.ApprovalVerdict.TIMEOUT)
        assertEquals(listOf(CopilotDiag.Kind.APPROVAL_CANCELLED), kinds())
    }

    @Test fun `the auto-approved path records authorization from policy not a user`() {
        val verdict = runBlocking {
            AgentFlowGate.awaitDecision(
                toolName = "read_file",
                argsSummary = "path=README.md",
                autoApproved = true,
                sessionAllowed = false,
            )
        }
        assertEquals(AgentFlowGate.ApprovalVerdict.APPROVED, verdict)
        val events = CopilotDiag.recent()
        assertEquals(listOf(CopilotDiag.Kind.EXECUTION_AUTHORIZED), events.map { it.kind })
        assertEquals("policy", events.single().detail)
    }

    // NOTE: the forced-ask path is NOT driven here on purpose. `awaitDecision` waits up to ten
    // minutes for a card answer, so a test that expects TIMEOUT would stall the suite for ten
    // minutes. APPROVAL_REQUIRED is emitted before that wait; its runtime emission is therefore
    // compile-verified only, and the verdict mapping below is what is unit-tested.

    // ---- policy decisions (a different stage) ----

    @Test fun `a refused write records the policy decision`() {
        WriteBoundary.refusal("write_file", "/etc/passwd", "/projects/demo")
        assertEquals(listOf(CopilotDiag.Kind.WRITE_POLICY_REFUSE), kinds())
    }

    @Test fun `a staged proposal records STAGE`() {
        WriteBoundary.proposalRefusal("/etc/hosts", "/projects/demo")
        assertEquals(listOf(CopilotDiag.Kind.WRITE_POLICY_STAGE), kinds())
    }

    @Test fun `the refusal text is unchanged by instrumentation`() {
        val t = WriteBoundary.refusal("write_file", "/etc/passwd", "/projects/demo")
        assertTrue(t.startsWith("REFUSED:"))
        assertTrue(t.contains("Nothing was written"))
    }

    @Test fun `diagnostics never authorize a call`() {
        // Recording a grant cannot change the gate's answer for a forced tool.
        AgentFlowGate.recordVerdict("mcp_remote", AgentFlowGate.ApprovalVerdict.APPROVED)
        assertTrue(AgentFlowGate.mustAsk("mcp_remote", autoApproved = true, sessionAllowed = true))
    }
}

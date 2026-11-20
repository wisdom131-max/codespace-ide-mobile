package com.codespace.ide.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4.2: the approval card driven deterministically. No sleep of ten minutes anywhere: the answer
 * comes from `approve()`/`decline()`, and the expiry path uses the injectable timeout, whose
 * production default (600_000 ms) is untouched.
 *
 * These tests also hold the security line: a forced (MCP/connector/service-write) tool asks even
 * when every permissive setting says yes.
 */
class ApprovalCardFlowTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    private fun kinds() = CopilotDiag.recent().map { it.kind }

    private fun awaitInBackground(
        toolName: String,
        autoApproved: Boolean = false,
        sessionAllowed: Boolean = false,
        timeoutMs: Long = 600_000L,
    ): Pair<CoroutineScope, () -> AgentFlowGate.ApprovalVerdict?> {
        var verdict: AgentFlowGate.ApprovalVerdict? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch {
            verdict = AgentFlowGate.awaitDecision(
                toolName = toolName,
                argsSummary = "path=README.md",
                autoApproved = autoApproved,
                sessionAllowed = sessionAllowed,
                timeoutMs = timeoutMs,
            )
        }
        return scope to { verdict }
    }

    @Test fun `granting the card records required then granted then authorized`() = runBlocking {
        val (scope, verdict) = awaitInBackground("run_command")
        assertTrue("the card must be pending", AgentFlowGate.pending.value != null)
        assertTrue(AgentFlowGate.approve())
        delay(50)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertEquals(AgentFlowGate.ApprovalVerdict.APPROVED, verdict())
        assertEquals(
            listOf(
                CopilotDiag.Kind.APPROVAL_REQUIRED,
                CopilotDiag.Kind.APPROVAL_GRANTED,
                CopilotDiag.Kind.EXECUTION_AUTHORIZED,
            ),
            kinds(),
        )
    }

    @Test fun `declining records required then denied and never authorizes`() = runBlocking {
        val (scope, verdict) = awaitInBackground("run_command")
        assertTrue(AgentFlowGate.reject())
        delay(50)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertEquals(AgentFlowGate.ApprovalVerdict.REJECTED, verdict())
        assertEquals(
            listOf(CopilotDiag.Kind.APPROVAL_REQUIRED, CopilotDiag.Kind.APPROVAL_DENIED),
            kinds(),
        )
    }

    @Test fun `an unanswered card expires as a cancellation not a grant`() = runBlocking {
        val (_, verdict) = awaitInBackground("run_command", timeoutMs = 50L)
        delay(200)
        assertEquals(AgentFlowGate.ApprovalVerdict.TIMEOUT, verdict())
        assertEquals(
            listOf(CopilotDiag.Kind.APPROVAL_REQUIRED, CopilotDiag.Kind.APPROVAL_CANCELLED),
            kinds(),
        )
        assertTrue(kinds().none { it == CopilotDiag.Kind.EXECUTION_AUTHORIZED })
    }

    @Test fun `a second approve cannot double-grant`() = runBlocking {
        val (scope, _) = awaitInBackground("run_command")
        assertTrue(AgentFlowGate.approve())
        delay(50)
        assertFalse("the card is gone once answered", AgentFlowGate.approve())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertEquals(1, kinds().count { it == CopilotDiag.Kind.APPROVAL_GRANTED })
    }

    @Test fun `an mcp tool asks even when every permissive setting says yes`() = runBlocking {
        val (scope, verdict) = awaitInBackground(
            "mcp_github_create_issue", autoApproved = true, sessionAllowed = true, timeoutMs = 50L,
        )
        delay(200)
        assertTrue("the card appeared despite AUTO_ALL", kinds().contains(CopilotDiag.Kind.APPROVAL_REQUIRED))
        assertEquals(AgentFlowGate.ApprovalVerdict.TIMEOUT, verdict())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue(kinds().none { it == CopilotDiag.Kind.EXECUTION_AUTHORIZED })
    }

    @Test fun `an auto-approved ordinary tool shows no card`() = runBlocking {
        val verdict = AgentFlowGate.awaitDecision(
            toolName = "read_file", argsSummary = "path=a", autoApproved = true, sessionAllowed = false,
        )
        assertEquals(AgentFlowGate.ApprovalVerdict.APPROVED, verdict)
        assertEquals(listOf(CopilotDiag.Kind.EXECUTION_AUTHORIZED), kinds())
        assertEquals("policy", CopilotDiag.recent().single().detail)
    }
}

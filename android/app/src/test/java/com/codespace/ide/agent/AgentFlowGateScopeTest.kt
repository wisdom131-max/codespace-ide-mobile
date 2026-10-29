package com.codespace.ide.agent

import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 6 — approval resolution safety.
 *
 * What this guards, in order of how badly it would hurt:
 *  - a double tap firing the "always allow" side effect twice,
 *  - a late tap after a timeout counting as an approval,
 *  - "allow for this session" silently persisting into a future run,
 *  - the session scope leaking across tools.
 */
class AgentFlowGateScopeTest {

    @After
    fun tearDown() {
        AgentFlowGate.pending.value = null
        AgentFlowGate.clearSessionApprovals()
    }

    private fun parkRequest(toolName: String = "run_command"): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        AgentFlowGate.pending.value = AgentFlowGate.PendingApproval(
            toolName = toolName,
            argsSummary = "ls -la",
            deferred = deferred,
            onAlwaysAllow = null,
        )
        return deferred
    }

    @Test
    fun approvingOnceResolvesTheRequestAndRemembersNothing() {
        parkRequest()
        assertTrue(AgentFlowGate.approve())
        assertTrue(AgentFlowGate.pending.value!!.deferred.isCompleted)
        assertFalse(AgentFlowGate.isSessionAllowed("run_command"))
        assertEquals(0, AgentFlowGate.sessionAllowedCount())
    }

    @Test
    fun aSecondTapCannotResolveTwice() {
        parkRequest()
        var alwaysCalls = 0
        AgentFlowGate.pending.value = AgentFlowGate.pending.value!!.copy(
            onAlwaysAllow = { alwaysCalls++ },
        )
        assertTrue(AgentFlowGate.approve(AgentFlowGate.ApprovalScope.ALWAYS))
        // The request was already resolved; a duplicate tap must be inert.
        assertFalse(AgentFlowGate.approve(AgentFlowGate.ApprovalScope.ALWAYS))
        assertEquals("the persisted side effect must run at most once", 1, alwaysCalls)
    }

    @Test
    fun aTapWithNothingPendingIsAHarmlessNoOp() {
        AgentFlowGate.pending.value = null
        assertFalse(AgentFlowGate.approve())
        assertFalse(AgentFlowGate.reject())
        assertEquals(0, AgentFlowGate.sessionAllowedCount())
    }

    @Test
    fun aLateTapAfterTheRequestVanishedApprovesNothing() {
        // Stands in for the timeout path: the gate clears pending, then a stale click lands.
        val deferred = parkRequest()
        AgentFlowGate.pending.value = null
        assertFalse(AgentFlowGate.approve())
        assertFalse("a stale tap must not complete the old request", deferred.isCompleted)
    }

    @Test
    fun sessionScopeIsRememberedForThatToolOnly() {
        parkRequest("write_file")
        AgentFlowGate.approve(AgentFlowGate.ApprovalScope.SESSION)
        assertTrue(AgentFlowGate.isSessionAllowed("write_file"))
        assertFalse("a different tool must still ask", AgentFlowGate.isSessionAllowed("run_command"))
        assertEquals(1, AgentFlowGate.sessionAllowedCount())
    }

    @Test
    fun sessionScopeNeverTouchesThePersistedPermission() {
        // The persisted switch is driven only by ChatPermissionStore (via onAlwaysAllow),
        // which this scope deliberately does not call.
        parkRequest("run_command")
        var alwaysCalls = 0
        AgentFlowGate.pending.value = AgentFlowGate.pending.value!!.copy(onAlwaysAllow = { alwaysCalls++ })
        AgentFlowGate.approve(AgentFlowGate.ApprovalScope.SESSION)
        assertEquals("session allow must not persist", 0, alwaysCalls)
    }

    @Test
    fun alwaysScopeRunsThePersistedSideEffectExactlyOnce() {
        parkRequest("run_command")
        var alwaysCalls = 0
        AgentFlowGate.pending.value = AgentFlowGate.pending.value!!.copy(onAlwaysAllow = { alwaysCalls++ })
        assertTrue(AgentFlowGate.approve(AgentFlowGate.ApprovalScope.ALWAYS))
        assertEquals(1, alwaysCalls)
        // ALWAYS is a persisted decision; it is not the in-memory list.
        assertFalse(AgentFlowGate.isSessionAllowed("run_command"))
    }

    @Test
    fun rejectingResolvesTheRequestWithoutGrantingAnything() {
        val deferred = parkRequest()
        assertTrue(AgentFlowGate.reject())
        assertTrue(deferred.isCompleted)
        assertFalse(AgentFlowGate.isSessionAllowed("run_command"))
        assertEquals(0, AgentFlowGate.sessionAllowedCount())
    }

    @Test
    fun rejectingTwiceIsInert() {
        parkRequest()
        assertTrue(AgentFlowGate.reject())
        assertFalse(AgentFlowGate.reject())
    }

    @Test
    fun clearingSessionApprovalsForgetsEverythingInMemory() {
        AgentFlowGate.rememberForSession("run_command")
        AgentFlowGate.rememberForSession("write_file")
        assertEquals(2, AgentFlowGate.sessionAllowedCount())
        AgentFlowGate.clearSessionApprovals()
        assertEquals(0, AgentFlowGate.sessionAllowedCount())
        assertFalse(AgentFlowGate.isSessionAllowed("run_command"))
    }

    @Test
    fun rememberingTheSameToolTwiceDoesNotDuplicate() {
        AgentFlowGate.rememberForSession("run_command")
        AgentFlowGate.rememberForSession("run_command")
        assertEquals(1, AgentFlowGate.sessionAllowedCount())
    }
}

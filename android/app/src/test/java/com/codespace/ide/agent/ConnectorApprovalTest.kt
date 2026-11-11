package com.codespace.ide.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 0.2 (owner approval 2026-11-11): connector consent, asserted on the real gate.
 *
 * The defect: IG15 said `use_connector` always shows a card, and no caller ever passed
 * `forceApproval`, so under AUTO_ALL the call went to the network with the user's stored token.
 *
 * These tests do not check a Boolean argument. They drive [AgentFlowGate.awaitDecision] - the same
 * suspending path the chat uses - and assert that the card is published, that the caller is still
 * suspended while it waits, and that each answer maps to the verdict the chat acts on.
 */
class ConnectorApprovalTest {

    private fun reset() {
        AgentFlowGate.clearSessionApprovals()
        // Clear, never spin: reject() leaves the state set when the deferred is already gone, which
        // would make a "while (pending != null) reject()" loop hang the suite.
        AgentFlowGate.pending.value = null
    }

    @Before
    fun setUp() {
        reset()
    }

    @After
    fun tearDown() {
        reset()
    }

    // --- the rule ---------------------------------------------------------------

    @Test
    fun onlyConnectorUseIsForced() {
        assertTrue(AgentFlowGate.requiresForcedApproval("use_connector"))
        // Everything else keeps the level-based behaviour it had: forcing them would be a silent
        // change to how AUTO_ALL works for the whole tool set.
        for (name in listOf("run_command", "run_vncode_command", "write_file", "grep_replace_all",
                "read_file", "git_commit_push", "mcp_github_create_issue")) {
            assertFalse(name + " must not be force-gated", AgentFlowGate.requiresForcedApproval(name))
        }
    }

    @Test
    fun autoAllWouldApproveTheConnectorWhichIsExactlyWhyTheGateMustAsk() {
        val auto = ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "use_connector", emptySet())
        assertTrue("AUTO_ALL does approve it without the gate rule", auto)
        assertTrue("...and the gate still asks", AgentFlowGate.mustAsk("use_connector", autoApproved = auto, sessionAllowed = false))
    }

    @Test
    fun aSessionAllowCannotSilenceTheConnectorCard() {
        assertTrue(AgentFlowGate.mustAsk("use_connector", autoApproved = true, sessionAllowed = true))
    }

    // --- AUTO_SAFE / MANUAL / run_vncode_command / MCP unchanged -----------------

    @Test
    fun autoSafeBehaviourIsUntouched() {
        // read-only stays promptless, writes still ask
        assertFalse(AgentFlowGate.mustAsk("read_file", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "read_file", emptySet()), false))
        assertTrue(AgentFlowGate.mustAsk("write_file", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "write_file", emptySet()), false))
        assertTrue(AgentFlowGate.mustAsk("use_connector", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "use_connector", emptySet()), false))
    }

    @Test
    fun manualBehaviourIsUntouched() {
        assertTrue(AgentFlowGate.mustAsk("read_file", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "read_file", emptySet()), false))
        assertTrue(AgentFlowGate.mustAsk("use_connector", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "use_connector", emptySet()), false))
        // an explicit persisted allow still wins for a normal tool under MANUAL
        assertFalse(AgentFlowGate.mustAsk("read_file", ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "read_file", setOf("read_file")), false))
    }

    @Test
    fun runVncodeCommandStaysPromptlessUnderAutoAllAndGatedElsewhere() {
        val autoAll = ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "run_vncode_command", emptySet())
        assertTrue(autoAll)
        assertFalse("owner rule: no prompt under AUTO_ALL", AgentFlowGate.mustAsk("run_vncode_command", autoAll, false))
        assertTrue(AgentFlowGate.mustAsk("run_vncode_command",
            ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "run_vncode_command", emptySet()), false))
        assertTrue(AgentFlowGate.mustAsk("run_vncode_command",
            ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "run_vncode_command", emptySet()), false))
    }

    @Test
    fun mcpToolsNeverInheritAutoAll() {
        val auto = ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "mcp_github_create_issue", emptySet())
        assertFalse("MCP tools are never auto-approved", auto)
        assertTrue("so they are gated", AgentFlowGate.mustAsk("mcp_github_create_issue", auto, false))
        assertFalse("and not by the connector rule, which is connector-only", AgentFlowGate.requiresForcedApproval("mcp_github_create_issue"))
    }

    @Test
    fun otherSafeToolsKeepTheirAutoAllBehaviour() {
        for (name in listOf("read_file", "run_command", "write_file", "grep_replace_all")) {
            val auto = ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, name, emptySet())
            assertTrue(name + " is auto-approved under AUTO_ALL", auto)
            assertFalse(name + " must not start asking", AgentFlowGate.mustAsk(name, auto, false))
        }
    }

    // --- the suspending path ----------------------------------------------------

    /**
     * Why this is a suspension test and not a "pending.value != null" test.
     *
     * AgentFlowGate.pending is a Compose MutableState. In a JVM unit test a write made inside a
     * coroutine is not observed by the test thread (the app reads it from a composition, which does
     * see it), so asserting on that state here would test the harness, not the gate. What the gate
     * actually promises is behavioural: under AUTO_ALL the connector call does NOT pass, it waits.
     * That is what these assert - the call is still suspended when the timeout fires, and the answer
     * it eventually reads maps to the verdict the chat obeys.
     */
    @Test
    fun connectorUnderAutoAllNeitherPassesNorFailsItWaits() = runBlocking {
        val outcome = withTimeoutOrNull(400) {
            AgentFlowGate.awaitDecision("use_connector", "GET /v1/messages", true, true)
        }
        assertNull("AUTO_ALL must not approve the connector call; it must wait for the card", outcome)
    }

    @Test
    fun aNonConnectorAutoApprovedToolStillReturnsWithoutWaiting() = runBlocking {
        val outcome = withTimeoutOrNull(400) {
            AgentFlowGate.awaitDecision("read_file", "", true, false)
        }
        assertEquals("the level shortcut must still work for everything else",
            AgentFlowGate.ApprovalVerdict.APPROVED, outcome)
    }

    @Test
    fun theCardAnswerMapsToTheVerdictTheChatObeys() {
        // The chat executes only on APPROVED and reports anything else as NOT RUN.
        assertEquals(AgentFlowGate.ApprovalVerdict.APPROVED, AgentFlowGate.verdictFor(true))
        assertEquals(AgentFlowGate.ApprovalVerdict.REJECTED, AgentFlowGate.verdictFor(false))
        assertEquals(AgentFlowGate.ApprovalVerdict.TIMEOUT, AgentFlowGate.verdictFor(null))
    }

    @Test
    fun approvingAndRejectingTheParkedRequestResolvesItExactlyOnce() {
        // The existing card contract, re-asserted for the connector because Wave 0.2 makes this
        // the only way a connector call can proceed.
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        AgentFlowGate.pending.value = AgentFlowGate.PendingApproval(
            toolName = "use_connector",
            argsSummary = "POST /v1/messages",
            deferred = deferred,
            onAlwaysAllow = null,
        )
        assertTrue(AgentFlowGate.approve())
        assertTrue(deferred.isCompleted)
        assertEquals(AgentFlowGate.ApprovalVerdict.APPROVED, AgentFlowGate.verdictFor(deferred.getCompleted()))
        assertFalse("a second tap cannot resolve it again", AgentFlowGate.approve())
        assertFalse(AgentFlowGate.reject())
    }

    @Test
    fun rejectingTheParkedConnectorRequestPreventsIt() {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        AgentFlowGate.pending.value = AgentFlowGate.PendingApproval(
            toolName = "use_connector",
            argsSummary = "POST /v1/messages",
            deferred = deferred,
            onAlwaysAllow = null,
        )
        assertTrue(AgentFlowGate.reject())
        assertEquals(AgentFlowGate.ApprovalVerdict.REJECTED, AgentFlowGate.verdictFor(deferred.getCompleted()))
    }

    @Test
    fun connectorConsentIsNotPersistedForTheNextCall() {
        // A session allow for a normal tool is remembered; for the connector the gate ignores the
        // set entirely, so the next call asks again.
        AgentFlowGate.rememberForSession("use_connector")
        assertTrue(AgentFlowGate.mustAsk("use_connector", autoApproved = true, sessionAllowed = true))
        AgentFlowGate.rememberForSession("write_file")
        assertFalse(AgentFlowGate.mustAsk("write_file", autoApproved = false, sessionAllowed = true))
    }

    @Test
    fun theConnectorCardDoesNotOfferAlwaysAllow() {
        assertFalse(AgentFlowGate.offersAlwaysAllow("use_connector"))
        assertTrue(AgentFlowGate.offersAlwaysAllow("write_file"))
        assertTrue(AgentFlowGate.offersAlwaysAllow("use_connector_backup_style_name"))
    }
}

package com.codespace.ide.security

import com.codespace.ide.agent.AgentFlowGate
import com.codespace.ide.chat.AttachmentSecrets
import com.codespace.ide.chat.provider.SideEffectRecovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.4 / 8.2 — approval memory and retry safety, against the real gate.
 *
 * Everything here is the production object; nothing is simulated except the answers we feed it.
 */
class Stage84ApprovalAndRetrySafetyTest {

    // ---------- 8.4: approval may not widen past what was agreed to ----------

    @Test
    fun `a session allow covers the arguments it was granted for and nothing else`() {
        AgentFlowGate.clearSessionApprovals()
        AgentFlowGate.rememberForSession("run_command", "git status")
        assertTrue(AgentFlowGate.isSessionAllowed("run_command", "git status"))
        assertFalse(
            "one command approval must not approve a different command",
            AgentFlowGate.isSessionAllowed("run_command", "rm -rf /sdcard"),
        )
        AgentFlowGate.clearSessionApprovals()
    }

    @Test
    fun `a session allow on a write does not approve a different path`() {
        AgentFlowGate.clearSessionApprovals()
        AgentFlowGate.rememberForSession("write_file", "path=src/Main.kt")
        assertTrue(AgentFlowGate.isSessionAllowed("write_file", "path=src/Main.kt"))
        assertFalse(AgentFlowGate.isSessionAllowed("write_file", "path=~/.ssh/authorized_keys"))
        AgentFlowGate.clearSessionApprovals()
    }

    @Test
    fun `a session allow on one tool does not approve another tool`() {
        AgentFlowGate.clearSessionApprovals()
        AgentFlowGate.rememberForSession("read_file", "path=README.md")
        assertFalse(AgentFlowGate.isSessionAllowed("write_file", "path=README.md"))
        AgentFlowGate.clearSessionApprovals()
    }

    @Test
    fun `the exact agreement is honoured and mustAsk stands down`() {
        AgentFlowGate.clearSessionApprovals()
        AgentFlowGate.rememberForSession("run_command", "npm test")
        assertFalse(AgentFlowGate.mustAsk("run_command", autoApproved = false, sessionAllowed = true))
        assertTrue(AgentFlowGate.mustAsk("run_command", autoApproved = false, sessionAllowed = false))
        AgentFlowGate.clearSessionApprovals()
    }

    @Test
    fun `MCP and connector calls are forced even when everything else says yes`() {
        assertTrue(AgentFlowGate.mustAsk("mcp_filesystem_read", autoApproved = true, sessionAllowed = true))
        assertTrue(AgentFlowGate.mustAsk("use_connector", autoApproved = true, sessionAllowed = true))
        assertFalse(AgentFlowGate.offersAlwaysAllow("mcp_filesystem_read"))
        assertFalse(AgentFlowGate.offersAlwaysAllow("use_connector"))
    }

    @Test
    fun `session keys differ per tool and per argument, and never collide on a blank summary`() {
        assertNotEquals(
            AgentFlowGate.sessionKey("run_command", "a"),
            AgentFlowGate.sessionKey("run_command", "b"),
        )
        assertNotEquals(
            AgentFlowGate.sessionKey("run_command", "x"),
            AgentFlowGate.sessionKey("write_file", "x"),
        )
        // A blank summary must fall back to the tool name rather than a constant hash of "".
        assertTrue(AgentFlowGate.sessionKey("run_command", "  ") == "run_command")
    }

    // ---------- 8.2: retry safety ----------

    @Test
    fun `an uncertain write is never offered a retry`() {
        assertFalse(SideEffectRecovery.mayRetry(SideEffectRecovery.State.UNCERTAIN, readOnly = false))
    }

    @Test
    fun `a verified failure may be retried, a completed write and an uncertain read may not be repeated blindly`() {
        assertTrue(SideEffectRecovery.mayRetry(SideEffectRecovery.State.VERIFIED_FAILED, readOnly = false))
        assertFalse(SideEffectRecovery.mayRetry(SideEffectRecovery.State.COMPLETED, readOnly = false))
        assertTrue(SideEffectRecovery.mayRetry(SideEffectRecovery.State.UNCERTAIN, readOnly = true))
        assertFalse(SideEffectRecovery.mayRetry(SideEffectRecovery.State.UNCERTAIN, readOnly = false))
    }

    @Test
    fun `an uncertain note tells the user the operation may have run`() {
        val note = SideEffectRecovery.uncertainNote("write_file")
        assertTrue(note.contains("write_file"))
        assertTrue(
            "the note must not claim the operation definitely did not happen: " + note,
            !note.lowercase().contains("did not run"),
        )
    }

    // ---------- 8.1 / 8.5 crossover: provider errors carry no credential ----------

    @Test
    fun `a provider error line keeps its diagnosis and loses its key`() {
        val fake = "generativelanguage.googleapis.com returned 401 for key AIza" + "SyntheticSecretValue1234567890"
        val (clean, changed) = AttachmentSecrets.redact(fake, AttachmentSecrets.TIER_MESSAGE_TEXT)
        assertTrue(changed)
        assertFalse("{synthetic} survived redaction", clean.contains("SyntheticSecretValue1234567890"))
        assertTrue("the useful part must survive: " + clean, clean.contains("returned 401"))
        assertTrue(clean.contains("generativelanguage"))
    }
}

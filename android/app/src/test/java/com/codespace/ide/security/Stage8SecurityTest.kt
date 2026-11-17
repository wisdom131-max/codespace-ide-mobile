package com.codespace.ide.security

import com.codespace.ide.agent.AgentFlowGate
import com.codespace.ide.agent.net.HeaderPolicy
import com.codespace.ide.chat.AttachmentSecrets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT - the security boundaries asserted as BEHAVIOUR, not as a scan.
 *
 * Every check here drives the real production component with a plausible attack or failure input and
 * asserts on the answer that component gives. A clean dependency scan proves nothing about whether an
 * approval gate actually asks, so these tests exist to keep the gates honest as the code moves.
 */
class Stage8SecurityTest {

    // ---------------------------------------------------------------- 8.4 MCP and connector approval

    private val fakeKeys = listOf(
        "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789",
        "ghp_1234567890abcdefghijklmnopqrstuvwxyz",
        "AIzaSyD-1234567890abcdefghijklmnopqrstuv",
    )

    @Test
    fun `an MCP tool asks even when the session is set to auto approve everything`() {
        assertTrue(
            "AUTO_ALL must not turn MCP consent into a setting nobody remembers agreeing to",
            AgentFlowGate.mustAsk("mcp_github_create_issue", autoApproved = true, sessionAllowed = false),
        )
        assertTrue(
            "a stored connector token must not be spent without an explicit answer",
            AgentFlowGate.mustAsk("use_connector", autoApproved = true, sessionAllowed = false),
        )
        assertFalse(
            "an ordinary local tool stays quiet under AUTO_ALL",
            AgentFlowGate.mustAsk("read_file", autoApproved = true, sessionAllowed = false),
        )
    }

    @Test
    fun `a forced-approval tool never offers always allow`() {
        assertFalse(AgentFlowGate.offersAlwaysAllow("mcp_slack_post"))
        assertFalse(AgentFlowGate.offersAlwaysAllow("use_connector"))
    }

    // ---------------------------------------------------------------- 8.5 credential redaction

    @Test
    fun `a provider key never survives message redaction`() {
        for (key in fakeKeys) {
            val text = "connection failed: Authorization: Bearer " + key + " (retry later)"
            val (out, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_MESSAGE_TEXT)
            assertFalse("the key leaked into message text: " + out, out.contains(key))
            assertFalse(out.contains("Bearer " + key))
            assertTrue("redaction should report that it changed something", changed)
        }
    }

    @Test
    fun `redaction is idempotent so a second pass cannot reintroduce a key`() {
        val text = "key=" + fakeKeys[0]
        val once = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT).first
        val twice = AttachmentSecrets.redact(once, AttachmentSecrets.TIER_ATTACHMENT).first
        assertFalse(once.contains(fakeKeys[0]))
        assertTrue("a second pass must be a no-op, got: " + twice, twice == once)
    }

    @Test
    fun `credential bearing request headers are replaced whole, never abbreviated`() {
        val shown = HeaderPolicy.describe(
            mapOf(
                "Authorization" to "Bearer " + fakeKeys[0],
                "x-api-key" to fakeKeys[1],
                "Accept" to "application/json",
            ),
        )
        for (key in fakeKeys) assertFalse("a header value leaked: " + shown, shown.contains(key))
        assertTrue("the harmless header should still be visible: " + shown, shown.contains("application/json"))
    }
}

package com.codespace.ide.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8.D: MCP calls ask every time. AUTO_ALL and "always allow" are consent for local tools, not for
 * code that runs on a third-party server the owner connected.
 */
class McpApprovalForcedTest {

    @Test fun anMcpToolAsksEvenWhenEverythingElseIsAllowed() {
        assertTrue(AgentFlowGate.requiresForcedApproval("mcp_github_create_issue"))
        assertTrue(AgentFlowGate.mustAsk("mcp_github_create_issue", autoApproved = true, sessionAllowed = true))
        assertTrue(AgentFlowGate.mustAsk("mcp_files_read", autoApproved = true, sessionAllowed = true))
    }

    @Test fun aConnectorToolStillAsks() {
        assertTrue(AgentFlowGate.requiresForcedApproval("use_connector"))
    }

    @Test fun ordinaryToolsKeepTheirLevel() {
        assertFalse(AgentFlowGate.requiresForcedApproval("read_file"))
        assertFalse(AgentFlowGate.mustAsk("read_file", autoApproved = true, sessionAllowed = false))
        assertTrue(AgentFlowGate.mustAsk("read_file", autoApproved = false, sessionAllowed = false))
        assertTrue(AgentFlowGate.mustAsk("write_file", autoApproved = false, sessionAllowed = false))
    }

    @Test fun aNameThatMerelyMentionsMcpIsNotForced() {
        assertFalse(AgentFlowGate.requiresForcedApproval("search_mcp_docs"))
        assertFalse(AgentFlowGate.requiresForcedApproval("mcp"))
    }
}

package com.codespace.ide.chat.panel

import com.codespace.ide.agent.AgentTools

/**
 * 2026-11-05 (device finding: tool discovery): the chat can say what it can actually do.
 *
 * Before this there was no way to ask from inside the app. The engine has always known - the `/tools`
 * HTTP endpoint serves `AgentTools.toolNames()`, which is the EXECUTION registry - but a user typing
 * in the composer had no equivalent.
 *
 * The list comes from that same registry on purpose. The tool count drifted once already because a
 * hand-maintained list existed beside the real one (fixed in fbb013e: `/tools` now serves
 * toolNames() instead of a 30-name copy). This adds a READER, never a second list.
 *
 * MCP names are appended when they are cached: they are additional tools the agent can call, and
 * omitting them would make the answer incomplete rather than simple.
 */
object ToolDiscovery {

    /** Tool names the chat can currently call, builtin registry first, cached MCP after it. */
    fun names(mcpCached: List<String> = emptyList()): List<String> {
        val builtin = AgentTools.toolNames()
        return (builtin + mcpCached.filter { it !in builtin }).distinct()
    }

    /**
     * Plain-language answer for the transcript.
     *
     * [modeLabel] is passed in rather than computed here: the mode is a UI fact, and this stays
     * pure so the wording can be asserted without a session.
     */
    fun describe(mcpCached: List<String> = emptyList(), modeLabel: String? = null): String {
        val builtin = AgentTools.toolNames()
        val extra = mcpCached.filter { it !in builtin }
        val head = if (modeLabel.isNullOrBlank()) {
            "This chat can call " + builtin.size + " built-in tools"
        } else {
            "In " + modeLabel + " this chat can call " + builtin.size + " built-in tools"
        }
        val extraLine = if (extra.isEmpty()) {
            "No MCP tools are cached right now."
        } else {
            "Plus " + extra.size + " MCP tool(s): " + extra.joinToString(", ") + "."
        }
        return head + ": " + builtin.joinToString(", ") + ". " + extraLine +
            " Connectors are called through use_connector / request_connector."
    }
}

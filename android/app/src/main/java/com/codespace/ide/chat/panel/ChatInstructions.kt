package com.codespace.ide.chat.panel

/**
 * The system instruction for one turn, as production assembles it.
 *
 * WHY THIS IS ITS OWN OBJECT (2026-11-11, Wave 1 Group A): the tool section used to be built inline
 * in ChatSession, which meant the prompt-budget tests could only measure a HELPER
 * ([AgentToolWire.protocol]) and had to assume that was what the provider received. It was not:
 * ChatSession appended a second, filtered block next to the unfiltered one, so the real prompt was
 * 13,170 characters while the guard was measuring a different string. A budget test that measures
 * anything other than the bytes sent is decoration.
 *
 * This object is the single place the instruction is composed. ChatSession calls it; the tests call
 * it; there is no second path to drift.
 */
internal object ChatInstructions {

    /**
     * The model is told that its list is partial, so "I have no tool for that" becomes "I can look
     * for one". Only emitted when something was actually withheld.
     */
    fun toolsetLine(sendTools: Boolean, visible: Int, total: Int): String {
        if (!sendTools) return ""
        if (visible >= total) return ""
        return "\nTool set for this task: " + visible + " of " + total +
            " mode-allowed tools are described below. The rest exist and can be loaded.\n"
    }

    /**
     * Mode rules + tool protocol (offered definitions only) + toolset line + loaded MCP docs +
     * workspace block. [loaded] is a parameter rather than read from ToolLoadout so the tests can
     * pin it; production passes nothing and gets the live loadout.
     */
    fun build(
        mode: ChatMode,
        sendTools: Boolean,
        offered: List<String>,
        wsBlock: String,
        mcpDocs: String,
        loaded: Set<String> = ToolLoadout.extra(),
    ): String {
        val visible = offered.toSet() + loaded
        val total = ToolRegistry.exposedNames(mode).size
        return ChatModePrompts.instruction(mode) +
            (if (sendTools) AgentToolWire.protocol(mode, visible) else "") +
            toolsetLine(sendTools, visible.size, total) +
            (if (mcpDocs.isBlank()) "" else "\n\n## EXTERNAL MCP TOOLS (loaded for this task)\n" + mcpDocs) +
            (if (wsBlock.isEmpty()) "" else "\n\n" + wsBlock)
    }
}

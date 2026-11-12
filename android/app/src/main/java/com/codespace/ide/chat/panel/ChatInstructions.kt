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
 *
 * FACTS (2026-11-11): remembered facts enter HERE too, as a fourth budgeted block. They are placed
 * BEFORE the workspace block and fitted to whatever the mode's cap has left, so memory can never be
 * the thing that pushes a request over budget - it shrinks to fit, and says nothing when there is
 * no room, instead of silently evicting a tool definition the turn actually needs.
 */
internal object ChatInstructions {

    /** The measured caps the budget tests assert. Ask is tight; everything else is generous. */
    const val ASK_CAP = 5000
    const val AGENT_CAP = 12500

    fun capFor(mode: ChatMode): Int = if (mode == ChatMode.ASK) ASK_CAP else AGENT_CAP

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
     * workspace block, then whatever memory fits. [loaded] is a parameter rather than read from
     * ToolLoadout so the tests can pin it; production passes nothing and gets the live loadout.
     *
     * [memory] is the raw remembered-facts block for this request. It is NOT concatenated blindly:
     * [fitMemory] measures the assembled prompt against [capFor] and hands back only what fits.
     */
    fun build(
        mode: ChatMode,
        sendTools: Boolean,
        offered: List<String>,
        wsBlock: String,
        mcpDocs: String,
        loaded: Set<String> = ToolLoadout.extra(),
        memory: String = "",
    ): String {
        val visible = offered.toSet() + loaded
        val total = ToolRegistry.exposedNames(mode).size
        val base = ChatModePrompts.instruction(mode) +
            (if (sendTools) AgentToolWire.protocol(mode, visible) else "") +
            toolsetLine(sendTools, visible.size, total) +
            (if (mcpDocs.isBlank()) "" else "\n\n## EXTERNAL MCP TOOLS (loaded for this task)\n" + mcpDocs) +
            (if (wsBlock.isEmpty()) "" else "\n\n" + wsBlock)
        return base + fitMemory(memory, capFor(mode) - base.length)
    }

    /**
     * Memory gets the leftover budget, never a fixed slice. A negative or tiny leftover means the
     * turn is already at its cap, and no memory block is emitted at all - a truncated fact list is
     * worse than none, because half a fact reads as a whole one.
     *
     * The separator counts against the budget, so the returned string is exactly what may be added.
     */
    fun fitMemory(memory: String, remaining: Int): String {
        if (memory.isBlank() || remaining <= 0) return ""
        val withSeparator = "\n\n" + memory.trim()
        if (withSeparator.length <= remaining) return withSeparator
        // Too big for the room left: cut at the last complete line that still fits.
        val budget = remaining - 2
        if (budget <= 0) return ""
        val lines = memory.trim().lines()
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length + 1 > budget) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        // A header with no facts under it is noise, not memory.
        if (sb.lines().count { it.trimStart().startsWith("- ") } == 0) return ""
        return "\n\n" + sb.toString().trimEnd()
    }
}

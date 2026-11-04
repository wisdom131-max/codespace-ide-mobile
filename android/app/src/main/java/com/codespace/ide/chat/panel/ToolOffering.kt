package com.codespace.ide.chat.panel

/**
 * Advisor ruling 2026-11-04 — what to do about a model's tool capability.
 *
 * VS Code can ask: the model declares `supportsToolCalling` and agent mode is offered only to
 * models that have it (`conversation/vscode-node/chatParticipants.ts:158`), falling back to
 * another endpoint otherwise (`prompt/node/executionSubagentToolCallingLoop.ts:88`). VN Code
 * talks to arbitrary OpenAI-compatible endpoints, so for most models there is no declaration to
 * read. That is why the capability is a TRI-STATE and why "unknown" must keep working:
 *
 *   true      offer tools (native caller)
 *   null      offer tools - the shipped behaviour, and the only honest default when nothing is known
 *   false     do not offer them, and say so in one activity row rather than letting the model
 *             look like it refused
 *
 * A mode that runs no tools never sends the wire at all, whatever the capability says.
 */
internal object ToolOffering {

    data class Plan(val sendTools: Boolean, val note: String?)

    fun plan(mode: ChatMode, supportsTools: Boolean?): Plan {
        if (!AgentToolWire.modeRunsTools(mode)) return Plan(false, null)
        return if (supportsTools == false) {
            Plan(
                sendTools = false,
                note = "This model is marked as not supporting tool calling, so tools were not " +
                    "offered. It answered from the conversation only. Pick a model that supports " +
                    "tools in Settings -> AI providers to let it act on the project.",
            )
        } else {
            Plan(true, null)
        }
    }
}

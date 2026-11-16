package com.codespace.ide.chat.provider

import com.codespace.ide.engine.CallVerdict
import com.codespace.ide.engine.NormalisedToolCall
import com.codespace.ide.engine.ToolCallNormaliser

enum class ToolOutcome { SUCCESS, FAILED, CANCELLED }

/**
 * The provider's answer, tied back to the call it belongs to. `result` is the tool's own text; this
 * type never invents one, and a `FAILED` result stays failed.
 */
data class ToolResultEnvelope(
    val callId: String,
    val name: String,
    val outcome: ToolOutcome,
    val result: String,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/**
 * PROJECT FIVE, STAGE 5.2 — the door every provider's tool call walks through.
 *
 * The gateway owns no permissions of its own: it validates the call against the registry's names,
 * then hands execution to the ONE executor it is given, which in the app is
 * `AgentTools.executeTool` — the single door Project Three and Four hardened (role checks, approval,
 * MCP approval, prerequisites, health, duplicate-write ledger, side-effect verification). Making the
 * executor a parameter is what keeps that true and what lets a test drive the boundary without an
 * Android Context.
 */
object ToolExecutionGateway {

    data class Outcome(
        val call: NormalisedToolCall,
        val executed: Boolean,
        val result: ToolResultEnvelope?,
        val refusal: String? = null,
    )

    /**
     * @param known the registry's tool names (`AgentTools.toolNames()` in the app).
     * @param claimed ids already executed in this round, so a replayed call cannot repeat a side effect.
     */
    suspend fun run(
        call: NormalisedToolCall,
        known: Collection<String>,
        claimed: MutableSet<String>,
        execute: suspend (name: String, argumentsJson: String, callId: String) -> String,
    ): Outcome {
        ToolCallNormaliser.verdict(call, known).let { v ->
            if (v is CallVerdict.Rejected) {
                return Outcome(call, executed = false, result = null, refusal = v.reason)
            }
        }
        if (!claimed.add(call.identity)) {
            return Outcome(
                call, executed = false, result = null,
                refusal = "this call already ran in this round",
            )
        }
        val output = try {
            execute(call.name, call.argumentsJson, call.id)
        } catch (t: Throwable) {
            "FAILED: " + (t.message ?: "unknown error")
        }
        return Outcome(call, executed = true, result = ToolResultNormaliser.fromExecution(call, output))
    }
}

package com.codespace.ide.chat.provider

import com.codespace.ide.chat.panel.ParsedToolCall
import com.codespace.ide.engine.NormalisedToolCall
import com.codespace.ide.engine.ToolCallNormaliser

/**
 * The text protocol this repo already parses -> the engine's normalised call shape.
 *
 * The dialects live in the engine beside the adapters that receive them; this is the one app-side
 * conversion, for calls that arrived as markup in the reply text. Correctness of the argument object
 * is decided by the engine's own balance check, so both routes agree about what is complete.
 */
object WireCalls {
    internal fun fromWire(
        provider: String,
        calls: List<ParsedToolCall>,
        ids: List<String> = emptyList(),
    ): List<NormalisedToolCall> = calls.mapIndexed { i, c ->
        val complete = c.name.isNotBlank() &&
            c.argumentsJson.length <= ToolCallNormaliser.MAX_ARGUMENT_CHARS &&
            ToolCallNormaliser.argsBalanced(c.argumentsJson)
        NormalisedToolCall(
            id = ids.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: ToolCallNormaliser.syntheticId(provider, i, c.name, c.argumentsJson),
            name = c.name.trim(),
            argumentsJson = c.argumentsJson.ifBlank { "{}" },
            provider = provider,
            complete = complete,
            issues = buildList {
                if (c.name.isBlank()) add("no tool name")
                if (!ToolCallNormaliser.argsBalanced(c.argumentsJson)) add("arguments did not close")
            },
        )
    }
}

/** Turns whatever the executor returned into an envelope, without upgrading anything to success. */
object ToolResultNormaliser {
    fun fromExecution(call: NormalisedToolCall, output: String): ToolResultEnvelope {
        val failed = output.startsWith("FAILED", ignoreCase = true)
        val cancelled = output.startsWith("CANCELLED", ignoreCase = true)
        return ToolResultEnvelope(
            callId = call.id,
            name = call.name,
            outcome = when {
                cancelled -> ToolOutcome.CANCELLED
                failed -> ToolOutcome.FAILED
                else -> ToolOutcome.SUCCESS
            },
            result = output,
            error = if (failed || cancelled) output.lineSequence().first().take(240) else null,
            cancelled = cancelled,
        )
    }
}

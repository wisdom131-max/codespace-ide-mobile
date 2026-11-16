package com.codespace.ide.chat.provider

import com.codespace.ide.chat.panel.ParsedToolCall
import org.json.JSONObject

/**
 * PROJECT FIVE, STAGE 5.2 — one shape for a tool call, whoever asked for it.
 *
 * Gemini sends `functionCall{name,args}`, OpenAI-compatible endpoints send `tool_calls[]` with
 * `arguments` as a STRING that arrives in fragments, Anthropic sends `tool_use` blocks with
 * `input` as an object that streams as `input_json_delta`. The dispatcher downstream must not care.
 *
 * Two rules are load-bearing here:
 *  - A call that has not been fully received is `complete = false` and can never be executed. A
 *    half-read argument object is a normal occurrence when a stream is cut, not an error to paper
 *    over.
 *  - A call that names a tool the registry does not have is refused before execution, so no
 *    provider can reach `AgentTools.executeTool` with a name of its own invention.
 */
data class NormalisedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val provider: String,
    val complete: Boolean,
    val issues: List<String> = emptyList(),
) {
    /** Identity used for duplicate suppression inside one round: provider + name + arguments. */
    val identity: String get() = "$provider|$name|$argumentsJson"
}

sealed interface CallVerdict {
    data object Allowed : CallVerdict
    data class Rejected(val reason: String) : CallVerdict
}

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

object ToolCallNormaliser {

    /** Argument objects larger than this are not a realistic call; treat them as malformed. */
    const val MAX_ARGUMENT_CHARS = 128_000

    internal fun syntheticId(provider: String, index: Int, name: String, args: String): String =
        "call-$provider-$index-" + Integer.toHexString((name + args).hashCode())

    /** Text-protocol calls (the wire this repo already parses) -> normalised calls. */
    internal fun fromWire(
        provider: String,
        calls: List<ParsedToolCall>,
        ids: List<String> = emptyList(),
    ): List<NormalisedToolCall> = calls.mapIndexed { i, c ->
        val complete = c.name.isNotBlank() && argsBalanced(c.argumentsJson) &&
            c.argumentsJson.length <= MAX_ARGUMENT_CHARS
        NormalisedToolCall(
            id = ids.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: syntheticId(provider, i, c.name, c.argumentsJson),
            name = c.name.trim(),
            argumentsJson = c.argumentsJson.ifBlank { "{}" },
            provider = provider,
            complete = complete,
            issues = buildList {
                if (c.name.isBlank()) add("no tool name")
                if (!argsBalanced(c.argumentsJson)) add("arguments did not close")
                if (c.argumentsJson.length > MAX_ARGUMENT_CHARS) add("arguments too large")
            },
        )
    }

    /** Gemini: candidates[0].content.parts[*].functionCall */
    fun fromGemini(json: JSONObject): List<NormalisedToolCall> {
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
        val out = ArrayList<NormalisedToolCall>()
        for (i in 0 until parts.length()) {
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val name = fc.optString("name").trim()
            val args = fc.optJSONObject("args")?.toString() ?: "{}"
            out.add(call("gemini", i, name, args, idHint = fc.optString("id")))
        }
        return out
    }

    /** OpenAI-compatible: choices[0].message.tool_calls[] (or delta for streaming). */
    fun fromOpenAI(json: JSONObject): List<NormalisedToolCall> {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        val calls = choice.optJSONObject("message")?.optJSONArray("tool_calls")
            ?: choice.optJSONObject("delta")?.optJSONArray("tool_calls")
            ?: return emptyList()
        val out = ArrayList<NormalisedToolCall>()
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            out.add(call("openai", i, fn.optString("name").trim(), fn.optString("arguments"), c.optString("id")))
        }
        return out
    }

    /** Anthropic: content[] blocks of type tool_use with an object input. */
    fun fromAnthropic(json: JSONObject): List<NormalisedToolCall> {
        val content = json.optJSONArray("content") ?: return emptyList()
        val out = ArrayList<NormalisedToolCall>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") != "tool_use") continue
            out.add(call("anthropic", i, b.optString("name").trim(),
                b.optJSONObject("input")?.toString() ?: "{}", b.optString("id")))
        }
        return out
    }

    private fun call(provider: String, i: Int, name: String, args: String, idHint: String): NormalisedToolCall {
        val args2 = args.ifBlank { "{}" }
        val complete = name.isNotBlank() && argsBalanced(args2) && args2.length <= MAX_ARGUMENT_CHARS
        return NormalisedToolCall(
            id = idHint.takeIf { it.isNotBlank() } ?: syntheticId(provider, i, name, args2),
            name = name, argumentsJson = args2, provider = provider, complete = complete,
            issues = if (complete) emptyList() else listOf("incomplete call"),
        )
    }

    /**
     * The gate that stands between a parsed call and a side effect. `known` is the registry's own
     * list; anything else is refused here so it can never reach the executor.
     */
    fun verdict(call: NormalisedToolCall, known: Collection<String>): CallVerdict = when {
        !call.complete -> CallVerdict.Rejected("the call did not arrive complete")
        call.name.isBlank() -> CallVerdict.Rejected("the call named no tool")
        call.argumentsJson.length > MAX_ARGUMENT_CHARS -> CallVerdict.Rejected("the arguments were too large")
        !known.contains(call.name) -> CallVerdict.Rejected("no such tool: ${call.name}")
        !jsonObject(call.argumentsJson) -> CallVerdict.Rejected("the arguments are not a JSON object")
        else -> CallVerdict.Allowed
    }

    /**
     * Drops later calls that repeat an id already claimed in this round. A provider that re-sends a
     * call (a retried chunk, a reconnected stream) must not cause the side effect twice.
     */
    fun uniqueById(calls: List<NormalisedToolCall>): Pair<List<NormalisedToolCall>, List<String>> {
        val seen = HashSet<String>()
        val kept = ArrayList<NormalisedToolCall>()
        val dropped = ArrayList<String>()
        for (c in calls) {
            if (seen.add(c.id)) kept.add(c) else dropped.add(c.id)
        }
        return kept to dropped
    }

    internal fun argsBalanced(args: String): Boolean {
        if (args.isBlank()) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in args) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    private fun jsonObject(text: String): Boolean = runCatching { JSONObject(text) }.isSuccess
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

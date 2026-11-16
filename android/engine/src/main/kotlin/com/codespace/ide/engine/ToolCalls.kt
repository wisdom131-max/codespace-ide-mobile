package com.codespace.ide.engine

/**
 * PROJECT FIVE, STAGE 5.2 — one shape for a tool call, whoever asked for it.
 *
 * Gemini sends `functionCall{name,args}`, OpenAI-compatible endpoints send `tool_calls[]` whose
 * `arguments` is a STRING that arrives in fragments, Anthropic sends `tool_use` blocks whose `input`
 * streams as `input_json_delta`. Downstream must not care.
 *
 * Two rules are load-bearing:
 *  - A call that has not fully arrived is `complete = false` and can never be executed.
 *  - A call naming a tool the registry does not have is refused before execution, so no provider can
 *    reach `AgentTools.executeTool` with a name of its own invention.
 *
 * Parsing is dependency-free ([JsonLite]) because this module ships no JSON library on purpose.
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

object ToolCallNormaliser {

    /** Argument objects larger than this are not a realistic call; treat them as malformed. */
    const val MAX_ARGUMENT_CHARS = 128_000

    fun syntheticId(provider: String, index: Int, name: String, args: String): String =
        "call-$provider-$index-" + Integer.toHexString((name + args).hashCode())

    /** Gemini: candidates[0].content.parts[*].functionCall */
    fun fromGemini(json: String): List<NormalisedToolCall> {
        val root = JsonLite.obj(JsonLite.parse(json)) ?: return emptyList()
        val parts = JsonLite.arr(JsonLite.obj(JsonLite.arr(root["candidates"])?.firstOrNull())?.get("content")?.let { JsonLite.obj(it)?.get("parts") })
            ?: return emptyList()
        return parts.mapIndexedNotNull { i, part ->
            val fc = JsonLite.obj(JsonLite.obj(part)?.get("functionCall")) ?: return@mapIndexedNotNull null
            call(
                "gemini", i, JsonLite.str(fc["name"]).trim(),
                JsonLite.obj(fc["args"])?.let { JsonLite.write(it) } ?: "{}",
                JsonLite.str(fc["id"]),
            )
        }
    }

    /** OpenAI-compatible: choices[0].message.tool_calls[] (or delta for streaming). */
    fun fromOpenAI(json: String): List<NormalisedToolCall> {
        val root = JsonLite.obj(JsonLite.parse(json)) ?: return emptyList()
        val choice = JsonLite.obj(JsonLite.arr(root["choices"])?.firstOrNull()) ?: return emptyList()
        val calls = JsonLite.arr(JsonLite.obj(choice["message"])?.get("tool_calls"))
            ?: JsonLite.arr(JsonLite.obj(choice["delta"])?.get("tool_calls"))
            ?: return emptyList()
        return calls.mapIndexedNotNull { i, c ->
            val fn = JsonLite.obj(JsonLite.obj(c)?.get("function")) ?: return@mapIndexedNotNull null
            call("openai", i, JsonLite.str(fn["name"]).trim(), JsonLite.str(fn["arguments"]),
                JsonLite.str(JsonLite.obj(c)?.get("id")))
        }
    }

    /** Anthropic: content[] blocks of type tool_use with an object input. */
    fun fromAnthropic(json: String): List<NormalisedToolCall> {
        val root = JsonLite.obj(JsonLite.parse(json)) ?: return emptyList()
        val content = JsonLite.arr(root["content"]) ?: return emptyList()
        return content.mapIndexedNotNull { i, raw ->
            val b = JsonLite.obj(raw) ?: return@mapIndexedNotNull null
            if (JsonLite.str(b["type"]) != "tool_use") return@mapIndexedNotNull null
            call("anthropic", i, JsonLite.str(b["name"]).trim(),
                JsonLite.obj(b["input"])?.let { JsonLite.write(it) } ?: "{}",
                JsonLite.str(b["id"]))
        }
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
     * The gate between a parsed call and a side effect. `known` is the registry's own list; anything
     * else is refused here so it can never reach the executor.
     */
    fun verdict(call: NormalisedToolCall, known: Collection<String>): CallVerdict = when {
        !call.complete -> CallVerdict.Rejected("the call did not arrive complete")
        call.name.isBlank() -> CallVerdict.Rejected("the call named no tool")
        call.argumentsJson.length > MAX_ARGUMENT_CHARS -> CallVerdict.Rejected("the arguments were too large")
        !known.contains(call.name) -> CallVerdict.Rejected("no such tool: ${call.name}")
        JsonLite.obj(JsonLite.parse(call.argumentsJson)) == null ->
            CallVerdict.Rejected("the arguments are not a JSON object")
        else -> CallVerdict.Allowed
    }

    /** Drops later calls repeating an id already claimed this round (replays, reconnects). */
    fun uniqueById(calls: List<NormalisedToolCall>): Pair<List<NormalisedToolCall>, List<String>> {
        val seen = HashSet<String>()
        val kept = ArrayList<NormalisedToolCall>()
        val dropped = ArrayList<String>()
        for (c in calls) {
            if (seen.add(c.id)) kept.add(c) else dropped.add(c.id)
        }
        return kept to dropped
    }

    fun argsBalanced(args: String): Boolean {
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
}

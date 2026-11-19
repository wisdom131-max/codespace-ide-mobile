package com.codespace.ide.engine


/**
 * PROJECT FIVE, STAGE 5.2 — the stream, said the same way by every provider.
 *
 * Deliberately small and bounded: the normaliser keeps one pending tool call per index (never the
 * event history), caps each argument buffer, and releases a call as soon as it is handed to the
 * caller. A long answer therefore costs the answer, not a parallel copy of every chunk.
 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallStarted(val id: String, val name: String) : StreamEvent
    data class ToolCallArgsDelta(val id: String, val chunk: String) : StreamEvent
    data class ToolCallCompleted(val call: NormalisedToolCall) : StreamEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent
    data class Ended(val reason: StreamEnd, val detail: String = "") : StreamEvent
    data class Failed(val message: String) : StreamEvent
    data object Cancelled : StreamEvent
}

/**
 * The end of a stream, stated accurately. `OUTPUT_LIMIT` is NOT a finished answer and
 * `INCOMPLETE_TOOL_CALL` is not a call: both exist so nothing downstream has to infer intent from
 * a buffer that simply stopped.
 */
enum class StreamEnd { NATURAL, TOOL_CALLS, OUTPUT_LIMIT, CANCELLED, PROVIDER_ERROR, INCOMPLETE_TOOL_CALL, UNKNOWN }

enum class StreamProvider { GEMINI, OPENAI, ANTHROPIC }

/**
 * Feeds provider payloads in, emits normalised events out. Not thread safe by design: one
 * instance belongs to one response.
 */
class ProviderStreamNormaliser(private val provider: StreamProvider) {

    private data class Pending(val id: String, val name: String, val args: StringBuilder, var stopped: Boolean = false)

    private val pending = LinkedHashMap<Int, Pending>()
    private val startedIds = HashSet<String>()
    private var finished: StreamEnd? = null

    companion object {
        /** Argument buffer ceiling per call; beyond this the call is marked incomplete, not truncated. */
        const val MAX_PENDING_ARGS = 64_000
        const val MAX_PENDING_CALLS = 16
    }

    /** True once an End has been emitted; later payloads are ignored (late events after cancel). */
    val ended: Boolean get() = finished != null

    fun feed(payload: String): List<StreamEvent> {
        if (finished != null) return emptyList()
        val json = JsonLite.obj(JsonLite.parse(payload))
            ?: return listOf(StreamEvent.Failed("the provider sent something that is not an event"))
        return when (provider) {
            StreamProvider.GEMINI -> feedGemini(json)
            StreamProvider.OPENAI -> feedOpenAI(json)
            StreamProvider.ANTHROPIC -> feedAnthropic(json)
        }
    }

    /** The transport stopped. Anything still half-received is reported, never executed. */
    fun finish(): List<StreamEvent> {
        if (finished != null) return emptyList()
        val out = ArrayList<StreamEvent>()
        for ((i, p) in pending.toSortedMap()) {
            out.add(
                StreamEvent.Ended(
                    StreamEnd.INCOMPLETE_TOOL_CALL,
                    "tool call ${p.name.ifBlank { "#$i" }} was still arriving when the stream ended",
                )
            )
        }
        pending.clear()
        if (out.isEmpty()) {
            finished = StreamEnd.UNKNOWN
            out.add(StreamEvent.Ended(StreamEnd.UNKNOWN, "the stream ended without a finish reason"))
        } else {
            // A half-received call closes the stream: whatever arrives after this is a late chunk and
            // is ignored, so it cannot complete a call the transport already gave up on.
            finished = StreamEnd.INCOMPLETE_TOOL_CALL
        }
        return out
    }

    fun cancel(): List<StreamEvent> {
        if (finished != null) return emptyList()
        finished = StreamEnd.CANCELLED
        pending.clear()
        return listOf(StreamEvent.Cancelled, StreamEvent.Ended(StreamEnd.CANCELLED))
    }

    private fun end(reason: StreamEnd, detail: String = ""): List<StreamEvent> {
        if (finished != null) return emptyList()
        finished = reason
        val out = ArrayList<StreamEvent>()
        pending.toSortedMap().forEach { (_, p) ->
            if (!p.stopped) out.add(
                StreamEvent.Ended(
                    StreamEnd.INCOMPLETE_TOOL_CALL,
                    "tool call ${p.name.ifBlank { "?" }} was incomplete at finish",
                )
            )
        }
        pending.clear()
        out.add(StreamEvent.Ended(reason, detail))
        return out
    }

    // ---- Gemini -------------------------------------------------------------------------------

    private fun feedGemini(root: Map<String, Any?>): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        JsonLite.obj(root["error"])?.let {
            return listOf(StreamEvent.Failed(JsonLite.str(it["message"]).take(240)))
        }
        val cand = JsonLite.obj(JsonLite.arr(root["candidates"])?.firstOrNull())
        val parts = JsonLite.arr(JsonLite.obj(cand?.get("content"))?.get("parts"))
        if (parts != null) {
            for (i in parts.indices) {
                val p = JsonLite.obj(parts[i]) ?: continue
                JsonLite.str(p["text"]).takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.TextDelta(it)) }
                val fc = JsonLite.obj(p["functionCall"]) ?: continue
                val name = JsonLite.str(fc["name"]).trim()
                val args = JsonLite.obj(fc["args"])?.let { JsonLite.write(it) } ?: "{}"
                val id = JsonLite.str(fc["id"]).takeIf { it.isNotBlank() }
                    ?: ToolCallNormaliser.syntheticId("gemini", i, name, args)
                if (!startedIds.add(id)) continue // replayed part
                out.add(StreamEvent.ToolCallStarted(id, name))
                out.add(
                    StreamEvent.ToolCallCompleted(
                        NormalisedToolCall(id, name, args, "gemini", complete = name.isNotBlank() && ToolCallNormaliser.argsBalanced(args))
                    )
                )
            }
        }
        JsonLite.obj(root["usageMetadata"])?.let { u ->
            out.add(StreamEvent.Usage(JsonLite.int(u["promptTokenCount"]), JsonLite.int(u["candidatesTokenCount"])))
        }
        when (JsonLite.str(cand?.get("finishReason")).uppercase()) {
            "STOP" -> out.addAll(end(StreamEnd.NATURAL))
            "MAX_TOKENS" -> out.addAll(end(StreamEnd.OUTPUT_LIMIT))
            "SAFETY", "RECITATION", "BLOCKLIST" ->
                out.addAll(end(StreamEnd.PROVIDER_ERROR, "the provider withheld this answer"))
            else -> Unit
        }
        return out
    }

    // ---- OpenAI-compatible --------------------------------------------------------------------

    private fun feedOpenAI(rootIn: Map<String, Any?>): List<StreamEvent> {
        // Cloudflare Workers AI answers /ai/run/<model> with the completion INSIDE "result":
        // {"result":{"choices":[{"message":{"content":"OK"}}]}}. Without this unwrap a successful
        // 200 produced no text at all. Verified live 2026-11-17.
        val root = JsonLite.obj(rootIn["result"]) ?: rootIn
        val out = ArrayList<StreamEvent>()
        JsonLite.obj(root["error"])?.let {
            return listOf(StreamEvent.Failed(JsonLite.str(it["message"]).take(240)))
        }
        val choice = JsonLite.obj(JsonLite.arr(root["choices"])?.firstOrNull()) ?: return out
        val delta = JsonLite.obj(choice["delta"]) ?: JsonLite.obj(choice["message"])
        JsonLite.str(delta?.get("content")).takeIf { it.isNotEmpty() }
            ?.let { out.add(StreamEvent.TextDelta(it)) }
        JsonLite.str(delta?.get("reasoning_content")).takeIf { it.isNotEmpty() }
            ?.let { out.add(StreamEvent.ReasoningDelta(it)) }
        val calls = JsonLite.arr(delta?.get("tool_calls"))
        if (calls != null) {
            for (i in calls.indices) {
                val c = JsonLite.obj(calls[i]) ?: continue
                val index = JsonLite.int(c["index"], i)
                val fn = JsonLite.obj(c["function"]) ?: continue
                val p = pending.getOrPut(index) {
                    val name = JsonLite.str(fn["name"]).trim()
                    val id = JsonLite.str(c["id"]).takeIf { it.isNotBlank() }
                        ?: ToolCallNormaliser.syntheticId("openai", index, name, "")
                    Pending(id, name, StringBuilder())
                }
                if (p.name.isBlank() && JsonLite.str(fn["name"]).isNotBlank()) {
                    // Some gateways send the name in a later chunk than the id.
                    pending[index] = Pending(p.id, JsonLite.str(fn["name"]).trim(), p.args)
                    out.add(StreamEvent.ToolCallStarted(p.id, JsonLite.str(fn["name"]).trim()))
                } else if (startedIds.add(p.id)) {
                    out.add(StreamEvent.ToolCallStarted(p.id, p.name))
                }
                val frag = JsonLite.str(fn["arguments"])
                if (frag.isNotEmpty()) {
                    if (p.args.length + frag.length > MAX_PENDING_ARGS) {
                        pending.remove(index)
                        out.add(StreamEvent.Failed("tool arguments exceeded $MAX_PENDING_ARGS characters"))
                    } else {
                        p.args.append(frag)
                        out.add(StreamEvent.ToolCallArgsDelta(p.id, frag))
                    }
                }
            }
        }
        JsonLite.obj(root["usage"])?.let { u ->
            out.add(StreamEvent.Usage(JsonLite.int(u["prompt_tokens"]), JsonLite.int(u["completion_tokens"])))
        }
        when (JsonLite.str(choice["finish_reason"])) {
            "stop" -> out.addAll(end(StreamEnd.NATURAL))
            "length" -> out.addAll(end(StreamEnd.OUTPUT_LIMIT))
            "tool_calls", "function_call" -> {
                // P0.5 (2026-11-19): the device diagnostic showed "end reason: tool_calls" on a
                // request with ZERO tools offered. A finish reason that announces calls the body
                // never contained is an inconsistent response, not a successful tool handoff.
                val hadCalls = pending.isNotEmpty() || startedIds.isNotEmpty()
                closePending(out)
                if (hadCalls) out.addAll(end(StreamEnd.TOOL_CALLS))
                else out.addAll(end(
                    StreamEnd.INCOMPLETE_TOOL_CALL,
                    "the provider reported tool calls but sent none",
                ))
            }
            "content_filter" -> out.addAll(end(StreamEnd.PROVIDER_ERROR, "the provider withheld this answer"))
            else -> Unit
        }
        return out
    }

    private fun closePending(out: MutableList<StreamEvent>) {
        pending.toSortedMap().forEach { (_, p) ->
            val args = p.args.toString().ifBlank { "{}" }
            val complete = p.name.isNotBlank() && ToolCallNormaliser.argsBalanced(args)
            out.add(
                StreamEvent.ToolCallCompleted(
                    NormalisedToolCall(p.id, p.name, args, "openai", complete,
                        if (complete) emptyList() else listOf("incomplete call"))
                )
            )
            p.stopped = true
        }
        pending.clear()
    }

    // ---- Anthropic ----------------------------------------------------------------------------

    private fun feedAnthropic(root: Map<String, Any?>): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        when (JsonLite.str(root["type"])) {
            "error" -> return listOf(
                StreamEvent.Failed(JsonLite.str(JsonLite.obj(root["error"])?.get("message")).take(240)
                    .ifEmpty { "provider error" })
            )
            "content_block_start" -> {
                val i = JsonLite.int(root["index"], pending.size)
                val block = JsonLite.obj(root["content_block"]) ?: return out
                when (JsonLite.str(block["type"])) {
                    "text" -> JsonLite.str(block["text"]).takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.TextDelta(it)) }
                    "tool_use" -> {
                        val name = JsonLite.str(block["name"]).trim()
                        val id = JsonLite.str(block["id"]).takeIf { it.isNotBlank() }
                            ?: ToolCallNormaliser.syntheticId("anthropic", i, name, "")
                        if (pending.size < MAX_PENDING_CALLS) pending[i] = Pending(id, name, StringBuilder())
                        if (startedIds.add(id)) out.add(StreamEvent.ToolCallStarted(id, name))
                        JsonLite.obj(block["input"])?.let { input ->
                            if (input.isNotEmpty()) {
                                val text = JsonLite.write(input)
                                pending[i]?.args?.append(text)
                                out.add(StreamEvent.ToolCallArgsDelta(id, text))
                            }
                        }
                    }
                }
            }
            "content_block_delta" -> {
                val i = JsonLite.int(root["index"], 0)
                val d = JsonLite.obj(root["delta"]) ?: return out
                when (JsonLite.str(d["type"])) {
                    "text_delta" -> JsonLite.str(d["text"]).takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.TextDelta(it)) }
                    "thinking_delta" -> JsonLite.str(d["thinking"]).takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.ReasoningDelta(it)) }
                    "input_json_delta" -> {
                        val p = pending[i] ?: return out
                        val frag = JsonLite.str(d["partial_json"])
                        if (p.args.length + frag.length > MAX_PENDING_ARGS) {
                            pending.remove(i)
                            out.add(StreamEvent.Failed("tool arguments exceeded ${MAX_PENDING_ARGS} characters"))
                        } else {
                            p.args.append(frag)
                            out.add(StreamEvent.ToolCallArgsDelta(p.id, frag))
                        }
                    }
                }
            }
            "content_block_stop" -> {
                val i = JsonLite.int(root["index"], 0)
                val p = pending.remove(i) ?: return out
                val args = p.args.toString().ifBlank { "{}" }
                val complete = p.name.isNotBlank() && ToolCallNormaliser.argsBalanced(args)
                out.add(
                    StreamEvent.ToolCallCompleted(
                        NormalisedToolCall(p.id, p.name, args, "anthropic", complete,
                            if (complete) emptyList() else listOf("incomplete call"))
                    )
                )
            }
            "message_delta" -> {
                val stop = JsonLite.str(JsonLite.obj(root["delta"])?.get("stop_reason"))
                JsonLite.obj(root["usage"])?.let { u ->
                    out.add(StreamEvent.Usage(JsonLite.int(u["input_tokens"]), JsonLite.int(u["output_tokens"])))
                }
                when (stop) {
                    "end_turn", "stop_sequence" -> out.addAll(end(StreamEnd.NATURAL))
                    "max_tokens" -> out.addAll(end(StreamEnd.OUTPUT_LIMIT))
                    "tool_use" -> out.addAll(end(StreamEnd.TOOL_CALLS))
                    "refusal" -> out.addAll(end(StreamEnd.PROVIDER_ERROR, "the provider withheld this answer"))
                    "" -> Unit
                    else -> out.addAll(end(StreamEnd.UNKNOWN, stop))
                }
            }
        }
        return out
    }
}

package com.codespace.ide.chat.provider

import org.json.JSONObject

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
        val json = runCatching { JSONObject(payload) }.getOrNull()
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

    private fun feedGemini(json: JSONObject): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        json.optJSONObject("error")?.let {
            return listOf(StreamEvent.Failed(it.optString("message").take(240)))
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                p.optString("text").takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.TextDelta(it)) }
                val fc = p.optJSONObject("functionCall") ?: continue
                val name = fc.optString("name").trim()
                val args = fc.optJSONObject("args")?.toString() ?: "{}"
                val id = fc.optString("id").takeIf { it.isNotBlank() }
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
        json.optJSONObject("usageMetadata")?.let { u ->
            out.add(StreamEvent.Usage(u.optInt("promptTokenCount", 0), u.optInt("candidatesTokenCount", 0)))
        }
        when (cand?.optString("finishReason").orEmpty().uppercase()) {
            "STOP" -> out.addAll(end(StreamEnd.NATURAL))
            "MAX_TOKENS" -> out.addAll(end(StreamEnd.OUTPUT_LIMIT))
            "SAFETY", "RECITATION", "BLOCKLIST" ->
                out.addAll(end(StreamEnd.PROVIDER_ERROR, "the provider withheld this answer"))
            else -> Unit
        }
        return out
    }

    // ---- OpenAI-compatible --------------------------------------------------------------------

    private fun feedOpenAI(json: JSONObject): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        json.optJSONObject("error")?.let {
            return listOf(StreamEvent.Failed(it.optString("message").take(240)))
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return out
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        delta?.optString("content")?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.TextDelta(it)) }
        delta?.optString("reasoning_content")?.takeIf { it.isNotEmpty() }
            ?.let { out.add(StreamEvent.ReasoningDelta(it)) }
        val calls = delta?.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val index = c.optInt("index", i)
                val fn = c.optJSONObject("function") ?: continue
                val p = pending.getOrPut(index) {
                    val name = fn.optString("name").trim()
                    val id = c.optString("id").takeIf { it.isNotBlank() }
                        ?: ToolCallNormaliser.syntheticId("openai", index, name, "")
                    Pending(id, name, StringBuilder())
                }
                if (p.name.isBlank() && fn.optString("name").isNotBlank()) {
                    // Some gateways send the name in a later chunk than the id.
                    pending[index] = Pending(p.id, fn.optString("name").trim(), p.args)
                    out.add(StreamEvent.ToolCallStarted(p.id, fn.optString("name").trim()))
                } else if (startedIds.add(p.id)) {
                    out.add(StreamEvent.ToolCallStarted(p.id, p.name))
                }
                val frag = fn.optString("arguments")
                if (frag.isNotEmpty()) {
                    if (p.args.length + frag.length > MAX_PENDING_ARGS) {
                        pending.remove(index)
                        out.add(StreamEvent.Failed("tool arguments exceeded ${MAX_PENDING_ARGS} characters"))
                    } else {
                        p.args.append(frag)
                        out.add(StreamEvent.ToolCallArgsDelta(p.id, frag))
                    }
                }
            }
        }
        json.optJSONObject("usage")?.let { u ->
            out.add(StreamEvent.Usage(u.optInt("prompt_tokens", 0), u.optInt("completion_tokens", 0)))
        }
        when (choice.optString("finish_reason")) {
            "stop" -> out.addAll(end(StreamEnd.NATURAL))
            "length" -> out.addAll(end(StreamEnd.OUTPUT_LIMIT))
            "tool_calls", "function_call" -> {
                closePending(out)
                out.addAll(end(StreamEnd.TOOL_CALLS))
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

    private fun feedAnthropic(json: JSONObject): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        when (json.optString("type")) {
            "error" -> return listOf(
                StreamEvent.Failed(json.optJSONObject("error")?.optString("message")?.take(240) ?: "provider error")
            )
            "content_block_start" -> {
                val i = json.optInt("index", pending.size)
                val block = json.optJSONObject("content_block") ?: return out
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.TextDelta(it)) }
                    "tool_use" -> {
                        val name = block.optString("name").trim()
                        val id = block.optString("id").takeIf { it.isNotBlank() }
                            ?: ToolCallNormaliser.syntheticId("anthropic", i, name, "")
                        if (pending.size < MAX_PENDING_CALLS) pending[i] = Pending(id, name, StringBuilder())
                        if (startedIds.add(id)) out.add(StreamEvent.ToolCallStarted(id, name))
                        block.optJSONObject("input")?.let { input ->
                            if (input.length() > 0) {
                                pending[i]?.args?.append(input.toString())
                                out.add(StreamEvent.ToolCallArgsDelta(id, input.toString()))
                            }
                        }
                    }
                }
            }
            "content_block_delta" -> {
                val i = json.optInt("index", 0)
                val d = json.optJSONObject("delta") ?: return out
                when (d.optString("type")) {
                    "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.TextDelta(it)) }
                    "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }
                        ?.let { out.add(StreamEvent.ReasoningDelta(it)) }
                    "input_json_delta" -> {
                        val p = pending[i] ?: return out
                        val frag = d.optString("partial_json")
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
                val i = json.optInt("index", 0)
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
                val stop = json.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                json.optJSONObject("usage")?.let { u ->
                    out.add(StreamEvent.Usage(u.optInt("input_tokens", 0), u.optInt("output_tokens", 0)))
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

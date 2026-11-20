package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * STAGE 3 (2026-11-20) — native structured tool calling, only where it is VERIFIED.
 *
 * A capability flag is a claim, not proof: the research found TOOL_CALLS declared in
 * ProviderCapabilities while the request path never sent declarations. So native calling requires
 * both the capability AND a verified profile, and everything else keeps the text protocol.
 * Native calls become the same [ToolCall] and still go through AgentTools.executeTool.
 */
object NativeToolCalling {

    data class Declaration(val name: String, val description: String, val parameters: JSONObject)

    enum class Wire { OPENAI, ANTHROPIC, GEMINI, TEXT }

    /** Native is used only when the endpoint is verified for it. Unverified means text. */
    fun wireFor(capabilities: Set<String>, verified: Boolean, custom: Boolean = false): Wire = when {
        !verified -> Wire.TEXT
        custom -> Wire.TEXT
        "TOOL_CALLS" in capabilities -> Wire.OPENAI
        else -> Wire.TEXT
    }

    fun openAiDeclarations(tools: List<Declaration>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.parameters),
                    ),
            )
        }
        return arr
    }

    fun anthropicDeclarations(tools: List<Declaration>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", t.parameters),
            )
        }
        return arr
    }

    fun geminiDeclarations(tools: List<Declaration>): JSONObject {
        val fns = JSONArray()
        for (t in tools) {
            fns.put(JSONObject().put("name", t.name).put("description", t.description))
        }
        return JSONObject().put("functionDeclarations", fns)
    }

    /** OpenAI-compatible: arguments arrive as a JSON STRING and must be parsed, never trusted. */
    fun parseOpenAi(message: JSONObject): List<ToolCall> {
        val calls = message.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<ToolCall>(calls.length())
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val args = try {
                JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" })
            } catch (_: Throwable) {
                continue // malformed arguments never execute
            }
            out.add(ToolCall(c.optString("id", "n$i"), name, args, ToolCall.Source.NATIVE))
        }
        return out
    }

    /** Anthropic: tool_use blocks inside the content array. */
    fun parseAnthropic(content: JSONArray): List<ToolCall> {
        val out = ArrayList<ToolCall>(2)
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") != "tool_use") continue
            val name = b.optString("name", "").trim()
            if (name.isEmpty()) continue
            out.add(
                ToolCall(
                    b.optString("id", "a$i"), name,
                    b.optJSONObject("input") ?: JSONObject(), ToolCall.Source.NATIVE,
                ),
            )
        }
        return out
    }

    /** Gemini: functionCall parts. */
    fun parseGemini(parts: JSONArray): List<ToolCall> {
        val out = ArrayList<ToolCall>(2)
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val fc = p.optJSONObject("functionCall") ?: continue
            val name = fc.optString("name", "").trim()
            if (name.isEmpty()) continue
            out.add(ToolCall("g$i", name, fc.optJSONObject("args") ?: JSONObject(), ToolCall.Source.NATIVE))
        }
        return out
    }

    /**
     * Streaming: arguments arrive in pieces and are only usable when the provider marks the call
     * complete. An incomplete call yields nothing, so it can never execute on partial arguments.
     */
    fun accumulateOpenAiDeltas(deltas: List<JSONObject>, complete: Boolean): List<ToolCall> {
        if (!complete) return emptyList()
        val merged = JSONObject()
        for (d in deltas) {
            val calls = d.optJSONArray("tool_calls") ?: continue
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val idx = c.optString("index", "0")
                val slot = merged.optJSONObject(idx) ?: JSONObject().also { merged.put(idx, it) }
                c.optString("id", "").takeIf { it.isNotEmpty() }?.let { slot.put("id", it) }
                val fn = c.optJSONObject("function") ?: continue
                fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { slot.put("name", it) }
                fn.optString("arguments", "").takeIf { it.isNotEmpty() }?.let {
                    slot.put("arguments", slot.optString("arguments", "") + it)
                }
            }
        }
        val out = ArrayList<ToolCall>(2)
        val keys = merged.keys().asSequence().toList()
        for (k in keys) {
            val slot = merged.optJSONObject(k) ?: continue
            val name = slot.optString("name", "").trim()
            if (name.isEmpty()) continue
            val args = try {
                JSONObject(slot.optString("arguments", "{}").ifBlank { "{}" })
            } catch (_: Throwable) {
                continue
            }
            out.add(ToolCall(slot.optString("id", "s$k"), name, args, ToolCall.Source.NATIVE))
        }
        return out
    }
}

package com.codespace.ide.chat

import android.content.Context
import com.codespace.ide.agent.AgentTools
import com.codespace.ide.agent.McpClientManager
import org.json.JSONArray
import org.json.JSONObject

/** Native OpenAI calls and Qwen/legacy text calls share the existing permission gate. */
object NativeToolProtocol {
    private val tags = Regex("""<(tool|tool_call)>\s*(\{.*?\})\s*</\1>""", RegexOption.DOT_MATCHES_ALL)

    fun calls(text: String): JSONArray {
        val out = JSONArray()
        tags.findAll(text).forEachIndexed { index, match ->
            val call = JSONObject(match.groupValues[2])
            val args = call.optJSONObject("arguments") ?: JSONObject()
            out.put(JSONObject().put("id", call.optString("_callId").ifBlank { "call_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_" + index })
                .put("type", "function").put("function", JSONObject()
                    .put("name", call.optString("name", "error")).put("arguments", args.toString())))
        }
        return out
    }

    fun assistantMessage(text: String, calls: JSONArray): JSONObject = JSONObject()
        .put("role", "assistant").put("content", text.replace(tags, "").trim())
        .put("tool_calls", calls)

    fun resultMessage(calls: JSONArray, index: Int, result: String): JSONObject = JSONObject()
        .put("role", "tool").put("tool_call_id", calls.getJSONObject(index).getString("id"))
        .put("content", result)

    /** Preserve native call IDs/arguments without treating the model's prose as execution. */
    fun responseText(message: JSONObject): String {
        val text = if (message.isNull("content")) "" else message.optString("content")
        val calls = message.optJSONArray("tool_calls") ?: return text
        val out = StringBuilder(if (calls.length() > 0) text.replace(tags, "").trim() else text)
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val f = c.getJSONObject("function")
            // Malformed arguments fail visibly, never convert them to an empty command.
            val raw = f.get("arguments")
            val args = if (raw is JSONObject) raw else JSONObject(raw.toString())
            out.append("\n<tool>").append(JSONObject().put("name", f.getString("name"))
                .put("arguments", args).put("_callId", c.getString("id")).toString()).append("</tool>")
        }
        return out.toString()
    }

    class StreamCalls {
        private val entries = sortedMapOf<Int, JSONObject>()
        fun accept(delta: JSONObject) {
            val calls = delta.optJSONArray("tool_calls") ?: return
            for (i in 0 until calls.length()) {
                val part = calls.getJSONObject(i)
                val entry = entries.getOrPut(part.getInt("index")) {
                    JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", ""))
                }
                if (part.has("id")) entry.put("id", part.getString("id"))
                part.optJSONObject("function")?.let { f ->
                    val target = entry.getJSONObject("function")
                    for (key in listOf("name", "arguments")) {
                        if (f.has(key) && !f.isNull(key)) target.put(key, target.optString(key) + f.getString(key))
                    }
                }
            }
        }
        fun response(text: String): String {
            val array = JSONArray()
            entries.values.forEach { array.put(it) }
            return responseText(JSONObject().put("content", text).put("tool_calls", array))
        }
    }

    /** Builtin examples are the canonical argument contract; MCP retains its actual schema. */
    fun schemas(context: Context, allow: List<String>?): JSONArray {
        val array = JSONArray()
        val seen = mutableSetOf<String>()
        tags.findAll(AgentTools.TOOLS_DESCRIPTION).forEach { match ->
            val c = try { JSONObject(match.groupValues[2]) } catch (_: Exception) { return@forEach }
            val name = c.getString("name")
            if ((allow == null || name in allow) && seen.add(name)) {
                val args = c.optJSONObject("arguments") ?: JSONObject()
                val properties = JSONObject()
                args.keys().forEach { key -> properties.put(key, schemaFor(args.get(key))) }
                if (name == "detect_secrets") {
                    properties.remove("text")
                    properties.put("text", JSONObject().put("type", "string"))
                    properties.put("path", JSONObject().put("type", "string"))
                }
                val optional = setOf("workdir", "repo_dir", "project_dir", "scopes", "filter", "staged", "body")
                val required = JSONArray()
                args.keys().forEach { key -> if (key !in optional && name != "detect_secrets" && !(name == "git_branch" && key == "name")) required.put(key) }
                array.put(definition(name, "CodeSpace tool: " + name, JSONObject().put("type", "object")
                    .put("properties", properties).put("required", required).put("additionalProperties", true)))
            }
        }
        if ((allow == null || "detect_secrets" in allow) && seen.add("detect_secrets")) {
            array.put(definition("detect_secrets", "Scan text or a file path for secrets", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("text", JSONObject().put("type", "string"))
                    .put("path", JSONObject().put("type", "string")))))
        }
        McpClientManager.nativeToolDefinitions(context).forEach { tool ->
            val name = tool.getJSONObject("function").getString("name")
            if ((allow == null || name in allow) && seen.add(name)) array.put(tool)
        }
        return array
    }

    private fun definition(name: String, description: String, parameters: JSONObject): JSONObject = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name)
            .put("description", description).put("parameters", parameters))

    private fun schemaFor(value: Any): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean")
        is Number -> JSONObject().put("type", "number")
        is JSONArray -> JSONObject().put("type", "array").put("items", if (value.length() > 0) schemaFor(value.get(0)) else JSONObject())
        is JSONObject -> {
            val properties = JSONObject()
            value.keys().forEach { properties.put(it, schemaFor(value.get(it))) }
            JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", true)
        }
        else -> JSONObject().put("type", "string")
    }
}

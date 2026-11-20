package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE 3 — native calling only where verified; every path lands on the same ToolCall. */
class NativeToolCallingTest {

    private val tools = listOf(
        NativeToolCalling.Declaration("read_file", "Read a file", JSONObject().put("type", "object")),
        NativeToolCalling.Declaration("write_file", "Write a file", JSONObject().put("type", "object")),
    )

    @Test
    fun `openai declarations carry name description and schema`() {
        val arr = NativeToolCalling.openAiDeclarations(tools)
        assertEquals(2, arr.length())
        assertEquals("function", arr.getJSONObject(0).getString("type"))
        assertEquals("read_file", arr.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `anthropic declarations use input_schema`() {
        val arr = NativeToolCalling.anthropicDeclarations(tools)
        assertTrue(arr.getJSONObject(0).has("input_schema"))
        assertTrue(!arr.getJSONObject(0).has("parameters"))
    }

    @Test
    fun `gemini declarations are wrapped`() {
        val obj = NativeToolCalling.geminiDeclarations(tools)
        assertEquals(2, obj.getJSONArray("functionDeclarations").length())
    }

    @Test
    fun `openai response parses into normalized calls`() {
        val msg = JSONObject().put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put("id", "call_1")
                    .put("function", JSONObject().put("name", "read_file")
                        .put("arguments", """{"path":"a.txt"}""")),
            ),
        )
        val c = NativeToolCalling.parseOpenAi(msg).single()
        assertEquals("call_1", c.id)
        assertEquals("a.txt", c.args.getString("path"))
        assertEquals(ToolCall.Source.NATIVE, c.source)
    }

    @Test
    fun `openai malformed arguments are dropped not executed`() {
        val msg = JSONObject().put(
            "tool_calls",
            JSONArray().put(JSONObject().put("function", JSONObject().put("name", "read_file")
                .put("arguments", "{not json"))),
        )
        assertTrue(NativeToolCalling.parseOpenAi(msg).isEmpty())
    }

    @Test
    fun `anthropic content parses tool_use only`() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "hi"))
            .put(JSONObject().put("type", "tool_use").put("id", "t1").put("name", "read_file")
                .put("input", JSONObject().put("path", "b")))
        val c = NativeToolCalling.parseAnthropic(content).single()
        assertEquals("read_file", c.name)
        assertEquals("b", c.args.getString("path"))
    }

    @Test
    fun `gemini parts parse functionCall`() {
        val parts = JSONArray().put(
            JSONObject().put("functionCall", JSONObject().put("name", "read_file")
                .put("args", JSONObject().put("path", "c"))),
        )
        assertEquals("c", NativeToolCalling.parseGemini(parts).single().args.getString("path"))
    }

    @Test
    fun `streamed arguments accumulate into one call`() {
        val d1 = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("id", "s1")
            .put("function", JSONObject().put("name", "read_file").put("arguments", """{"pa"""))))
        val d2 = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("function", JSONObject().put("arguments", """th":"d"}" """))))
        val c = NativeToolCalling.accumulateOpenAiDeltas(listOf(d1, d2), complete = true).single()
        assertEquals("d", c.args.getString("path"))
    }

    @Test
    fun `an incomplete stream yields no call`() {
        val d1 = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("function", JSONObject().put("name", "read_file").put("arguments", """{"pa"""))))
        assertTrue(NativeToolCalling.accumulateOpenAiDeltas(listOf(d1), complete = false).isEmpty())
    }

    @Test
    fun `unverified endpoint falls back to text`() {
        assertEquals(NativeToolCalling.Wire.TEXT, NativeToolCalling.wireFor(setOf("TOOL_CALLS"), verified = false))
    }

    @Test
    fun `custom endpoint stays on text even when verified`() {
        assertEquals(
            NativeToolCalling.Wire.TEXT,
            NativeToolCalling.wireFor(setOf("TOOL_CALLS"), verified = true, custom = true),
        )
    }

    @Test
    fun `verified openai-compatible endpoint uses native`() {
        assertEquals(NativeToolCalling.Wire.OPENAI, NativeToolCalling.wireFor(setOf("TOOL_CALLS"), verified = true))
    }

    @Test
    fun `a verified endpoint without tool capability stays on text`() {
        assertEquals(NativeToolCalling.Wire.TEXT, NativeToolCalling.wireFor(setOf("STREAMING"), verified = true))
    }

    @Test
    fun `both protocols produce the same normalized shape`() {
        val textCall = when (val r = ToolCallScan.scan("""<tool>{"name":"read_file","arguments":{"path":"x"}}</tool>""")) {
            is ScanOutcome.Calls -> r.calls.single()
            else -> error("text parse failed")
        }
        val nativeCall = NativeToolCalling.parseOpenAi(
            JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "c1")
                .put("function", JSONObject().put("name", "read_file")
                    .put("arguments", """{"path":"x"}""")))),
        ).single()
        assertEquals(textCall.name, nativeCall.name)
        assertEquals(textCall.args.toString(), nativeCall.args.toString())
    }
}

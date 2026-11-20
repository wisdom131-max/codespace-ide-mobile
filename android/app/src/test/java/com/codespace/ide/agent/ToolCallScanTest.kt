package com.codespace.ide.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 1 — the scanner must survive everything a real model emits, and must never turn a broken
 * call into an executable one.
 */
class ToolCallScanTest {

    private fun calls(text: String) = when (val r = ToolCallScan.scan(text)) {
        is ScanOutcome.Calls -> r.calls
        is ScanOutcome.Failure -> emptyList()
    }

    @Test
    fun `nested objects parse whole`() {
        val t = """<tool>{"name":"write_file","arguments":{"path":"a.txt","meta":{"deep":{"x":1}}}}</tool>"""
        val c = calls(t).single()
        assertEquals("write_file", c.name)
        assertEquals(1, c.args.getJSONObject("meta").getJSONObject("deep").getInt("x"))
    }

    @Test
    fun `nested arrays parse whole`() {
        val t = """<tool>{"name":"multi","arguments":{"edits":[{"a":1},{"b":[2,3]}]}}</tool>"""
        assertEquals(2, calls(t).single().args.getJSONArray("edits").length())
    }

    @Test
    fun `a brace inside a string is data`() {
        val t = """<tool>{"name":"write_file","arguments":{"text":"if (x) { y }"}}</tool>"""
        assertEquals("if (x) { y }", calls(t).single().args.getString("text"))
    }

    @Test
    fun `escaped quotes survive`() {
        val t = """<tool>{"name":"write_file","arguments":{"text":"say \"hi\""}}</tool>"""
        assertEquals("say \"hi\"", calls(t).single().args.getString("text"))
    }

    @Test
    fun `escaped backslashes survive`() {
        val t = """<tool>{"name":"write_file","arguments":{"p":"C:\\tmp\\x"}}</tool>"""
        assertEquals("C:\\tmp\\x", calls(t).single().args.getString("p"))
    }

    @Test
    fun `unicode survives`() {
        val t = """<tool>{"name":"save_memory","arguments":{"value":"café ☕ 日本語"}}</tool>"""
        assertEquals("café ☕ 日本語", calls(t).single().args.getString("value"))
    }

    @Test
    fun `multiple calls in one response`() {
        val t = """<tool>{"name":"a","arguments":{}}</tool> then <tool>{"name":"b","arguments":{"x":1}}</tool>"""
        val c = calls(t)
        assertEquals(2, c.size)
        assertEquals(listOf("a", "b"), c.map { it.name })
    }

    @Test
    fun `an unterminated call is a failure, not a call`() {
        val r = ToolCallScan.scan("""<tool>{"name":"write_file","arguments":{"p":"x"""")
        assertTrue(r is ScanOutcome.Failure)
        assertEquals(ScanOutcome.Failure.Reason.MALFORMED_PROTOCOL, (r as ScanOutcome.Failure).reason)
    }

    @Test
    fun `malformed json is a typed failure`() {
        val r = ToolCallScan.scan("""<tool>{"name":"x","arguments":{oops}}</tool>""")
        assertTrue(r is ScanOutcome.Failure)
        assertEquals(ScanOutcome.Failure.Reason.INVALID_JSON, (r as ScanOutcome.Failure).reason)
    }

    @Test
    fun `a call with no name is a typed failure`() {
        val r = ToolCallScan.scan("""<tool>{"arguments":{"p":"x"}}</tool>""")
        assertTrue(r is ScanOutcome.Failure)
        assertEquals(ScanOutcome.Failure.Reason.MISSING_NAME, (r as ScanOutcome.Failure).reason)
    }

    @Test
    fun `empty arguments are allowed`() {
        val c = calls("""<tool>{"name":"git_status","arguments":{}}</tool>""").single()
        assertEquals(0, c.args.length())
    }

    @Test
    fun `hermes dialect produces the same normalized call`() {
        val t = """<tool_call>
<function=read_file>
<parameter=path>
a/b.txt
</parameter>
</function>
</tool_call>"""
        val c = calls(t).single()
        assertEquals("read_file", c.name)
        assertEquals("a/b.txt", c.args.getString("path").trim())
        assertEquals(ToolCall.Source.HERMES_XML, c.source)
    }

    @Test
    fun `both dialects can appear in one response`() {
        val t = """<tool>{"name":"a","arguments":{}}</tool>
<tool_call>
<function=b>
<parameter=x>1</parameter>
</function>
</tool_call>"""
        assertEquals(listOf("a", "b"), calls(t).map { it.name })
    }

    @Test
    fun `ordinary text around calls is preserved exactly`() {
        val t = """Before the call.
<tool>{"name":"a","arguments":{}}</tool>
After the call."""
        val r = ToolCallScan.scan(t) as ScanOutcome.Calls
        assertTrue(r.visible.contains("Before the call."))
        assertTrue(r.visible.contains("After the call."))
        assertTrue(!r.visible.contains("<tool>"))
    }

    @Test
    fun `call ids are stable within a response`() {
        val t = """<tool>{"name":"a","arguments":{}}</tool><tool>{"name":"b","arguments":{}}</tool>"""
        assertEquals(listOf("t0", "t1"), calls(t).map { it.id })
    }

    @Test
    fun `unknown tool names still parse so the executor can refuse them`() {
        val c = calls("""<tool>{"name":"not_a_real_tool","arguments":{}}</tool>""").single()
        assertEquals("not_a_real_tool", c.name)
    }

    @Test
    fun `malformed input never yields an error tool`() {
        val names = AgentTools.parseToolCalls("""<tool>{"name": broken</tool>""").map { it.first }
        assertTrue("no fake error tool", names.none { it == "error" })
        assertTrue(names.isEmpty())
    }

    @Test
    fun `text with no protocol has no calls and is unchanged`() {
        val t = "Just a normal answer with a { brace } in it."
        assertTrue(calls(t).isEmpty())
        assertEquals(t, ToolCallScan.visible(t))
    }

    @Test
    fun `unterminated object scan returns -1`() {
        assertEquals(-1, ToolCallScan.jsonObjectEnd("""{"a":1""", 0))
        assertNull(AgentTools.parseFailure("plain text"))
    }

    @Test
    fun `arguments keep their types`() {
        val c = calls("""<tool>{"name":"x","arguments":{"n":5,"b":true,"s":"t"}}</tool>""").single()
        assertEquals(5, c.args.getInt("n"))
        assertTrue(c.args.getBoolean("b"))
        assertEquals("t", c.args.getString("s"))
        assertEquals(JSONObject(c.args.toString()).getInt("n"), 5)
    }
}

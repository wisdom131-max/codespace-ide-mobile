package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE 2 — protocol must never reach the visible reply, in any chunking. */
class ProtocolStreamFilterTest {

    private fun feed(chunks: List<String>): Pair<String, ProtocolStreamFilter> {
        val f = ProtocolStreamFilter()
        val sb = StringBuilder()
        chunks.forEach { sb.append(f.accept(it)) }
        sb.append(f.flush())
        return sb.toString() to f
    }

    @Test
    fun `marker split across two chunks never shows`() {
        val (shown, _) = feed(listOf("Here. <to", """ol>{"name":"a","arguments":{}}</tool>"""))
        assertEquals("Here. ", shown)
    }

    @Test
    fun `marker split across several chunks never shows`() {
        val (shown, _) = feed(listOf("<", "to", "ol", ">", """{"name":"a","arguments":{}}""", "</tool>"))
        assertEquals("", shown)
    }

    @Test
    fun `partial arguments never show`() {
        val (shown, f) = feed(listOf("""<tool>{"name":"write_file","argum"""))
        assertEquals("", shown)
        assertTrue(f.completedCalls().isEmpty())
    }

    @Test
    fun `nested json is not exposed`() {
        val (shown, f) = feed(listOf("""<tool>{"name":"w","arguments":{"a":{"b":"}"}}}</tool>"""))
        assertEquals("", shown)
        assertEquals(1, f.completedCalls().size)
    }

    @Test
    fun `text before a call survives`() {
        val (shown, _) = feed(listOf("Reading now. ", """<tool>{"name":"a","arguments":{}}</tool>"""))
        assertEquals("Reading now. ", shown)
    }

    @Test
    fun `text after a call survives`() {
        val (shown, _) = feed(listOf("""<tool>{"name":"a","arguments":{}}</tool>""", " Done."))
        assertEquals(" Done.", shown)
    }

    @Test
    fun `multiple calls each stay hidden`() {
        val (shown, f) = feed(listOf("""<tool>{"name":"a","arguments":{}}</tool><tool>{"name":"b","arguments":{}}</tool>"""))
        assertEquals("", shown)
        assertEquals(listOf("a", "b"), f.completedCalls().map { it.name })
    }

    @Test
    fun `an unfinished call at turn end is dropped, not rendered`() {
        val (shown, f) = feed(listOf("""<tool>{"name":"a","arguments":{"x":1"""))
        assertEquals("", shown)
        assertTrue(f.completedCalls().isEmpty())
    }

    @Test
    fun `a malformed call is dropped silently`() {
        val (shown, f) = feed(listOf("""<tool>{oops}</tool>"""))
        assertEquals("", shown)
        assertTrue(f.completedCalls().isEmpty())
    }

    @Test
    fun `provider interruption mid-call leaves no text`() {
        val (shown, _) = feed(listOf("Working", """ <tool>{"name":"a","argu"""))
        assertEquals("Working", shown.trimEnd())
    }

    @Test
    fun `retry with a repeated fragment does not duplicate the reply`() {
        val f = ProtocolStreamFilter()
        val a = f.accept("Same answer.")
        val b = f.accept(" Same answer.")
        assertEquals("Same answer.", a)
        assertEquals(" Same answer.", b)
    }

    @Test
    fun `hermes streaming is hidden too`() {
        val (shown, f) = feed(listOf("<tool_call>\n<function=read_file>\n", "<parameter=path>\na.txt\n</parameter>\n</function>\n</tool_call>"))
        assertEquals("", shown)
        assertEquals("read_file", f.completedCalls().single().name)
    }

    @Test
    fun `ordinary text containing a brace is shown unchanged`() {
        val (shown, _) = feed(listOf("Use { braces } in code."))
        assertEquals("Use { braces } in code.", shown)
    }

    @Test
    fun `a lone angle bracket is not swallowed`() {
        val (shown, _) = feed(listOf("Compare a < b and c > d."))
        assertEquals("Compare a < b and c > d.", shown)
    }
}

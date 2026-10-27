package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract tests for the incremental SSE parser (boundary chunks, CRLF, DONE). */
class SseParserTest {

    @Test fun `whole stream in one chunk`() {
        val p = SseParser()
        val out = p.feed("data: {\"d\":\"hi\"}\n\ndata: [DONE]\n\n")
        assertEquals(listOf("""{"d":"hi"}""", "[DONE]"), out)
    }

    @Test fun `delta split mid-line across chunks`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("data: {\"d\":\"he"))
        assertEquals(emptyList<String>(), p.feed("llo\"}"))
        assertEquals(listOf("""{"d":"hello"}"""), p.feed("\n\n"))
    }

    @Test fun `crlf line endings`() {
        val p = SseParser()
        val out = p.feed("data: one\r\n\r\ndata: [DONE]\r\n\r\n")
        assertEquals(listOf("one", "[DONE]"), out)
    }

    @Test fun `comments and fields ignored`() {
        val p = SseParser()
        val out = p.feed(": keepalive\nid: 7\nevent: msg\ndata: x\n\n")
        assertEquals(listOf("x"), out)
    }

    @Test fun `multi-line data joined with newline`() {
        val p = SseParser()
        val out = p.feed("data: a\ndata: b\n\n")
        assertEquals(listOf("a\nb"), out)
    }

    @Test fun `prefix colon inside payload kept`() {
        val p = SseParser()
        val out = p.feed("data: {\"json\":\"with:colon\"}\n\n")
        assertEquals(listOf("""{"json":"with:colon"}"""), out)
    }

    @Test fun `finish flushes trailing event without blank line`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("data: tail"))
        assertEquals(listOf("tail"), p.finish())
    }

    @Test fun `keepalive comments never emit`() {
        val p = SseParser()
        val out = p.feed(": ping\n: ping\n")
        assertEquals(emptyList<String>(), out)
    }
}

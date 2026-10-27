package com.codespace.ide.engine

/**
 * Incremental Server-Sent-Events parser (rebuild from scratch; the archived
 * chat's parser left with the removal series). Feed arbitrary chunks — the
 * parser buffers partial lines across chunk boundaries and emits one item per
 * completed `data:` event. Comments (`:` lines), `id:`, `event:`, `retry:`
 * fields are ignored per the SSE spec. Multi-line data (several data: lines
 * in one event) is joined with \n. CRLF is normalized to LF.
 *
 * Output is data payloads only; the engine layer decides what a payload means
 * (delta JSON, [DONE] sentinel, keepalive comments already filtered here).
 */
class SseParser {

    private val buffer = StringBuilder()
    private val pendingData = ArrayList<String>()

    /**
     * Feed one chunk. Returns the data payloads of all events completed by
     * this chunk. Never throws on malformed input: unknown lines are ignored
     * (honest degradation, same ruling as the archived engine).
     */
    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val out = ArrayList<String>()
        while (true) {
            val nl = buffer.indexOf('\n')
            if (nl < 0) break
            val rawLine = buffer.substring(0, nl)
            buffer.delete(0, nl + 1)
            val line = rawLine.trimEnd('\r')
            when {
                line.isEmpty() -> {
                    // Event boundary: flush accumulated data lines, if any.
                    if (pendingData.isNotEmpty()) {
                        out.add(pendingData.joinToString("\n"))
                        pendingData.clear()
                    }
                }
                line.startsWith(":") -> { /* comment/keepalive */ }
                line.startsWith("data:") -> {
                    pendingData.add(line.removePrefix("data:").removePrefix(" "))
                }
                else -> { /* id:/event:/retry: and unknown fields ignored */ }
            }
        }
        return out
    }

    /** End of stream: flush a trailing event not closed by a blank line. */
    fun finish(): List<String> {
        val rest = buffer.toString()
        buffer.setLength(0)
        if (rest.isNotEmpty()) {
            val line = rest.trimEnd('\r')
            if (line.startsWith("data:")) {
                pendingData.add(line.removePrefix("data:").removePrefix(" "))
            }
        }
        val out = if (pendingData.isEmpty()) emptyList()
                  else listOf(pendingData.joinToString("\n")).also { pendingData.clear() }
        return out
    }
}

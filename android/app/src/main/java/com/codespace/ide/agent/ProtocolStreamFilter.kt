package com.codespace.ide.agent

/**
 * STAGE 2 (2026-11-20) — nothing that might become a tool call is ever shown as assistant text.
 *
 * Deltas arrive in arbitrary pieces, so a marker can be split across chunks ("<to" + "ol>"). The
 * filter holds any suffix that could still become a marker, and holds the whole protocol region
 * until it closes. Only text that cannot be protocol is released immediately.
 */
class ProtocolStreamFilter {

    private val buffer = StringBuilder()
    private var holding = false
    private val completed = ArrayList<ToolCall>()

    private val openers = listOf("<tool>", "<tool_call>")
    private val closes = mapOf("<tool>" to "</tool>", "<tool_call>" to "</tool_call>")

    /** Text safe to display right now. Never a fragment of a call. */
    fun accept(delta: String): String {
        if (delta.isEmpty()) return ""
        buffer.append(delta)
        val out = StringBuilder()
        var progress = true
        while (progress) {
            progress = false
            if (!holding) {
                val openAt = openers.map { buffer.indexOf(it) }.filter { it >= 0 }.minOrNull()
                if (openAt != null) {
                    out.append(buffer, 0, openAt)
                    buffer.delete(0, openAt)
                    holding = true
                    progress = true
                    continue
                }
                // Hold a tail that is a prefix of an opener: it may become one in the next chunk.
                val keep = partialOpenerLength()
                if (keep > 0) {
                    out.append(buffer, 0, buffer.length - keep)
                    buffer.delete(0, buffer.length - keep)
                } else {
                    out.append(buffer)
                    buffer.setLength(0)
                }
            } else {
                val opener = openers.firstOrNull { buffer.startsWith(it) } ?: break
                val closeTag = closes.getValue(opener)
                val closeAt = buffer.indexOf(closeTag)
                if (closeAt < 0) break
                val end = closeAt + closeTag.length
                val region = buffer.substring(0, end)
                when (val r = ToolCallScan.scan(region)) {
                    is ScanOutcome.Calls -> completed.addAll(r.calls)
                    is ScanOutcome.Failure -> Unit // a broken call is dropped, never rendered
                }
                buffer.delete(0, end)
                holding = false
                progress = true
            }
        }
        return out.toString()
    }

    /** Turn end: release ordinary text, discard an unfinished call, and report what completed. */
    fun flush(): String {
        val tail = if (holding) "" else buffer.toString()
        buffer.setLength(0)
        holding = false
        return tail
    }

    fun completedCalls(): List<ToolCall> = completed.toList()

    private fun partialOpenerLength(): Int {
        var best = 0
        for (opener in openers) {
            for (n in 1 until opener.length) {
                if (buffer.length >= n && buffer.regionMatches(buffer.length - n, opener, 0, n)) {
                    if (n > best) best = n
                }
            }
        }
        return best
    }
}

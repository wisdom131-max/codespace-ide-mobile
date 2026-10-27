package com.codespace.ide.spike

/**
 * Fixed streaming corpus for the benchmark — SAME bytes for both panes.
 * Contains markdown headings, inline code, fenced code blocks, a long-ish
 * paragraph (fling), and a rate-limit sample so the renderer also handles
 * error styling during streaming. Deltas are emitted at 30/s.
 */
object SpikeCorpus {

    /** Message 1: markdown + inline code + fenced code (streams first). */
    const val MSG1 = """### Editor loop tip
Use `SelectionContainer` around the stream so **selection and copy** work mid-delta.

```kotlin
fun delta(chunk: String) {
    append(chunk)          // 30 deltas per second
    frameStats.sample()    // Choreographer-backed
}
```

The list is a LazyColumn with `key = message.id + deltaIndex` so recomposition stays bounded to the visible row. A long paragraph follows to force layout during streaming: `payload budget estimation, retry-after handling, and the history-budget Compact/Balanced/Full cutoff all live in the engine, never in composables.`"""

    /** Message 2: error/rate-limit style sample (429 wait card). */
    const val MSG2 = """### Rate limited (429)
The provider asked to wait. Retry-After: **30s**. The engine surfaces a plain wait card; the panel never re-sends silently.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
```

Small payloads may retry automatically; large ones always ask first."""

    /** Message 3: fling content — many short lines. */
    const val MSG3 = (1..24).joinToString("\n") { "Line $it — fling me to the bottom and back; both panes must stay smooth." }

    /** Delta chunks for a message: ~4 chars per delta at 30/s ≈ streaming feel. */
    fun deltas(msg: String, chunk: Int = 4): List<String> =
        msg.chunked(chunk)

    const val DELAY_MS = 33L
}

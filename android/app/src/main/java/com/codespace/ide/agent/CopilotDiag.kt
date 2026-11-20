package com.codespace.ide.agent

/**
 * P4 (2026-11-20) — observability lite.
 *
 * A bounded, in-memory ring of structured diagnostic events. No network, no telemetry, no file
 * writes on the hot path: 200 events is enough to answer "which tool ran, did it fail, was its
 * output offloaded" and small enough to cost nothing on a phone. Events die with the process, and
 * that is stated rather than hidden.
 *
 * Every string that enters an event passes [redact] first. Raw prompts and complete tool outputs
 * are never recorded - only names, states, counts and sizes.
 */
object CopilotDiag {

    enum class Kind {
        SESSION, TOOL_START, TOOL_END, APPROVAL, OFFLOAD, RETRIEVAL, PROVIDER_ERROR, CONTINUATION,
    }

    /** Bounded so a long session cannot grow memory without limit. */
    const val MAX_EVENTS = 200

    data class Event(
        val at: Long,
        val kind: Kind,
        val name: String,
        val ok: Boolean,
        val detail: String,
        val session: String,
    )

    private val ring = ArrayDeque<Event>()
    private var session = ""

    /** A session id correlates a turn's events without identifying a person. */
    fun startSession(id: String) {
        session = redact(id).take(40)
        ring.clear()
        record(Kind.SESSION, "start", true, "session opened")
    }

    fun record(kind: Kind, name: String, ok: Boolean, detail: String = "") {
        synchronized(ring) {
            if (ring.size >= MAX_EVENTS) ring.removeFirst()
            ring.addLast(
                Event(
                    at = System.currentTimeMillis(),
                    kind = kind,
                    name = redact(name).take(80),
                    ok = ok,
                    detail = redact(detail).take(200),
                    session = session,
                ),
            )
        }
    }

    /**
     * Runs one instrumented operation and records its real outcome. The result string passes
     * through untouched; a diagnostic failure can never turn a success into a failure, and an
     * exception is recorded then rethrown so execution behaviour is unchanged.
     */
    suspend fun trace(name: String, block: suspend () -> String): String {
        val started = System.currentTimeMillis()
        runCatching { record(Kind.TOOL_START, name, true) }
        return try {
            val out = block()
            val ok = !out.startsWith("FAILED")
            runCatching {
                record(
                    Kind.TOOL_END, name, ok,
                    (System.currentTimeMillis() - started).toString() + "ms, " +
                        out.length + " chars" + (if (ok) "" else ", failure"),
                )
            }
            out
        } catch (t: Throwable) {
            runCatching { record(Kind.TOOL_END, name, false, "threw: " + (t.message ?: "unknown")) }
            throw t
        }
    }

    fun recent(limit: Int = 50): List<Event> = synchronized(ring) { ring.toList().takeLast(limit) }

    fun clear() = synchronized(ring) { ring.clear() }

    /** Counts per kind, for a compact summary row. */
    fun counts(): Map<Kind, Int> = synchronized(ring) { ring.groupingBy { it.kind }.eachCount() }

    /**
     * Strips the shapes that must never be logged: bearer headers, provider keys, and
     * key=value pairs whose key looks like a credential.
     */
    fun redact(s: String): String {
        var out = s
        out = out.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._-]{8,}"), "Bearer [redacted]")
        out = out.replace(Regex("(?i)(sk|pk|ghp|gho|glpat|xoxb|xoxp)[-_][A-Za-z0-9_-]{8,}"), "[redacted-key]")
        out = out.replace(
            Regex("(?i)(api[_-]?key|token|secret|password|authorization)\\s*[=:]\\s*\\S+"),
            "$1=[redacted]",
        )
        return out
    }
}

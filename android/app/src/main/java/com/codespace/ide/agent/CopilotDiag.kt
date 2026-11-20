package com.codespace.ide.agent

import java.io.File

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
        // P4 completion: real transitions, not placeholders. Each is emitted where the decision is
        // actually made, so an event never claims a transition that did not happen.
        APPROVAL_REQUIRED, APPROVAL_GRANTED, APPROVAL_DENIED, APPROVAL_CANCELLED,
        CONTINUATION_STARTED, CONTINUATION_COMPLETED, CONTINUATION_FAILED, CONTINUATION_SKIPPED,
        PROVIDER_REQUEST_START, PROVIDER_REQUEST_SUCCESS, PROVIDER_REQUEST_FAILURE,
        PROVIDER_REQUEST_CANCELLED,
    }

    /** Bounded so a long session cannot grow memory without limit. */
    const val MAX_EVENTS = 500

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
        persisted = 0
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
        if (++sinceSave >= 5) {
            sinceSave = 0
            runCatching { sink?.let { persistTo(it) } }
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

    fun clear() {
        synchronized(ring) { ring.clear() }
        persisted = 0
        sinceSave = 0
    }

    /** Stops writing to the attached file. Events already on disk are untouched. */
    fun detach() {
        sink = null
        sinceSave = 0
    }

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

    // ---- persistence (P4.4) -------------------------------------------------------------
    //
    // One append-only JSONL file. Chosen over a database because the whole payload is a few
    // hundred short lines: a single file needs no schema, no migration and no dependency, and a
    // torn final line is simply skipped on read.
    //
    // Retention: at most MAX_PERSISTED events, nothing older than KEEP_DAYS. Both are enforced on
    // load, so a file that grew while the app was open is trimmed the next time it is read.

    const val MAX_PERSISTED = 500
    const val KEEP_DAYS = 3

    /**
     * P4.4 wiring: the file the app attached, plus a counter so the disk is touched once every
     * few events rather than on every one. Null means memory-only, which is the honest state in
     * JVM tests and before the Application has started.
     */
    @Volatile private var sink: File? = null
    @Volatile private var sinceSave = 0

    /** Points diagnostics at a file and restores whatever survived the last process. */
    fun attach(file: File): Int {
        sink = file
        return loadFrom(file)
    }

    fun sinkFile(): File? = sink

    /** Writes anything pending now. Safe at any time; never throws. */
    fun flush(): Boolean = sink?.let { persistTo(it) } ?: false

    /** How many events have already reached the file, so a save never rewrites what it wrote. */
    @Volatile private var persisted = 0

    private fun json(e: Event): String =
        "{\"at\":" + e.at + ",\"kind\":\"" + e.kind.name + "\",\"name\":\"" +
            e.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"ok\":" + e.ok +
            ",\"detail\":\"" + e.detail.replace("\\", "\\\\").replace("\"", "\\\"") +
            "\",\"session\":\"" + e.session.replace("\"", "\\\"") + "\"}"

    /** Appends events not yet written. Returns false on any failure; never throws. */
    fun persistTo(file: File): Boolean = try {
        val snapshot = synchronized(ring) { ring.toList() }
        val from = persisted.coerceAtMost(snapshot.size)
        if (from >= snapshot.size) {
            true
        } else {
            file.parentFile?.mkdirs()
            file.appendText(snapshot.drop(from).joinToString("") { json(it) + "\n" })
            persisted = snapshot.size
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Loads the newest MAX_PERSISTED events, dropping anything past KEEP_DAYS and any line that
     * does not parse. A corrupt file yields what it can and never throws.
     */
    fun loadFrom(file: File, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - KEEP_DAYS * 24L * 60L * 60L * 1000L
        val parsed = ArrayList<Event>()
        try {
            if (!file.isFile) return 0
            file.forEachLine { line: String ->
                val e = parse(line) ?: return@forEachLine
                if (e.at >= cutoff) parsed.add(e)
            }
        } catch (_: Throwable) {
            // A read failure leaves whatever parsed so far, which is the honest partial answer.
        }
        val kept = parsed.takeLast(MAX_PERSISTED)
        synchronized(ring) {
            ring.clear()
            kept.forEach { ring.addLast(it) }
        }
        persisted = kept.size
        // Rewrite when trimming happened, so the file does not grow without limit.
        if (kept.size != parsed.size) runCatching {
            file.writeText(kept.joinToString("") { json(it) + "\n" })
        }
        return kept.size
    }

    private fun parse(line: String): Event? {
        val t = line.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        fun str(key: String): String? {
            val i = t.indexOf("\"" + key + "\":\"")
            if (i < 0) return null
            val start = i + key.length + 4
            val end = t.indexOf('"', start)
            return if (end < 0) null else t.substring(start, end)
        }
        fun num(key: String): Long? {
            val i = t.indexOf("\"" + key + "\":")
            if (i < 0) return null
            val start = i + key.length + 3
            val end = t.indexOf(',', start).let { if (it < 0) t.length - 1 else it }
            return t.substring(start, end).trim().toLongOrNull()
        }
        return try {
            val kind = Kind.entries.firstOrNull { it.name == str("kind") } ?: return null
            val at = num("at") ?: return null
            Event(
                at = at,
                kind = kind,
                name = str("name") ?: "",
                ok = t.contains("\"ok\":true"),
                detail = str("detail") ?: "",
                session = str("session") ?: "",
            )
        } catch (_: Throwable) {
            null
        }
    }

}

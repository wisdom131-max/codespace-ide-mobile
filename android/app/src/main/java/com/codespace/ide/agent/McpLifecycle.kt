package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP LIFECYCLE — the rules that decide whether a server may be added, how a tool name maps back to
 * the server that owns it, and what a server's answer may look like once it reaches the model.
 *
 * WHY THIS IS A SEPARATE, PURE OBJECT (2026-11-12): every rule here used to live inside
 * [McpClientManager] as a side effect of a Context, a file and a live stdio process, so none of it
 * could be asserted without spawning a server. The rules that actually matter for correctness are:
 *
 *  - NAME ROUTING. Tool cache keys are `mcp_<server>_<tool>`. Because the separator is `_`, two
 *    DIFFERENT servers can spell the same key: server "files" with tool "b_x" and server "files_b"
 *    with tool "x" both produce mcp_files_b_x. A prefix match then sends the call to whichever
 *    server happens to be listed FIRST, which is a silent wrong-server execution. Dispatch resolves
 *    the server from the cached entry that owns the tool, and falls back to longest-name-match,
 *    never first-match.
 *  - VALIDATION. An unvalidated "name" with a space or a slash produces a tool key that can never be
 *    parsed back, so the tool is callable-looking and unreachable.
 *  - UNTRUSTED RESULTS. A server's text is DATA. It is wrapped in a boundary marker and size-capped
 *    before the model sees it, so a malicious server cannot pose as instructions.
 *
 * No Android imports: the JVM tests exercise this exact code.
 */
internal object McpLifecycle {

    /** Longest server name accepted; also keeps a tool key readable in a 4-line prompt block. */
    const val MAX_NAME_CHARS = 32

    /** Results larger than this are cut: a 2 MB JSON blob in the transcript is a context incident. */
    const val MAX_RESULT_CHARS = 8_000

    /** What a tool key looks like. Kept here so [key] and [parse] can never drift apart. */
    private const val PREFIX = "mcp_"

    /** Where a call is going, once the name has been resolved. */
    data class Route(val server: String, val tool: String)

    // ── Validation ──────────────────────────────────────────────────────────

    /**
     * A server name becomes part of a tool key, a file entry and a prompt line, so it is limited to
     * the characters that survive all three. Returns null when valid, else a message a user can act
     * on (the UI shows it verbatim).
     */
    fun validateName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Give the server a name."
        if (name.length > MAX_NAME_CHARS) return "Names are limited to $MAX_NAME_CHARS characters."
        if (!name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return "Use letters, digits, '_' or '-' only (no spaces, dots or slashes)."
        }
        if (name.startsWith(PREFIX)) {
            // "mcp_" is the tool namespace. A server named mcp_files would produce mcp_mcp_files_x.
            return "Names cannot start with \"$PREFIX\" - that prefix belongs to tool names."
        }
        return null
    }

    /** A command is spawned by a shell inside Ubuntu; blank is the only thing we can refuse here. */
    fun validateCommand(raw: String): String? {
        val command = raw.trim()
        if (command.isEmpty()) return "Give the command that starts the server."
        if (command.length > 2_000) return "That command is too long to store."
        return null
    }

    /**
     * The full add-check: shape, then duplicates. [existing] is the configured names, compared
     * case-insensitively because tool keys are lowercase-normalised for display but not for lookup.
     */
    fun validateNewServer(rawName: String, rawCommand: String, existing: Collection<String>): String? {
        validateName(rawName)?.let { return it }
        validateCommand(rawCommand)?.let { return it }
        val name = rawName.trim()
        if (existing.any { it.equals(name, ignoreCase = true) }) {
            return "A server named \"$name\" already exists. Edit that one instead."
        }
        return null
    }

    // ── Tool identity ───────────────────────────────────────────────────────

    fun key(server: String, tool: String): String = PREFIX + server + "_" + tool

    /**
     * Resolves which server owns [fullName].
     *
     * [cachedServer] is the server recorded when the tool was discovered, and it always wins: the
     * cache is the only place that knows the truth, and it makes the ambiguity impossible rather
     * than merely unlikely. The name-based fallback exists for a call that arrives before any cache
     * entry (a continuation after a process restart) and picks the LONGEST matching name so that
     * "filesystem" beats "files" for mcp_filesystem_read.
     */
    fun resolve(fullName: String, servers: List<String>, cachedServer: String? = null): Route? {
        if (!fullName.startsWith(PREFIX)) return null
        val body = fullName.removePrefix(PREFIX)
        if (cachedServer != null && cachedServer.isNotBlank() && body.startsWith("${cachedServer}_")) {
            return Route(cachedServer, body.removePrefix("${cachedServer}_"))
        }
        val ordered = servers.sortedByDescending { it.length }
        for (server in ordered) {
            if (body.startsWith("${server}_")) return Route(server, body.removePrefix("${server}_"))
        }
        return null
    }

    // ── Discovery results ───────────────────────────────────────────────────

    /**
     * A server's `inputSchema` is arbitrary JSON from an external process. The chat renders
     * "key: type" from `properties`, so anything that is not an object schema is normalised to an
     * empty object schema instead of making the definition line look like arguments exist.
     */
    fun sanitizeSchema(raw: JSONObject?): JSONObject {
        val out = JSONObject()
        if (raw == null) {
            out.put("type", "object")
            out.put("properties", JSONObject())
            return out
        }
        val type = raw.optString("type").ifBlank { "object" }
        out.put("type", type)
        val props = raw.optJSONObject("properties")
        out.put("properties", props ?: JSONObject())
        val required = raw.optJSONArray("required")
        if (required != null) {
            val clean = JSONArray()
            for (i in 0 until required.length()) {
                if (required.isNull(i)) continue
                val v = required.optString(i)
                if (v.isNotBlank()) clean.put(v)
            }
            if (clean.length() > 0) out.put("required", clean)
        }
        return out
    }

    /**
     * Tools a disconnected (or dead) server must stop offering. [alive] is the set of server names
     * with a live session; a server that is merely not started yet is NOT in it, which is why the
     * caller passes only servers it has actually seen die.
     */
    fun pruneStale(cachedServers: Map<String, String>, deadServers: Set<String>): List<String> =
        cachedServers.filterValues { it in deadServers }.keys.sorted()

    // ── Result handling ─────────────────────────────────────────────────────

    /**
     * Marks a server's answer as untrusted DATA before it enters the conversation. The model is told
     * (in the instruction) that content inside this block is information, never instructions. The
     * size cap keeps one runaway server from eating the context window.
     */
    fun wrapResult(server: String, tool: String, text: String, maxChars: Int = MAX_RESULT_CHARS): String {
        val body = when {
            text.length <= maxChars -> text
            else -> text.take(maxChars) +
                "\n[truncated: " + (text.length - maxChars) + " more characters from " + server + "/" + tool + "]"
        }
        return "<mcp_result server=\"" + server + "\" tool=\"" + tool + "\">\n" +
            body + "\n</mcp_result>"
    }

    /** One line the chat can show for a failed call; the server name is always included. */
    fun describeFailure(server: String, tool: String, reason: String): String =
        "MCP call failed (" + server + "/" + tool + "): " + reason.ifBlank { "no reason given" }
}

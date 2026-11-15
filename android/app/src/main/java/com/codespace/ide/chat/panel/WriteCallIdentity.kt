package com.codespace.ide.chat.panel

import java.security.MessageDigest

/**
 * GATE 2 (2026-11-15): a write call carries an id derived from WHAT IT ASKS FOR.
 *
 * The ledger, [RetryDecision] and [ToolDispatchGuard] were built for exactly-once dispatch and are
 * persisted on disk, but the chat path called `executeTool(name, args, ctx)` with no id, so every
 * dispatch minted a fresh UUID and the machinery never saw a repeat. An id nobody supplies is a
 * system nobody uses.
 *
 * Only WRITE tools get a derived id, and the id is content-addressed: the same tool asking for the
 * same arguments is the same intent. Re-applying identical content is the duplicate side effect
 * this protects against, and skipping it is safe - the file on disk either already holds that
 * content, or the user changed it after the first write, in which case not overwriting their edit
 * is the better outcome. The model is told the call was reused, so it can read the file and see.
 *
 * Commands, git, connectors and every other side effect keep a fresh id on purpose: the same
 * `run_command` or `git_commit` twice can be a real second intent, and those paths already ask at
 * the approval gate. A content-addressed id there would suppress work the user asked for.
 */
internal object WriteCallIdentity {

    /** The file writers. Same set as [com.codespace.ide.agent.WriteBoundary], by design. */
    private val writeTools: Set<String> = setOf(
        "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
        "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path", "docx_create", "docx_edit",
    )

    /**
     * Stable id for a write call, or null when this tool must keep a fresh id.
     *
     * Key order and whitespace in the JSON are not the model's intent, so both are normalised
     * before hashing: `{"a":1,"b":2}` and `{ "b": 2, "a": 1 }` are the same call.
     */
    fun idFor(toolName: String, argumentsJson: String): String? {
        if (toolName !in writeTools) return null
        val canonical = canonicalise(argumentsJson)
        return toolName + ":" + digest(canonical)
    }

    /** Sorted-key JSON-ish text with no insignificant whitespace. Never throws. */
    fun canonicalise(argumentsJson: String): String = try {
        val o = org.json.JSONObject(argumentsJson)
        val keys = o.keys().asSequence().toList().sorted()
        keys.joinToString(separator = "\u0001", prefix = "{", postfix = "}") { k ->
            k + "\u0002" + o.opt(k).toString()
        }
    } catch (_: Throwable) {
        // Unparseable arguments are refused earlier by the truncation guard; a raw string still
        // gives a stable id rather than a random one.
        argumentsJson.trim()
    }

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) sb.append(String.format("%02x", bytes[i]))
        return sb.toString()
    }
}

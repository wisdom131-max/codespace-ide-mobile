package com.codespace.ide.agent

import java.security.MessageDigest

/**
 * AUDIT 2026-11-12 - the ledger kept whole argument values.
 *
 * `recordStarted` stored `args` verbatim. A `write_file` argument is the entire file, so one entry
 * could hold a megabyte, the persisted ledger file grew with it, and 200 of those is a real memory
 * cost on a phone for the sake of a value used in exactly one comparison: "does the file already
 * hold what was asked for?"
 *
 * A long value is fingerprinted instead of kept. It is enough for that comparison and it cannot
 * grow. Short values (paths, ids, commands, small bodies) stay readable, because a human reads them
 * when a call is refused.
 */
object ArgFingerprint {

    /** Values at or under this length are kept whole. */
    const val KEEP = 2_000

    /** A refusal message can only show so much; this bounds the map itself. */
    const val MAX_KEYS = 12

    private const val PREFIX = "sha256:"

    fun stamp(args: Map<String, String>): Map<String, String> {
        if (args.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in args) {
            if (out.size >= MAX_KEYS) break
            out[k] = if (v.length > KEEP) PREFIX + sha256(v) else v
        }
        return out
    }

    /**
     * Does [actual] equal what [stored] captured? A fingerprinted value is compared by hash, a short
     * one exactly. A missing stored value never matches: absence is not evidence.
     */
    fun matches(stored: String?, actual: String?): Boolean {
        if (stored == null || actual == null) return false
        return if (stored.startsWith(PREFIX)) {
            val want = stored.removePrefix(PREFIX)
            want.equals(sha256(actual), ignoreCase = true)
        } else {
            stored == actual
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}

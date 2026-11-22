package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.18 (catalog 60) - a malformed call should cost one call, not the whole reply.
 *
 * Today a tool call whose JSON never closes discards everything the model said in that turn, so
 * one stray comma deletes a paragraph of real work. This repairs only the faults that have exactly
 * one sensible reading, and refuses everything else.
 *
 * It never guesses a VALUE. A missing quote around a number, a trailing comma, or a missing final
 * brace are all unambiguous; a missing key or an unquoted word is not, and is refused so the caller
 * can ask again instead of running something the model did not mean.
 */
object ArgumentRepair {

    sealed interface Result {
        /** Repaired (or already valid) JSON, plus what was changed. */
        data class Ok(val json: String, val fixes: List<String>) : Result
        /** Not repairable without guessing. [reason] is safe to show in a log. */
        data class Failed(val reason: String) : Result
    }

    fun repair(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Failed("empty arguments")
        val fixes = ArrayList<String>()
        var s = trimmed

        // Smart quotes arrive from copy-paste, never from a model writing JSON on purpose.
        if (s.contains('\u201c') || s.contains('\u201d') || s.contains('\u2018') || s.contains('\u2019')) {
            s = s.replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2018', '\'').replace('\u2019', '\'')
            fixes.add("smart quotes")
        }

        if (!s.startsWith("{")) {
            // A bare argument list is a common shape; wrapping is unambiguous.
            if (s.startsWith("\"")) {
                s = "{$s}"
                fixes.add("wrapped bare argument list")
            } else {
                return Result.Failed("arguments are not an object")
            }
        }

        // Unbalanced braces: only a missing TAIL is safe to add.
        val open = s.count { it == '{' }
        val close = s.count { it == '}' }
        if (close < open) {
            s = s + "}".repeat(open - close)
            fixes.add("closed ${open - close} brace(s)")
        } else if (close > open) {
            return Result.Failed("more closing braces than opening ones")
        }

        // Trailing commas before a close are unambiguous.
        val noTrailing = Regex(",\\s*([}\\]])").replace(s) { m -> m.groupValues[1] }
        if (noTrailing != s) {
            s = noTrailing
            fixes.add("removed trailing comma(s)")
        }

        val balanced = s.count { it == '[' } == s.count { it == ']' }
        if (!balanced) return Result.Failed("unbalanced brackets")

        val parsed = JsonLite.parse(s)
        if (parsed !is Map<*, *>) return Result.Failed("still not valid JSON after repair")
        return Result.Ok(s, fixes)
    }
}

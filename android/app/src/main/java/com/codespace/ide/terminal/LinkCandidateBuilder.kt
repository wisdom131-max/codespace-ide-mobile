package com.codespace.ide.terminal

/**
 * BATCH11-S2 (advisor approved design, stage 2 of 3): CHEAP CANDIDATES.
 *
 * The Part A research convergence (iTerm2 PathCleaner, IntelliJ NON_PATH chars,
 * VS Code/Theia wrapped-path handling) applied to our token shapes. Everything
 * here is PURE STRING WORK — no file lookups (design item 1: quoted paths and
 * shell-escaped spaces are DETECTED without file lookups; existence picks the
 * winner later in S3). Bounded, no nested-quantifier regexes (the BATCH7 hang
 * lesson: every regex here is anchored, simple, and runs on short cleaned
 * strings, never whole wrapped lines).
 *
 * Shapes covered (advisor S2 list):
 *   - enclosing punctuation and quotes: 'file.kt', "file.kt", (file.kt), [file.kt],
 *     {file.kt}, <file.kt> — two layers max;
 *   - shell escapes: My\ codespace\ app\ 1/1.py -> My codespace app 1/1.py;
 *   - prompt prefix (owner evidence: the prompt row "root@localhost:/sdcard/My
 *     codespace app 1#" produced the token "root@localhost:/sdcard/My"):
 *     user@host:/path and user@host:~ — stripped only when / or ~ follows;
 *   - trailing junk: ! ? . , ; : and the unicode ellipsis (a '.' run that is a
 *     parent ref like ".." is protected);
 *   - git diff prefixes: a/ b/ i/ w/ c/ o/;
 *   - suffix grammars: :line, :line:col, (line), (line,col), ", line N",
 *     ", line N, column M", " line N" — the iTerm2 superset from Part A.
 *
 * Ranking (design item 6): explicit forms (quoted or escaped) FIRST, then
 * descending path length (longest wins), then leading-chunk drops last.
 * Existence still decides — this is only the cheap, honest candidate order.
 */
object LinkCandidateBuilder {

    /** Hard cap — every input shape is bounded. */
    const val MAX_CANDIDATES = 12

    /** Max leading chunks dropped ("error   at spantest.txt" -> "spantest.txt"). */
    const val MAX_CHUNK_DROPS = 4

    data class ParsedToken(val path: String, val line: Int, val col: Int)

    data class Candidate(
        val path: String,
        val line: Int,
        val col: Int,
        /** True when the source form was explicitly shell-quoted or escaped —
         *  the advisor's top rank: an explicit path beats an inferred one. */
        val explicit: Boolean,
        val origin: String,
    )

    // ── cleanup helpers (all pure, all bounded) ─────────────────────────────

    /** Strips up to two layers of enclosing punctuation. Returns text + was-quoted. */
    internal fun stripEnclosure(text: String): Pair<String, Boolean> {
        var t = text.trim()
        var quoted = false
        val pairs = listOf(
            Pair('"', '"'), Pair('\'', '\''), Pair('`', '`'),
            Pair('(', ')'), Pair('[', ']'), Pair('{', '}'), Pair('<', '>'),
        )
        for (layer in 0 until 2) {
            val match = pairs.firstOrNull { t.length >= 2 && t.startsWith(it.first) && t.endsWith(it.second) }
                ?: break
            if (match.first == '"' || match.first == '\'' || match.first == '`') quoted = true
            t = t.substring(1, t.length - 1).trim()
        }
        return Pair(t, quoted)
    }

    /** Unescapes shell escapes: backslash before space, parens, brackets, backslash, quote. */
    internal fun unescape(text: String): Pair<String, Boolean> {
        if (!text.contains('\\')) return Pair(text, false)
        val out = StringBuilder(text.length)
        var changed = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                if (n == ' ' || n == '(' || n == ')' || n == '[' || n == ']' || n == '\\' || n == '"' || n == '\'') {
                    out.append(n)
                    i += 2
                    changed = true
                    continue
                }
            }
            out.append(c)
            i++
        }
        return Pair(out.toString(), changed)
    }

    /** Strips trailing sentence junk. Protects ".." (parent ref) and never empties the string. */
    internal fun stripJunk(text: String): String {
        var t = text.trimEnd()
        val junk = "!?.,;:…"
        while (t.isNotEmpty() && t.last() in junk) {
            if (t.length == 1) break
            if (t.endsWith("..")) break
            t = t.substring(0, t.length - 1).trimEnd()
        }
        return t
    }

    /** user@host:/path or user@host:~/x — stripped ONLY when / or ~ follows the colon. */
    internal fun stripPromptPrefix(text: String): String {
        val m = Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:(?=[/~])").find(text) ?: return text
        return text.substring(m.value.length)
    }

    /** git diff prefixes. Returns the text unchanged when the remainder is too short. */
    internal fun stripDiffPrefix(text: String): String {
        val m = Regex("^(a|b|i|w|c|o)/").find(text) ?: return text
        if (text.length <= m.value.length + 1) return text
        return text.substring(m.value.length)
    }

    // ── suffix grammars (iTerm2 superset from Part A) ────────────────────────

    private val LINE_COL = Regex(":(\\d{1,7}):(\\d{1,7})$")
    private val LINE_ONLY = Regex(":(\\d{1,7})$")
    private val PAREN_LINE_COL = Regex("\\((\\d{1,7}),(\\d{1,7})\\)$")
    private val PAREN_LINE = Regex("\\((\\d{1,7})\\)$")
    private val COMMA_LINE_COL = Regex(", line (\\d{1,7}), column (\\d{1,7})$")
    private val COMMA_LINE = Regex(", line (\\d{1,7})$")
    private val SPACE_LINE = Regex(" line (\\d{1,7})$")

    /**
     * Parses path + optional line/col suffix off a CLEANED token (enclosure,
     * escapes, junk and prefixes already handled). Fixes the legacy gap pinned
     * in FileLinkReproTest: "Main.kt:42:5" used to leave ":42" in the path.
     */
    fun parseToken(clean: String): ParsedToken {
        val t = clean.trim()
        // gcc-style rows leave a stray quote on the PATH part: File "path", line 42
        // (the enclosing strip handles symmetric quotes; this trims what the
        // suffix ladder leaves behind). One layer each side, repeated max 2,
        // never empties the path.
        fun trimmed(p: String): String {
            var r = p.trim()
            var layers = 0
            while (layers < 2 && r.length > 1 && (r[0] == '"' || r[0] == '\'' || r[0] == '`')) {
                r = r.substring(1).trim()
                layers++
            }
            layers = 0
            while (layers < 2 && r.length > 1 && (r.last() == '"' || r.last() == '\'' || r.last() == '`')) {
                r = r.substring(0, r.length - 1).trim()
                layers++
            }
            return r
        }
        LINE_COL.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        LINE_ONLY.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), -1)
        }
        PAREN_LINE_COL.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        PAREN_LINE.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), -1)
        }
        COMMA_LINE_COL.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        COMMA_LINE.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), -1)
        }
        SPACE_LINE.find(t)?.let {
            return ParsedToken(trimmed(t.substring(0, it.range.first)), it.groupValues[1].toInt(), -1)
        }
        return ParsedToken(trimmed(t), -1, -1)
    }

    // ── the ranked candidate list ───────────────────────────────────────────

    /**
     * Build the ranked candidates for a raw token. Pure, bounded (MAX_CANDIDATES),
     * deduped. Order: explicit (quoted/escaped) forms first, then base forms in
     * cleanup order, then leading-chunk drops.
     */
    fun build(raw: String): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        var capped = false

        fun add(path: String, explicit: Boolean, origin: String) {
            if (capped || path.isBlank()) return
            val parsed = parseToken(path)
            if (parsed.path.isBlank()) return
            val key = parsed.path + "|" + parsed.line + "|" + parsed.col
            if (!out.containsKey(key)) {
                out[key] = Candidate(parsed.path, parsed.line, parsed.col, explicit, origin)
                if (out.size >= MAX_CANDIDATES) capped = true
            }
        }

        val (enclosed, wasQuoted) = stripEnclosure(raw.trim())
        val (unescaped, wasEscaped) = unescape(enclosed)
        val explicit = wasQuoted || wasEscaped
        val base = unescaped

        // Rank 0: the explicit form, as written (quotes/escapes removed).
        add(base, explicit, if (explicit) "explicit" else "clean")
        // Trailing sentence junk: "1.py," / "1.py:9." / "done."
        add(stripJunk(base), explicit, "junk")
        // Prompt rows: "root@localhost:/sdcard/My" (owner evidence shape).
        val prompt = stripPromptPrefix(base)
        if (prompt != base) {
            add(prompt, explicit, "prompt")
            add(stripJunk(prompt), explicit, "prompt-junk")
        }
        // git diff rows: "+++ b/src/Main.kt".
        val diff = stripDiffPrefix(base)
        if (diff != base) add(diff, explicit, "diff")
        // Leading filler words: "at /path", "cd /path" — bounded chunk drops.
        var dropped = stripJunk(base)
        for (n in 1..MAX_CHUNK_DROPS) {
            val sp = dropped.indexOf(' ')
            if (sp < 0 || sp + 1 >= dropped.length) break
            dropped = dropped.substring(sp + 1).trim()
            if (dropped.isEmpty()) break
            add(dropped, explicit, "drop" + n)
            add(stripJunk(dropped), explicit, "drop" + n + "-junk")
        }

        // Advisor ranking: explicit forms FIRST (a quoted/escaped path is the
        // user's own declaration and must outrank any inferred form), then the
        // rest in the emission order above (longest, least-mangled first).
        val explicitOnes = out.values.filter { it.explicit }
        val rest = out.values.filter { !it.explicit }
        return explicitOnes + rest
    }
}

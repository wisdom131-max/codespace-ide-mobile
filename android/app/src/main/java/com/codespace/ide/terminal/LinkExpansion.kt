package com.codespace.ide.terminal

/**
 * BATCH11-S3 (advisor approved design, stage 3): EXISTENCE-GATED EXPANSION —
 * the iTerm2 model from the Part A research, applied to our tap path.
 *
 * The SPAN scanner (BATCH7-P1) can only accept spaces when a :line suffix
 * anchors the token, so "cd /sdcard/My codespace app 1/1.py" with NO suffix
 * never produced a full-path token (the owner's Bug 1 evidence — the tap saw
 * only the whitespace word "1/1.py", and the prompt row
 * "root@localhost:/sdcard/My codespace app 1#" only "root@localhost:/sdcard/My").
 *
 * The fix is NOT a wider regex (design item 1: detection stays lightweight and
 * space-free synchronously). At TAP TIME ONLY, the chunk window around the tap
 * is grown LEFTWARD (up to MAX_LEFT_CHUNKS) and rightward (up to
 * MAX_RIGHT_CHUNKS), each window is cleaned by the S2 candidate pipeline, and
 * FILE EXISTENCE selects the winner — exactly how iTerm2's Semantic History
 * works (its PathFinder grows a brute-force window on a background queue), and
 * why no terminal widens its regex: existence is the only honest oracle for
 * "was that space part of the path?".
 *
 * Ranking (design item 6): explicit (quoted/escaped) first; absolute existing;
 * relative to the verified cwd (633 -> /proc -> initial, via the S1 order);
 * project roots only when UNIQUE. LONGEST valid window wins; a real tie
 * between distinct files is reported as ambiguity, never silently picked.
 * A directory is never returned as a file-open hit.
 *
 * Pure + probe-injected: JVM-testable with an in-memory fake file system; the
 * probe goes through LinkExistenceCache (TTL + clear-on-command + one fresh
 * re-check on negative). NEVER called on the main thread.
 */
object LinkExpansion {

    /** iTerm2 grows leftward over the whole prefix; we bound it. */
    const val MAX_LEFT_CHUNKS = 6
    /**
     * DEVICE D1 (advisor round 2026-10-19): SYMMETRIC with the left cap. The
     * old value 3 made growth position-dependent — a quoted absolute path
     * spans SIX chunks, so a tap on chunk 0 could never reach the trailing
     * chunks (1 + 3 = 4 chunks max), while taps on later chunks worked via
     * left growth. The LinkExpansionD1Test pins pin this (RED at 3, GREEN at 6).
     */
    const val MAX_RIGHT_CHUNKS = 6
    /** Total window candidates per tap (advisor hard cap). */
    const val MAX_WINDOWS = 24
    /** Slice radius around the tap for chunking (bounded, like the span scanner). */
    const val MAX_SLICE_CHARS = 256

    data class Window(val text: String, val startChunk: Int, val endChunk: Int, val width: Int)

    data class Hit(
        val path: String,
        val line: Int,
        val col: Int,
        val origin: String,
        val window: String,
        /** True when the winning candidate was an explicit quoted/escaped form. */
        val explicit: Boolean,
        /** Ranking: the window's chunk span, then its char width (longest valid wins). */
        val chunkSpan: Int,
        val charWidth: Int,
        /** D1 resolved-span overlay: the winning span's char range in the row
         *  text (start inclusive, end exclusive). -1 = unknown. */
        val spanStart: Int = -1,
        val spanEnd: Int = -1,
    )

    sealed class Result {
        data class Found(val hit: Hit) : Result()
        data class Ambiguous(val matches: List<Hit>) : Result()
        object DirectoryOnly : Result()
        object NotFound : Result()
    }

    // ── chunking ────────────────────────────────────────────────────────────

    /**
     * The chunk list of the row slice around the tap: runs of non-whitespace
     * separated by whitespace runs. Pure, linear, bounded to MAX_SLICE_CHARS.
     */
    internal fun chunks(rowText: String, tapOffset: Int): List<Pair<Int, String>> {
        val off = tapOffset.coerceIn(0, rowText.length)
        val from = maxOf(0, off - MAX_SLICE_CHARS)
        val to = minOf(rowText.length, off + MAX_SLICE_CHARS)
        val out = mutableListOf<Pair<Int, String>>()
        var i = from
        while (i < to) {
            if (rowText[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < to && !rowText[i].isWhitespace()) i++
            out.add(Pair(start, rowText.substring(start, i)))
        }
        return out
    }

    /**
     * Bounded window generation around the tapped chunk. Widest windows first
     * (longest valid wins). MAX_WINDOWS cap.
     */
    fun windows(rowText: String, tapOffset: Int): List<Window> {
        val cs = chunks(rowText, tapOffset)
        if (cs.isEmpty()) return emptyList()
        val off = tapOffset.coerceIn(0, rowText.length)
        // Anchor: the chunk containing the tap; when the tap sits on whitespace,
        // the nearest chunk on the LEFT (the common "tap the word" case).
        var anchor = -1
        for ((idx, pair) in cs.withIndex()) {
            if (pair.first <= off) anchor = idx else break
        }
        if (anchor < 0) anchor = 0
        val out = mutableListOf<Window>()
        val seen = mutableSetOf<String>()
        for (total in (1 + MAX_RIGHT_CHUNKS + MAX_LEFT_CHUNKS) downTo 2) {
            for (left in minOf(MAX_LEFT_CHUNKS, total - 1) downTo 0) {
                val right = total - 1 - left
                if (right > MAX_RIGHT_CHUNKS) continue
                val sIdx = anchor - left
                val eIdx = anchor + right
                if (sIdx < 0 || eIdx >= cs.size) continue
                val startChar = cs[sIdx].first
                val endChar = cs[eIdx].first + cs[eIdx].second.length
                val text = rowText.substring(startChar, endChar)
                if (text in seen) continue
                seen.add(text)
                out.add(Window(text, sIdx, eIdx, endChar - startChar))
                if (out.size >= MAX_WINDOWS) return out
            }
        }
        return out
    }

    // ── existence-gated selection ──────────────────────────────────────────

    /**
     * Ordered cwd bases for the selection: the S1 context order (633 tracked,
     * then /proc — passed by the caller as [procCwd] since it is IO-side —
     * then the initial dir), then project roots (unique-only, LAST).
     * "/" and "/root" never used as bases (S1 rule).
     */
    data class SelectionContext(
        val cwdCtx: IdeTerminalBridge.CwdContext?,
        val procCwd: String?,
        val roots: List<String> = emptyList(),
    )

    fun orderedCwds(ctx: SelectionContext): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun add(label: String, v: String?) {
            val t = v?.trim()?.trimEnd('/')
            if (t.isNullOrEmpty() || t == "/" || t == "/root") return
            out.add(Pair(label, t))
        }
        add("633", ctx.cwdCtx?.tracked633)
        add("proc", ctx.procCwd)
        add("initial", ctx.cwdCtx?.initialDir)
        return out
    }

    /**
     * Runs the existence-gated selection. All probes go through
     * LinkExistenceCache (TTL, clear-on-command, fresh re-check on negative).
     * Ranking: longest valid window wins (chunk span, then char width); a real
     * tie between DISTINCT files at the max width is Ambiguous, never silently
     * picked. A directory is recorded but never returned as a file hit.
     */
    fun select(
        rowText: String,
        tapOffset: Int,
        ctx: SelectionContext,
        probe: LinkFileProbe,
    ): Result {
        val wins = windows(rowText, tapOffset)
        if (wins.isEmpty()) return Result.NotFound
        val cwds = orderedCwds(ctx)
        val matches = mutableListOf<Hit>()
        var sawDirectory = false

        fun consider(path: String, cand: LinkCandidateBuilder.Candidate, w: Window, origin: String,
                      spanStart: Int = -1, spanEnd: Int = -1) {
            if (LinkExistenceCache.isDirectory(probe, path)) {
                sawDirectory = true
            } else {
                matches.add(Hit(path, cand.line, cand.col, origin, w.text, cand.explicit,
                    w.endChunk - w.startChunk, w.width, spanStart, spanEnd))
            }
        }

        // ── D1 EXPLICIT-SPAN PRE-PASS (advisor design): a quoted or shell-
        //    escaped span containing the tap is ONE unit BEFORE any chunking —
        //    detection is pure string work (LinkSpanFinder), no file lookups.
        //    If it resolves to an existing file it wins outright (explicit
        //    forms rank first); if not, the windows path below still runs.
        val explicit = LinkSpanFinder.explicitSpanAt(rowText, tapOffset)
        if (explicit != null) {
            for (cand in LinkCandidateBuilder.build(explicit.raw)) {
                if (cand.path.isBlank()) continue
                if (!cand.path.contains('/') && !cand.path.contains('.')) continue
                if (cand.path.startsWith("/")) {
                    if (LinkExistenceCache.exists(probe, cand.path)) {
                        consider(cand.path, cand, Window(explicit.raw, 0, 0, explicit.raw.length), "explicit:" + cand.origin, explicit.start, explicit.end)
                        break
                    }
                    continue
                }
                var resolved = false
                for ((label, base) in cwds) {
                    val joined = base + "/" + cand.path
                    if (LinkExistenceCache.exists(probe, joined)) {
                        consider(joined, cand, Window(explicit.raw, 0, 0, explicit.raw.length), "explicit-cwd-" + label + ":" + cand.origin, explicit.start, explicit.end)
                        resolved = true
                        break
                    }
                }
                if (resolved) break
                if (ctx.roots.isNotEmpty()) {
                    var rootHits = 0
                    for (root in ctx.roots) {
                        val joined = root.trimEnd('/') + "/" + cand.path
                        if (LinkExistenceCache.exists(probe, joined)) {
                            consider(joined, cand, Window(explicit.raw, 0, 0, explicit.raw.length), "explicit-root:" + cand.origin, explicit.start, explicit.end)
                            rootHits++
                        }
                    }
                    if (rootHits > 0) break
                }
            }
            // An explicit hit ranks above every window hit — Found now.
            val explicitHit = matches.maxWith(compareBy({ it.explicit }, { it.charWidth }))
            if (explicitHit != null && explicitHit.explicit) {
                return Result.Found(explicitHit)
            }
        }

        for (w in wins) {
            val cs = chunks(rowText, tapOffset)
            val wStart = cs.getOrNull(w.startChunk)?.first ?: -1
            val wEnd = cs.getOrNull(w.endChunk)?.let { it.first + it.second.length } ?: -1
            for (cand in LinkCandidateBuilder.build(w.text)) {
                if (cand.path.isBlank()) continue
                if (!cand.path.contains('/') && !cand.path.contains('.')) continue
                // Tier A: absolute existing.
                if (cand.path.startsWith("/")) {
                    if (LinkExistenceCache.exists(probe, cand.path)) {
                        consider(cand.path, cand, w, "absolute:" + cand.origin, wStart, wEnd)
                        break
                    }
                    continue
                }
                // Tier B: cwd-relative, in the VERIFIED source order (633 -> proc -> initial).
                var resolved = false
                for ((label, base) in cwds) {
                    val joined = base + "/" + cand.path
                    if (LinkExistenceCache.exists(probe, joined)) {
                        consider(joined, cand, w, "cwd-" + label + ":" + cand.origin, wStart, wEnd)
                        resolved = true
                        break
                    }
                }
                if (resolved) break
                // Tier C: project roots — EVERY match recorded; uniqueness is
                // decided by the final ranking (a two-root match at the max
                // width is honest Ambiguity, not a silent pick).
                if (ctx.roots.isNotEmpty()) {
                    var rootHits = 0
                    for (root in ctx.roots) {
                        val joined = root.trimEnd('/') + "/" + cand.path
                        if (LinkExistenceCache.exists(probe, joined)) {
                            consider(joined, cand, w, "root:" + cand.origin, wStart, wEnd)
                            rootHits++
                        }
                    }
                    if (rootHits > 0) break
                }
            }
        }

        if (matches.isEmpty()) {
            return if (sawDirectory) Result.DirectoryOnly else Result.NotFound
        }
        // Ranking (design item 6): explicit quoted/escaped FIRST, then longest
        // valid window (chunk span, then char width). maxWith picks the LARGEST,
        // so the comparator keys must ASCEND with rank (it.explicit: true sorts
        // above false — the first red build had !it.explicit here, which ranked
        // every NON-explicit hit above the quoted forms).
        val best = matches.maxWith(compareBy({ it.explicit }, { it.chunkSpan }, { it.charWidth }))
        val contenders = matches.filter { it.explicit == best.explicit && it.chunkSpan == best.chunkSpan && it.charWidth == best.charWidth }
        val distinct = contenders.map { it.path }.distinct()
        if (distinct.size == 1) return Result.Found(contenders.first())
        // Real tie between distinct files: show them, never pick silently.
        return Result.Ambiguous(contenders.distinctBy { it.path })
    }
}

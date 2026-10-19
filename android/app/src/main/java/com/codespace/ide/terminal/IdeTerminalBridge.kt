package com.codespace.ide.terminal

import android.content.Context
import com.codespace.ide.diagnostics.AppOutputLog
import java.io.File

/**
 * IdeTerminalBridge - terminal to editor bridge.
 *
 * Part A of the terminal file-linking feature (2026-09-05):
 *
 *  A1: OSC 7777 handler wiring. The vendored termux TerminalEmulator (see
 *      com.termux.terminal.TerminalEmulator.doOscIdeOpen) consumes the
 *      Acode-compatible sequence "ESC]7777;open;TYPE;PATH[;LINE]BEL"
 *      and calls TerminalSession.onOscIdeOpen on the reader thread. This
 *      class attaches the listener that hops to the main thread, translates
 *      the proot-guest path to a host path, and routes it to the app's
 *      EXISTING open-file-at-line editor entry point.
 *
 *      Protocol verified from Acode-Foundation/Acode source:
 *      src/components/terminal/terminal.js (setupOscHandler - registerOscHandler(7777)),
 *      src/components/terminal/terminalManager.js (onOscOpen -> openFile),
 *      src/plugins/terminal/scripts/init-alpine.sh (acode CLI printf).
 *      Acode's format is "open;type;path"; the ";line" 4th field is our
 *      documented extension (accepted ; ambiguity, same as Acode's own).
 *
 *  A2: `ide` CLI helper installed into the proot rootfs at /usr/local/bin/ide
 *      (Acode installs /usr/local/bin/acode the same way). Usage in the shell:
 *          ide src/Main.kt:42     -> opens file at line 42
 *          ide notes.txt          -> opens file
 *          ide .                  -> emits folder type (folder handling is Phase 2)
 *
 *  A3: plain-text tap detection helper - resolves a tapped "path.kt:42"-style
 *      token (build-error output is NOT wrapped in OSC 7777) against the
 *      session's cwd and the proot guest-path map.
 */
object IdeTerminalBridge {

    private const val CHANNEL = "terminal"

    // ── A1: OSC 7777 listener wiring ─────────────────────────────────────────

    /**
     * Attaches the OSC 7777 open handler to a LOCAL (proot) terminal session.
     * Remote/SSH sessions must NOT get this - a remote host must not be able
     * to trigger local file opens (Acode enforces the same rule).
     *
     * The [openFileAtLine] lambda follows the app's existing shell-level
     * convention: 0-BASED line (ProjectShellScreen adds +1 before scrolling;
     * see its onOpenFileAtLine lambda). A negative value means "no line".
     */
    fun attachOscIdeOpen(
        context: Context,
        session: com.termux.terminal.TerminalSession,
        openFileAtLine: (path: String, line: Int) -> Unit,
        /**
         * LOCKED-ROOT (2026-09-12): returns the SENDING terminal tab's locked root at
         * open time (null = unlocked). The ide script resolves relative names against
         * the shell cwd (the locked root), but an ABSOLUTE guest path or a stale cwd
         * could still name a file in a different root - the guard refuses those and
         * prints a [LOCK] line into the terminal so the refusal is visible.
         */
        lockedRootProvider: (() -> String?)? = null,
    ) {
        val appCtx = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        session.setOscIdeOpenListener { type, path, line ->
            main.post {
                try {
                    if (type != "file") {
                        AppOutputLog.log("OSC 7777: ignored non-file open request (type=" + type + " path=" + path + ")", CHANNEL)
                        return@post
                    }
                    val host = guestPathToHostFile(appCtx, path)
                    if (host == null) {
                        AppOutputLog.log("OSC 7777: path not found on host (guest=" + path + ")", CHANNEL)
                        return@post
                    }
                    // LOCKED-ROOT (2026-09-12): fail closed for locked terminals - see
                    // attachOscIdeOpen docs. Refused opens print a visible [LOCK] line.
                    val lockRoot = lockedRootProvider?.invoke()
                    if (lockRoot != null) {
                        val lockCanonical = try { File(lockRoot).canonicalPath } catch (_: Exception) { File(lockRoot).absolutePath }
                        val hostCanonical = try { host!!.canonicalPath } catch (_: Exception) { host!!.absolutePath }
                        val inside = hostCanonical == lockCanonical || hostCanonical.startsWith(lockCanonical.trimEnd('/') + "/")
                        if (!inside) {
                            AppOutputLog.log("[OSC 7777] LOCKED terminal refused open of '" + path + "' - outside locked root '" + lockCanonical + "'", CHANNEL)
                            try {
                                val em = session.getEmulator()
                                if (em != null) {
                                    val msg = ("\r\n[LOCK] ide open refused: '" + path + "' is outside this terminal's locked root\r\n").toByteArray(Charsets.UTF_8)
                                    em.append(msg, msg.size)
                                }
                            } catch (_: Exception) {}
                            return@post
                        }
                    }
                    // OSC line numbers are 1-based human numbers ("Main.kt:42");
                    // the shell lambda expects 0-based. Absent (-1) = no line.
                    val zeroBased = line - 1
                    AppOutputLog.log("OSC 7777 open: " + host.absolutePath + " line=" + line, CHANNEL)
                    openFileAtLine(host.absolutePath, zeroBased)
                } catch (e: Exception) {
                    AppOutputLog.log("OSC 7777 handler error: " + e.message, CHANNEL)
                }
            }
        }
    }

    // ── path translation ─────────────────────────────────────────────────────

    /**
     * Translates a proot-guest path (what the shell sees) to a real Android
     * host path, or null if the target does not actually exist on the host.
     *
     * Guest to host rules (reverse of IdeEnvironment.resolveWorkspacePath):
     *   /sdcard/...            -> /storage/emulated/0/...
     *   /storage/emulated/0/.. -> already host-style, kept as-is
     *   anything else          -> rootfs-relative (e.g. /root/... -> filesDir/ubuntu-rootfs/root/...)
     */
    fun guestPathToHostFile(
        context: Context,
        guestPath: String,
        /** BATCH11-S1: existence seam — JVM tests inject a fake file system. */
        probe: LinkFileProbe = LinkFileProbe.REAL,
    ): File? {
        val trimmed = guestPath.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("/sdcard/") || trimmed == "/sdcard" -> {
                val hostPath = trimmed.replaceFirst("/sdcard", "/storage/emulated/0")
                File(hostPath).takeIf { probe.exists(hostPath) }
            }
            trimmed.startsWith("/storage/emulated/") -> File(trimmed).takeIf { probe.exists(trimmed) }
            else -> {
                // rootfs-relative (e.g. /root/x, /etc/x). ProotInstaller.guestToHostPath
                // maps "/x" to rootfsDir/x on the host.
                val f = ProotInstaller.guestToHostPath(context, trimmed)
                f.takeIf { probe.exists(f.absolutePath) }
            }
        }
    }

    /**
     * A3: resolve a plain-text "path:line" token tapped in the terminal
     * (build-error style output - NOT OSC-wrapped) to a host file + 0-based
     * line, or null if it cannot be resolved.
     *
     * Resolution order:
     *   1. token as absolute host path (exists)
     *   2. token via guest-to-host translation (/sdcard/..., /root/...)
     *   3. token relative to the session shell's real cwd (TerminalSession.getCwd,
     *      which reads /proc/PID/cwd - a HOST path) - first as-is, then guest-translated
     */
    /**
     * LOCKED-ROOT WRAPPER (2026-09-12): cross-root leak fix. A terminal locked to a
     * workspace root must FAIL CLOSED for files outside that root - the terminal
     * file-link tap used to fall back to searching EVERY workspace root of the
     * project ([TAP] branch 4), so a terminal locked to Root A happily opened Root
     * B's "2.md" through the all-roots fallback. When [lockedRoot] is non-null
     * (the sending terminal tab's lock at tap time), the resolution may only land
     * INSIDE that root; anything else is refused. Unlocked terminals keep the old
     * behavior (build-output paths resolve against any root).
     */
    /**
     * A5-SPAN (2026-09-21, audit #2854): extract the file-link token at
     * [tapOffset] in [rowText] by matching SPANS instead of Termux's
     * whitespace-delimited word (which cuts "My Project/src/Main.kt:42" at the
     * space). xterm.js link providers match against the whole buffer line, so
     * this takes the full wrapped-line text; a space is accepted INSIDE a path
     * only when more path characters follow, and the :line suffix anchors the
     * end so trailing words ("... error") are never swallowed. Two forms:
     *   1. span:      path with spaces:line[:col]   (file must contain / or .)
     *   2. gcc/clang: File "path", line 42
     * Returns the matched token, or null when no match covers the tap offset
     * (caller falls back to the legacy word).
     */
    fun extractFileLinkToken(rowText: String, tapOffset: Int): String? {
        // BATCH7-P1 (advisor 1c, 2026-10-18, device-evidence): the span pattern here
        // was a nested repetition running over WHOLE wrapped lines on the MAIN thread —
        // the owner's logs show four ANRs in three minutes with main inside
        // Matcher.find -> ICU MatchChunkAt. The extraction now delegates to
        // TerminalLinkExtractor: bounded window around the tap, linear one-pass
        // scanner (no nested repetition, no backtracking), deadline-guarded regex
        // safety net. Same token shapes and gates as before (unit-tested parity).
        return TerminalLinkExtractor.extractFileLinkToken(rowText, tapOffset)
    }

    /**
     * Advisor Part 3 (2026-10-16): the tap path's resolve + exists work runs OFF
     * the main thread with a hard time limit. Hardening only — NOT a claimed root
     * cause (a blocked check gives an ANR dialog, not a silent close; the Link
     * tap mode setting is the discriminator). Result is posted back by the caller's
     * main-thread scope. Never throws.
     */
    data class TappedLinkResult(
        val file: File?,
        val line: Int,
        /** BATCH11-S1: honest outcome. The old timedOut flag collapsed NOT_FOUND,
         *  TIMEOUT and ERROR into one value, which produced the wrong "(storage
         *  busy)" wording for plain not-found tokens (advisor report gap). */
        val reason: LinkResolveReason,
        val errorClass: String? = null,
        /** BATCH11-S2: parsed column when the token carried :line:col (editor open
         *  still uses line only — the onFileLinkTap callback is (path, line); the
         *  column is parsed and logged for the future editor change). -1 = none. */
        val col: Int = -1,
        /** BATCH11-S3: distinct existing files that tied (reason=AMBIGUOUS). */
        val ambiguousPaths: List<String> = emptyList(),
        /** BATCH11-S3: the only match was a directory (reason=DIRECTORY). */
        val directoryOnly: Boolean = false,
    )

    /**
     * BATCH11-S1 (advisor approved design item 4): the cwd CONTEXT, ordered.
     * tracked633 = the OSC-633 shell-integration cwd (VERIFIED, in-memory);
     * initialDir = the tab's recorded creation dir. The resolver inserts the
     * /proc cwd BETWEEN them: [633, /proc, initialDir], filtering "/" and "/root"
     * (never silently "/", never bare /root — V0-f legacy placeholder is absent).
     * In-memory values only — built on the main thread, no I/O here.
     */
    data class CwdContext(
        val tracked633: String? = null,
        val initialDir: String? = null,
    )

    suspend fun resolveTappedFileLinkAsync(
        context: Context? = null,
        session: com.termux.terminal.TerminalSession?,
        token: String,
        projectId: String? = null,
        lockedRoot: String? = null,
        /** BATCH10-P0: DIAG-only tap-run id for [TAPDIAG] correlation (no behavior change). */
        diagId: Int? = null,
        /** BATCH11-S1: ordered cwd context (633 + initial dir; /proc read stays IO-side). */
        cwdCtx: CwdContext? = null,
        /** BATCH11-S1/S3: existence seam — JVM tests inject a fake file system. */
        probe: LinkFileProbe = LinkFileProbe.REAL,
        /** BATCH11-S3: the tapped wrapped-row text + CHAR offset — feeds the
         *  existence-gated expansion. Null = token-only path. */
        rowText: String? = null,
        tapCharOffset: Int = -1,
    ): TappedLinkResult {
        return try {
            // withTimeoutOrNull returns null BOTH on expiry AND when the block
            // returns null. Worse: blocking probes (File on FUSE, the exact thing
            // the budget guards) cannot be PREEMPTED — the block runs past the
            // budget and only then hands back null. The honest classifier is the
            // measured wall time: null + elapsed >= budget = TIMEOUT (the ONLY
            // "storage may be busy" case, per the advisor's honest-wording rule);
            // null + completed under budget = NOT_FOUND.
            val startNs = System.nanoTime()
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    resolveTappedFileLinkDetailed(context, session, token, projectId, lockedRoot, diagId, cwdCtx, probe, rowText, tapCharOffset)
                }
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            when {
                r != null -> TappedLinkResult(r.hit.first, r.hit.second, LinkResolveReason.RESOLVED, null, r.hit.third)
                r.ambiguousPaths.isNotEmpty() -> TappedLinkResult(null, -1, LinkResolveReason.AMBIGUOUS, null, -1, r.ambiguousPaths)
                r.directoryOnly -> TappedLinkResult(null, -1, LinkResolveReason.DIRECTORY)
                elapsedMs >= RESOLVE_TIMEOUT_MS -> TappedLinkResult(null, -1, LinkResolveReason.TIMEOUT)
                else -> TappedLinkResult(null, -1, LinkResolveReason.NOT_FOUND)
            }
        } catch (t: Throwable) {
            // TIMEOUT is ONLY the withTimeoutOrNull expiry above; a thrown
            // Throwable is an ERROR with its class name (honest wording).
            TappedLinkResult(null, -1, LinkResolveReason.ERROR, t.javaClass.simpleName)
        }
    }

    /** Advisor Part 3 caps (VS Code parity: maxLinkLength 500 / MaxResolvedLinkLength 1024). */
    const val MAX_TOKEN_LENGTH = 512
    const val MAX_ROOTS_TO_PROBE = 5
    const val RESOLVE_TIMEOUT_MS = 3_000L

    /** Pure, unit-tested: is this token even worth probing the disk for? */
    fun isResolvableToken(token: String): Boolean {
        val t = token.trim()
        return t.isNotEmpty() && t.length <= MAX_TOKEN_LENGTH && (t.contains('/') || t.contains('.'))
    }

    /** BATCH11-S3: the FULL resolve outcome so the pane can word every failure
     *  honestly (ambiguity list, directory-only) instead of collapsing to null. */
    data class DetailedResult(
        val hit: Triple<File, Int, Int>?,
        val ambiguousPaths: List<String> = emptyList(),
        val directoryOnly: Boolean = false,
    )

    /** BATCH11-S3: the lock guard applied to the DETAILED result's hit. */
    fun lockFilter(resolved: Triple<File, Int, Int>?, lockedRoot: String?, token: String): Triple<File, Int, Int>? {
        if (resolved == null) return null
        if (lockedRoot != null) {
            val lockCanonical = try { File(lockedRoot).canonicalPath } catch (_: Exception) { File(lockedRoot).absolutePath }
            val resCanonical = try { resolved.first.canonicalPath } catch (_: Exception) { resolved.first.absolutePath }
            val inside = resCanonical == lockCanonical || resCanonical.startsWith(lockCanonical.trimEnd('/') + "/")
            if (!inside) {
                AppOutputLog.log("[TAP] LOCKED terminal refused '" + token + "' - '" + resCanonical + "' is outside locked root '" + lockCanonical + "'", CHANNEL)
                return null
            }
        }
        return resolved
    }

    fun resolveTappedFileLink(
        context: Context? = null,
        session: com.termux.terminal.TerminalSession?,
        token: String,
        projectId: String? = null,
        lockedRoot: String? = null,
        diagId: Int? = null,
        cwdCtx: CwdContext? = null,
        probe: LinkFileProbe = LinkFileProbe.REAL,
        rowText: String? = null,
        tapCharOffset: Int = -1,
    ): Triple<File, Int, Int>? {
        val detailed = resolveTappedFileLinkDetailed(context, session, token, projectId, lockedRoot, diagId, cwdCtx, probe, rowText, tapCharOffset)
        return detailed.hit
    }

    /** BATCH11-S3: the honest full outcome — hit OR ambiguity list OR directory-only. */
    fun resolveTappedFileLinkDetailed(
        context: Context? = null,
        session: com.termux.terminal.TerminalSession?,
        token: String,
        projectId: String? = null,
        lockedRoot: String? = null,
        diagId: Int? = null,
        cwdCtx: CwdContext? = null,
        probe: LinkFileProbe = LinkFileProbe.REAL,
        rowText: String? = null,
        tapCharOffset: Int = -1,
    ): DetailedResult {
        val outcome = resolveTappedFileLinkUnscoped(context, session, token, projectId, diagId, cwdCtx, probe, rowText, tapCharOffset)
        if (outcome.ambiguousPaths.isNotEmpty() || outcome.directoryOnly) {
            // Ambiguity / directory-only are VERDICTS — the token chain must not
            // silently pick one of the tied files behind the user's back.
            return DetailedResult(null, outcome.ambiguousPaths, outcome.directoryOnly)
        }
        val hit = lockFilter(outcome.hit, lockedRoot, token)
        return DetailedResult(hit)
    }

    fun resolveTappedFileLinkUnscoped(
        context: Context? = null,
        session: com.termux.terminal.TerminalSession?,
        token: String,
        projectId: String? = null,
        diagId: Int? = null,
        cwdCtx: CwdContext? = null,
        probe: LinkFileProbe = LinkFileProbe.REAL,
        rowText: String? = null,
        tapCharOffset: Int = -1,
    ): DetailedResult {
        // Advisor Part 3 cap: an absurdly long "token" is not probed at all.
        if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) return DetailedResult(null)
        // BATCH11-S3: EXISTENCE-GATED EXPANSION first (the iTerm2 model — the
        // span scanner can only accept spaces when a :line suffix anchors the
        // token, so plain "cd /My codespace app 1/1.py" rows never produced a
        // full-path token; at tap time the chunk window around the tap grows
        // and FILE EXISTENCE picks the winner). Falls back to the S2 token
        // chain below when the expansion has nothing to work with.
        if (rowText != null && tapCharOffset >= 0) {
            val expansion = expandAndSelect(context, session, token, rowText, tapCharOffset, projectId, diagId, cwdCtx, probe)
            if (expansion.hit != null) return DetailedResult(expansion.hit)
            if (expansion.ambiguousPaths.isNotEmpty()) return DetailedResult(null, expansion.ambiguousPaths, false)
            if (expansion.directoryOnly) return DetailedResult(null, emptyList(), true)
        }
        // BATCH11-S2: the cheap candidate pipeline (quotes, escapes, prompt
        // prefix, junk, diff prefix, :line/:line:col suffixes, bounded chunk
        // drops) — pure string work, existence picks the winner.
        val dirHits = mutableListOf<String>()
        for (candidate in LinkCandidateBuilder.build(token)) {
            val r = resolveSingleToken(context, session, candidate, projectId, diagId, cwdCtx, probe, dirHits)
            if (r != null) return DetailedResult(r)
        }
        if (dirHits.isNotEmpty()) return DetailedResult(null, emptyList(), true)
        return DetailedResult(null)
    }

    /**
     * BATCH11-S3: run LinkExpansion.select with the S1-ordered cwd context and the
     * project roots (IO-side reads only — this whole path runs on Dispatchers.IO).
     * Returns the selected file, or null when the expansion found nothing (the
     * caller falls back to the S2 token chain — the expansion is additive, never
     * a regression for tokens the old path already handled).
     */
    private fun expandAndSelect(
        context: Context?,
        session: com.termux.terminal.TerminalSession?,
        token: String,
        rowText: String,
        tapCharOffset: Int,
        projectId: String?,
        diagId: Int?,
        cwdCtx: CwdContext?,
        probe: LinkFileProbe,
    ): LinkExpansionOutcome {
        val appCtx = context?.applicationContext
        val procCwd = try { session?.cwd } catch (_: Exception) { null }
        val roots = if (projectId == null || appCtx == null) emptyList()
            else try {
                com.codespace.ide.util.ProjectPathResolver.getAllWorkspaceRoots(appCtx, projectId).take(MAX_ROOTS_TO_PROBE)
            } catch (_: Exception) { emptyList() }
        val ctx = LinkExpansion.SelectionContext(cwdCtx = cwdCtx, procCwd = procCwd, roots = roots)
        when (val sel = LinkExpansion.select(rowText, tapCharOffset, ctx, probe)) {
            is LinkExpansion.Result.Found -> {
                val h = sel.hit
                AppOutputLog.log("[TAP] expansion RESOLVED: " + h.path + (if (h.line > 0) ":" + h.line else "") + " origin=" + h.origin + " window='" + h.window.take(80) + "'", CHANNEL)
                return LinkExpansionOutcome(Triple(File(h.path), if (h.line > 0) h.line - 1 else -2, h.col), emptyList(), false)
            }
            is LinkExpansion.Result.Ambiguous -> {
                val list = sel.matches.joinToString(" | ") { it.path }
                AppOutputLog.log("[TAP] expansion AMBIGUOUS: " + list.take(200), CHANNEL)
                return LinkExpansionOutcome(null, sel.matches.map { it.path }, false)
            }
            is LinkExpansion.Result.DirectoryOnly -> {
                AppOutputLog.log("[TAP] expansion: only a DIRECTORY matched (not opened as a file)", CHANNEL)
                return LinkExpansionOutcome(null, emptyList(), true)
            }
            is LinkExpansion.Result.NotFound -> {
                AppOutputLog.log("[TAP] expansion: no window resolved to an existing file — falling back to token chain", CHANNEL)
            }
        }
        return LinkExpansionOutcome(null, emptyList(), false)
    }

    /** BATCH11-S3: the expansion's full outcome — hit, honest ambiguity list,
     *  directory-only marker (a directory is never opened as a file). */
    data class LinkExpansionOutcome(
        val hit: Triple<File, Int, Int>?,
        val ambiguousPaths: List<String>,
        val directoryOnly: Boolean,
    )

    /** A5: progressive leading-chunk drop — bounded (max 5), pure (unit-tested). */
    fun linkCandidates(token: String): List<String> {
        val out = mutableListOf(token)
        var t = token
        while (out.size < 5 && t.contains(' ')) {
            val sp = t.indexOf(' ')
            t = t.substring(sp + 1).trim()
            if (t.isEmpty()) break
            if (t != out.last()) out.add(t)
        }
        return out
    }

    /**
     * Advisor 1b (2026-10-15): the token parse, extracted PURE so the exact
     * device repro strings are JVM-testable. Splits an optional :line suffix
     * off the token; a line longer than 6 digits is treated as NOT a line
     * (toInt() would overflow on "Main.kt:999999999999" — that was an
     * NumberFormatException escaping the parse, caught only by the outer
     * tap catch).
     */
    fun parseFileLinkToken(token: String): Pair<String, Int> {
        // BATCH11-S2: delegates to LinkCandidateBuilder.parseToken (fixes the
        // legacy :line:col gap — "Main.kt:42:5" used to leave ":42" in the path;
        // the FileLinkReproTest pin is updated to the CORRECT behavior). The
        // legacy Pair keeps its shape for existing callers; the column is
        // dropped here and carried by the builder/resolver chain.
        val clean = token.trim().trimEnd { it == ':' || it == ',' || it == ';' || it.isWhitespace() }
        val parsed = LinkCandidateBuilder.parseToken(clean)
        return Pair(parsed.path, parsed.line)
    }

    private fun resolveSingleToken(
        context: Context? = null,
        session: com.termux.terminal.TerminalSession?,
        /** BATCH11-S2: the already-parsed candidate from LinkCandidateBuilder. */
        candidate: LinkCandidateBuilder.Candidate,
        projectId: String?,
        diagId: Int? = null,
        cwdCtx: CwdContext? = null,
        probe: LinkFileProbe = LinkFileProbe.REAL,
        /** BATCH11-S3: populated when a DIRECTORY matched (design item 6: a
         *  directory is never opened as a file — recorded for the honest verdict). */
        directoryHits: MutableList<String>? = null,
    ): Triple<File, Int, Int>? {
        val pathPart = candidate.path
        val lineNum = candidate.line
        val colNum = candidate.col
        // BATCH11-S1: nullable for JVM tests (tests exercise the probe/cwd logic with
        // a fake file system and no Android Context); production always passes one.
        val appCtx = context?.applicationContext
        // BATCH10-P0 (advisor Bug 2): DIAG-ONLY — the IO-side facts of the run:
        // the /proc-read session cwd (the value branch 3 actually uses, read on
        // this IO thread, never main) correlated with the main-thread [TAPDIAG] id.
        if (diagId != null) {
            val procCwd = try { session?.cwd } catch (_: Exception) { null }
            // Roots count + loaded state (advisor Bug 2 fact) — probed ON THIS IO
            // thread, never main (File.exists on FUSE can stall; standing rule).
            val diagRoots = try {
                if (projectId == null || appCtx == null) emptyList()
                else com.codespace.ide.util.ProjectPathResolver.getAllWorkspaceRoots(appCtx, projectId)
            } catch (_: Exception) { emptyList() }
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[TAPDIAG] id=" + diagId + " resolve-enter" +
                    " candidate='" + candidate.path.take(60) + "' line=" + candidate.line + " col=" + candidate.col + " origin=" + candidate.origin +
                    " sessionCwdProc='" + procCwd + "'" +
                    " roots=" + (if (projectId == null) "none(projectId=null)" else diagRoots.size.toString()) +
                    " rootsList='" + diagRoots.joinToString("|").take(120) + "'" +
                    " pid=" + (try { session?.pid ?: -1 } catch (_: Exception) { -1 }),
                CHANNEL,
            )
        }
        // TAP-DIAG: every step logs to the Output tab (terminal channel) so the next
        // on-device test pinpoints exactly WHERE resolution fails instead of failing
        // silently. token=what was extracted from the tap, pathPart/lineNum=what each
        // branch tried.
        AppOutputLog.log("[TAP] file-link tap: origin=" + candidate.origin + " pathPart='" + pathPart + "' line=" + lineNum + " col=" + colNum + " explicit=" + candidate.explicit, CHANNEL)
        // 1. absolute host path (BATCH11-S3: a directory is recorded, not opened)
        if (probe.exists(pathPart) && java.io.File(pathPart).isAbsolute && probe.isDirectory(pathPart)) {
            directoryHits?.add(pathPart)
        }
        File(pathPart).takeIf {
            it.isAbsolute && probe.exists(pathPart) && !probe.isDirectory(pathPart)
        }?.let {
            AppOutputLog.log("[TAP] resolved via absolute host path: " + it.absolutePath, CHANNEL)
            return Triple(it, lineNum - 1, colNum)
        }
        // 2. guest -> host translation. BATCH11-S1: guarded — a failing guest map
        // (no context, proot not installed, map error) must not kill the resolve;
        // later branches still get their turn.
        if (appCtx != null) {
            try {
                guestPathToHostFile(appCtx, pathPart, probe)?.let {
                    if (probe.isDirectory(it.absolutePath)) {
                        AppOutputLog.log("[TAP] guest->host map matched a DIRECTORY (not opened as a file)", CHANNEL)
                        directoryHits?.add(it.absolutePath)
                    } else {
                        AppOutputLog.log("[TAP] resolved via guest->host map: " + it.absolutePath, CHANNEL)
                        return Triple(it, lineNum - 1, colNum)
                    }
                }
            } catch (_: Throwable) {
                AppOutputLog.log("[TAP] guest->host map branch failed (skipped)", CHANNEL)
            }
        }
        // 3. relative to the session cwd. BATCH11-S1 (advisor approved design
        // item 4): EXPLICIT source order — the OSC-633 tracked cwd (verified,
        // in-memory) FIRST, then the /proc cwd (read here on the IO thread,
        // never main), then the tab's recorded initial dir. "/" and "/root" are
        // NEVER used as a cwd base (never silently "/", never bare /root); a
        // source that is filtered out is LOGGED, not silent.
        val procCwd = try { session?.cwd } catch (_: Exception) { null }
        val cwdSources = listOf(
            Pair("cwd633", cwdCtx?.tracked633),
            Pair("proc", procCwd),
            Pair("initialDir", cwdCtx?.initialDir),
        )
        var anyCwdUsable = false
        for ((sourceLabel, cwdVal) in cwdSources) {
            val cwd = cwdVal?.trim()?.trimEnd('/')
            if (cwd == null || cwd.isEmpty()) {
                AppOutputLog.log("[TAP] cwd source " + sourceLabel + ": null/empty", CHANNEL)
                continue
            }
            if (cwd == "/" || cwd == "/root") {
                // Honest diag for the owner's "trying session cwd=/" evidence:
                // the /proc read reported "/" (or the legacy /root placeholder)
                // — that base is deliberately NOT probed.
                AppOutputLog.log("[TAP] cwd source " + sourceLabel + "='" + cwd + "' SKIPPED (never a base)", CHANNEL)
                continue
            }
            anyCwdUsable = true
            val joined = File(cwd, pathPart)
            AppOutputLog.log("[TAP] trying cwd(" + sourceLabel + ")=" + cwd + " -> " + joined.absolutePath, CHANNEL)
            if (probe.exists(joined.absolutePath)) {
                if (probe.isDirectory(joined.absolutePath)) {
                    AppOutputLog.log("[TAP] cwd(" + sourceLabel + ") matched a DIRECTORY (not opened as a file)", CHANNEL)
                    directoryHits?.add(joined.absolutePath)
                } else {
                    AppOutputLog.log("[TAP] resolved via cwd(" + sourceLabel + "): " + joined.absolutePath, CHANNEL)
                    return Triple(joined, lineNum - 1, colNum)
                }
            }
        }
        if (!anyCwdUsable) {
            AppOutputLog.log("[TAP] no usable cwd (all sources null or filtered) - cwd branch skipped", CHANNEL)
        }
        // 4. FIX: relative to every workspace root of the project. Build tools print
        // paths relative to the project root ("src/Main.kt:42") regardless of where
        // the shell happens to sit; branch 3 missed those whenever the shell cwd was
        // "/" (the initial proot cwd) or a different folder.
        if (projectId != null && appCtx != null) {
            val roots = try {
                com.codespace.ide.util.ProjectPathResolver.getAllWorkspaceRoots(appCtx, projectId)
            } catch (_: Exception) { emptyList() }
            // Advisor Part 3 cap (VS Code MaxResolvedLinksInLine parity): bound the probes.
            val capped = roots.take(MAX_ROOTS_TO_PROBE)
            AppOutputLog.log("[TAP] workspace-root fallback: " + capped.size + "/" + roots.size + " root(s) for project " + projectId, CHANNEL)
            for (root in capped) {
                val joined = File(root, pathPart)
                if (probe.exists(joined.absolutePath) && !probe.isDirectory(joined.absolutePath)) {
                    AppOutputLog.log("[TAP] resolved via workspace root " + root + ": " + joined.absolutePath, CHANNEL)
                    return Triple(joined, lineNum - 1, colNum)
                }
            }
        }
        AppOutputLog.log("[TAP] unresolved: no branch matched '" + pathPart + "'", CHANNEL)
        return null
    }

    // ── A2: `ide` CLI installer ─────────────────────────────────────────────

    /**
     * Installs /usr/local/bin/ide into the proot rootfs (Acode installs its
     * `acode` helper at /usr/local/bin/acode in init-alpine.sh the same way).
     * Idempotent - overwrites with the current version on every call.
     */
    fun installIdeCli(context: Context) {
        try {
            val rootfs = ProotInstaller.rootfsDir(context)
            val binDir = File(rootfs, "usr/local/bin")
            binDir.mkdirs()
            val script = File(binDir, "ide")
            script.writeText(buildIdeCliScript())
            script.setReadable(true, false)
            script.setWritable(true, false)
            script.setExecutable(true, false)
            // On-device verification: 'ide: 'open' does not exist' errors proved the
            // script WAS installed (that message comes from this script itself), but the
            // user could not tell. Log install state so the Output tab (terminal channel)
            // settles "is the CLI actually there?" on every session start.
            AppOutputLog.log("ide CLI: " + script.absolutePath + " exists=" + script.exists() + " exec=" + script.canExecute(), CHANNEL)
        } catch (e: Exception) {
            AppOutputLog.log("ide CLI install failed: " + e.message, CHANNEL)
        }
    }

    /** POSIX-sh script. Lines joined with \n - never embed raw newlines in Kotlin literals. */
    private fun buildIdeCliScript(): String = listOf(
        "#!/bin/sh",
        "# ide - open a file or folder in the Codespace IDE editor from the terminal.",
        "# Sends OSC 7777 (Acode-compatible file-open protocol):",
        "#   ESC]7777;open;TYPE;PATH[;LINE]BEL",
        "# The IDE terminal consumes the sequence and opens the file in the editor.",
        "# Usage:",
        "#   ide <path>[:line]      (VS Code `code <path>` style)",
        "#   ide open <path>[:line] (explicit subcommand style)",
        "#   ide .                  (opens current folder)",
        "if [ \"\$#\" -eq 0 ]; then",
        "    set -- \".\"",
        "fi",
        "# FIX: the `open` subcommand was reported on-device as \"ide: 'open' does not exist\" -",
        "# the script treated the WORD 'open' as the file path. Accept and strip it",
        "# (both bare-path and subcommand forms work identically now).",
        "if [ \"\$1\" = \"open\" ] || [ \"\$1\" = \"edit\" ]; then",
        "    shift",
        "    if [ \"\$#\" -eq 0 ] || [ -z \"\$1\" ]; then",
        "        set -- \".\"",
        "    fi",
        "fi",
        "target=\"\$1\"",
        "line=\"\"",
        "maybe=\$(printf '%s' \"\$target\" | cut -d: -f1)",
        "suffix=\$(printf '%s' \"\$target\" | cut -d: -f2)",
        "if [ -n \"\$suffix\" ] && [ -e \"\$maybe\" ]; then",
        "    target=\"\$maybe\"",
        "    line=\"\$suffix\"",
        "fi",
        "abs=\$(realpath -- \"\$target\" 2>/dev/null)",
        "if [ -z \"\$abs\" ] || [ ! -e \"\$abs\" ]; then",
        "    if [ -e \"\$target\" ]; then",
        "        d=\$(dirname -- \"\$target\")",
        "        f=\$(basename -- \"\$target\")",
        "        abs=\$(cd -- \"\$d\" 2>/dev/null && pwd -P)/\$f",
        "    fi",
        "fi",
        "if [ -z \"\$abs\" ] || [ ! -e \"\$abs\" ]; then",
        "    echo \"ide: '\$1' does not exist\" >&2",
        "    exit 1",
        "fi",
        "type=\"file\"",
        "[ -d \"\$abs\" ] && type=\"folder\"",
        "if [ -n \"\$line\" ]; then",
        "    printf '\\033]7777;open;%s;%s;%s\\a' \"\$type\" \"\$abs\" \"\$line\"",
        "else",
        "    printf '\\033]7777;open;%s;%s\\a' \"\$type\" \"\$abs\"",
        "fi",
        "",
    ).joinToString("\n")
}

package com.codespace.ide.util

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P2a — the canonical-path containment utility (2026-09-24).
 *
 * Specification: `terminal/ProotInstaller.kt:107` — the one in-tree site that got
 * the boundary right:
 *
 *     candCanonical == rootCanonical || candCanonical.startsWith(rootCanonical.trimEnd('/') + "/")
 *
 * Every call site that decides whether a path is inside a directory MUST consume
 * this utility (owner ruling 2026-09-24, MASTER-GAPS.md cross-cutting table).
 * Consumers: EX04/EX05/EX07 (ExplorerPane), RG03/RG04 (CloudBackupManager),
 * RG07 (BackupManager), IG01 (AgentEntityManager), VG02/VG02-b
 * (LivePreviewServer), WorkspaceContextProvider, OG04 (ProjectWizard/Templates).
 *
 * Three dialects, one rule: canonicalize BOTH sides, accept only `child == root`
 * or `child.startsWith(root + separator)`. Sibling-prefix matches ("out2" inside
 * "out"), absolute paths, and `..` escapes are all rejected.
 */
object CanonicalPaths {

    /** Canonicalize with absolute-path fallback (ProotInstaller.kt:107 form). */
    fun canonical(file: File): String =
        try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    /**
     * TRUE containment: child is root itself, or inside root behind a
     * path-separator boundary. Rejects sibling-prefix matches ("out2" is NOT
     * inside "out"), absolute escapes, and `..` escapes — both sides are
     * canonicalized first, so symlinked intermediates resolve before the check.
     */
    fun isInside(root: File, child: File): Boolean {
        val rootCanonical = canonical(root).trimEnd('/')
        val childCanonical = canonical(child)
        return childCanonical == rootCanonical || childCanonical.startsWith(rootCanonical + "/")
    }

    /** String-path variant. */
    fun isInside(rootPath: String, childPath: String): Boolean =
        isInside(File(rootPath), File(childPath))

    /**
     * Resolve an UNTRUSTED ARCHIVE ENTRY NAME (may contain nested relative
     * segments, e.g. "src/main/Foo.kt" or "./dir/file") inside root.
     * Zip-slip / tar-slip defense (EX05 / RG03 / RG07 / EX04 dialects):
     *   - rejects absolute names (POSIX "/", Windows drive letters)
     *   - rejects NUL bytes
     *   - drops "." segments, rejects any ".." segment
     *   - final authority: canonical containment via [isInside]
     * Returns the contained destination File, or null = REJECT (fail closed;
     * the caller skips the entry — the archive stream skips its data on advance).
     */
    fun safeEntryDestination(root: File, entryName: String): File? {
        if (entryName.isBlank() || entryName.contains('\u0000')) return null
        if (entryName.startsWith("/") || entryName.startsWith("\\") ||
            (entryName.length > 1 && entryName[1] == ':')
        ) return null
        val segments = entryName.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        var dest = root
        for (segment in segments) dest = File(dest, segment)
        return if (isInside(root, dest)) dest else null
    }

    /**
     * Validate an UNTRUSTED SINGLE NAME SEGMENT (New File/Folder/Rename inputs,
     * wizard project names, agent entity names): exactly one path segment —
     * no separators at all, not "." or "..", no NUL bytes.
     * Returns the trimmed validated name, or null = REJECT.
     */
    fun safeNameSegment(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        if (trimmed.contains('\u0000')) return null
        return trimmed
    }

    // ── PLAN A (P3b, 2026-09-25): per-file IDENTITY canonicalization ──────────────
    // The path-dialect family root cause (G03/LS06/DG11/TB08, MASTER-CONNECTIONS §2):
    // one physical file reached as raw-string, relative, URL-encoded, host, or guest
    // spellings became different identity keys. Every in-app identity decision now
    // keys through canonicalKey / sameFileIdentity below — NOT raw string equality
    // and NOT basename matching (LS06: two tabs named test.js got each other's
    // squiggles; DG11: the paused band painted on a same-named file in another folder).

    /** Bounded memo for canonicalKey — canonicalFile is a syscall; lookups are hot. */
    private val keyCache = ConcurrentHashMap<String, String>(64)
    private const val KEY_CACHE_MAX = 1024

    /**
     * Canonical identity key for a host-side file path: canonicalPath with
     * absolute-path fallback. Memoized (bounded) — canonicalFile is IO.
     */
    fun canonicalKey(path: String): String {
        keyCache[path]?.let { return it }
        val key = canonical(File(path))
        if (keyCache.size >= KEY_CACHE_MAX) keyCache.clear()
        keyCache[path] = key
        return key
    }

    /**
     * TRUE file identity: same string, same canonical file, or a
     * separator-boundary suffix of one another (relative-vs-absolute dialects).
     * This replaces raw equality AND basename fallbacks in tab/diag/band matching.
     */
    fun sameFileIdentity(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        if (canonicalKey(a) == canonicalKey(b)) return true
        return suffixBoundaryMatch(a, b)
    }

    /** Boundary-aware endsWith in EITHER direction: "x/y" matches "y" but not "xy". */
    private fun suffixBoundaryMatch(a: String, b: String): Boolean {
        val ca = canonicalKey(a).trimEnd('/')
        val cb = canonicalKey(b).trimEnd('/')
        fun endsOnBoundary(long: String, short: String): Boolean {
            if (long.length <= short.length) return false
            return long.endsWith(short) && long[long.length - short.length - 1] == '/'
        }
        return endsOnBoundary(ca, cb) || endsOnBoundary(cb, ca)
    }

    /**
     * Resolve a raw path against a project root (relative matchers/LSP output),
     * canonicalize when the target exists. PLAN A form of the BUG-A resolution.
     */
    fun resolveAgainstRoot(raw: String, root: String?): String {
        return try {
            var f = File(raw)
            if (!f.isAbsolute && !root.isNullOrBlank()) f = File(root, raw)
            val canon = f.canonicalFile
            if (canon.exists()) canon.absolutePath else f.absolutePath
        } catch (_: Exception) { raw }
    }

    /**
     * Find which candidate path (open tab list) IS the target file:
     * exact string, canonical identity, then boundary-suffix match.
     * Returns the matched candidate, or null when genuinely not open.
     */
    fun resolveTabMatch(candidatePaths: List<String>, target: String, root: String?): String? {
        val resolvedTarget = resolveAgainstRoot(target, root)
        candidatePaths.firstOrNull { it == resolvedTarget }?.let { return it }
        candidatePaths.firstOrNull { sameFileIdentity(it, resolvedTarget) }?.let { return it }
        return candidatePaths.firstOrNull { suffixBoundaryMatch(it, resolvedTarget) }
    }

    /**
     * Canonical identity for a path that may arrive in GUEST dialect (DAP stack
     * frames, terminal output): use the host file when it exists, else translate
     * guest->host and canonicalize that. Returns null when neither resolves.
     */
    fun canonicalHostKey(context: Context, path: String): String? {
        val host = if (File(path).exists()) File(path)
            else com.codespace.ide.terminal.ProotInstaller.guestToHostPath(context, path)
        return if (host.exists()) canonical(host) else null
    }
}

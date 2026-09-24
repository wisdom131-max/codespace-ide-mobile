package com.codespace.ide.util

import java.io.File

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
}

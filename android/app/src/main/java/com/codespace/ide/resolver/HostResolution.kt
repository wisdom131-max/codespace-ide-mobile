package com.codespace.ide.resolver

import com.codespace.ide.util.CanonicalPaths
import java.io.File

/**
 * V0-a (resolver architecture, 2026-10-05 owner-approved): HOST resolution —
 * "what host file does this raw tool/AI/editor path actually mean?"
 *
 * Lifted VERBATIM from ToolPathResolver.resolve into PURE Kotlin (Android
 * dependencies passed as plain values) so the JVM suite covers every branch:
 *
 *   - RELATIVE paths join the active project root (root spelled host OR guest —
 *     a guest-spelled root is translated through [PathDialects.guestToHost] first,
 *     the exact ToolPathResolver behavior); no project root = IllegalArgumentException
 *     ("nothing was written" — fail closed, never guess cwd).
 *   - ABSOLUTE paths that EXIST on disk canonicalize as-is.
 *   - ANDROID prefixes (/storage/..., filesDir/..., /data/user/0/<pkg>/...,
 *     /data/data/<pkg>/...) canonicalize as-is — NEW and EXISTING files alike,
 *     never prefixed with the guest rootfs.
 *   - everything else is a GUEST spelling (/etc/..., /root/... — includes NEW
 *     guest files and nested directories; never rely on target.exists()) and
 *     resolves through [PathDialects.guestToHost] before canonicalizing.
 *
 * ToolPathResolver keeps its public signature (thin forwarder, V0-e).
 */
object HostResolution {

    /**
     * One host-path identity for direct file tools, staged edits, Apply and
     * Force apply. Throws [IllegalArgumentException] on an empty path or a
     * relative path with no project root — same contract as ToolPathResolver.
     */
    fun toHostPath(
        raw: String,
        projectRoot: String?,
        hostFilesDir: String,
        packageName: String,
        rootfsPath: String,
    ): String {
        val path = raw.trim()
        require(path.isNotEmpty()) { "Empty file path; nothing was written" }
        val file = File(path)
        if (!file.isAbsolute) {
            val rootPath = projectRoot?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Relative file path needs an active project root; nothing was written")
            val root = File(rootPath)
            val hostRoot = if (root.exists()) root else PathDialects.guestToHost(rootPath, rootfsPath, hostFilesDir)
            return CanonicalPaths.canonical(File(hostRoot, path))
        }
        if (file.exists()) return CanonicalPaths.canonical(file)
        // Existing and new Android paths must not be prefixed with the guest rootfs.
        if (path == "/storage" || path.startsWith("/storage/") ||
            path == hostFilesDir || path.startsWith("$hostFilesDir/") ||
            path.startsWith("/data/user/0/$packageName/") ||
            path.startsWith("/data/data/$packageName/")
        ) return CanonicalPaths.canonical(file)
        // Includes NEW guest files/nested directories. Never rely on target.exists().
        return CanonicalPaths.canonical(PathDialects.guestToHost(path, rootfsPath, hostFilesDir))
    }
}

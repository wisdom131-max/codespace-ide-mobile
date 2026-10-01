package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.security.TrustState
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.util.CanonicalPaths
import java.io.File

/** One host-path identity for direct file tools, staged edits, Apply and Force apply. */
object ToolPathResolver {
    fun resolve(context: Context, raw: String, projectRoot: String? = TrustState.activeProjectRoot(context)): String {
        val path = raw.trim()
        require(path.isNotEmpty()) { "Empty file path; nothing was written" }
        val file = File(path)
        if (!file.isAbsolute) {
            val rootPath = projectRoot?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Relative file path needs an active project root; nothing was written")
            val root = File(rootPath)
            val hostRoot = if (root.exists()) root else ProotInstaller.guestToHostPath(context, rootPath)
            return CanonicalPaths.canonical(File(hostRoot, path))
        }
        if (file.exists()) return CanonicalPaths.canonical(file)
        // Existing and new Android paths must not be prefixed with the guest rootfs.
        if (path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/") ||
            path == context.filesDir.absolutePath || path.startsWith(context.filesDir.absolutePath + "/") ||
            path.startsWith("/data/user/0/" + context.packageName + "/") ||
            path.startsWith("/data/data/" + context.packageName + "/")) return CanonicalPaths.canonical(file)
        // Includes NEW guest files/nested directories. Never rely on target.exists().
        return CanonicalPaths.canonical(ProotInstaller.guestToHostPath(context, path))
    }
}

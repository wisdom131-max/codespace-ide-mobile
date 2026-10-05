package com.codespace.ide.resolver

import java.io.File

/**
 * V0-a (resolver architecture, 2026-10-05 owner-approved): the PATH-DIALECT
 * translation tables, lifted VERBATIM from ProotInstaller.hostToGuestPath /
 * guestToHostPath into PURE Kotlin (no Android Context) so the JVM test suite
 * covers every row of both tables host-side.
 *
 * ONE translation, two directions:
 *   HOST spelling (what the app, Android APIs, and the disk see):
 *     /storage/emulated/0/...           (shared/external storage projects)
 *     /data/user/0/<pkg>/files/...     (app-private; filesDir)
 *     .../ubuntu-rootfs/...            (the rootfs tree itself)
 *   GUEST spelling (what proot, bash, git, the LSP and DAP servers see):
 *     /sdcard/...  /host-files/...  /... (rootfs paths at /)
 *
 * ProotInstaller keeps its public functions (thin forwarders, V0-e) — every caller
 * keeps compiling unchanged; the table lives HERE from now on.
 *
 * V0-c: [guestToHost] consults [BindingRegistry.active] BEFORE the rootfs
 * fallback, so V1's workspace bindings resolve without touching this file again.
 */
object PathDialects {

    /**
     * Host → guest. Returns null when the host path is not reachable inside
     * proot at all (no bind-mount covers it) — callers treat null as
     * "do not pretend it is accessible" and fail closed.
     */
    fun hostToGuest(hostPath: String, rootfsPath: String, hostFilesDir: String): String? {
        return when {
            hostPath == rootfsPath -> "/"
            hostPath.startsWith("$rootfsPath/") -> "/" + hostPath.removePrefix("$rootfsPath/")
            hostPath == "/storage/emulated/0" -> "/sdcard"
            hostPath.startsWith("/storage/emulated/0/") -> "/sdcard/" + hostPath.removePrefix("/storage/emulated/0/")
            hostPath == "/sdcard" || hostPath.startsWith("/sdcard/") -> hostPath
            hostPath == hostFilesDir -> "/host-files"
            hostPath.startsWith("$hostFilesDir/") -> "/host-files/" + hostPath.removePrefix("$hostFilesDir/")
            else -> null // not bind-mounted into the proot guest
        }
    }

    /**
     * Guest → host. EXACT ProotInstaller semantics, with the V0-c binding consult
     * inserted before the rootfs fallback:
     *   - /sdcard → /storage/emulated/0
     *   - /host-files/projects/... → filesDir/projects/... (RESTRICTED to projects:
     *     app-private data like databases and token storage must keep resolving to
     *     the nonexistent rootfs fallback so exists() checks refuse it; escape
     *     attempts with .. fail the canonical containment check below)
     *   - V1 bindings: BindingRegistry.active covers the path → the bound host path
     *   - everything else → inside the rootfs tree
     */
    fun guestToHost(guestPath: String, rootfsPath: String, hostFilesDir: String): File {
        val trimmed = guestPath.trim()
        return when {
            trimmed == "/sdcard" -> File("/storage/emulated/0")
            trimmed.startsWith("/sdcard/") -> File("/storage/emulated/0/" + trimmed.removePrefix("/sdcard/"))
            trimmed == "/host-files/projects" || trimmed.startsWith("/host-files/projects/") -> {
                val projectsRoot = File(hostFilesDir, "projects")
                val rel = if (trimmed == "/host-files/projects") "" else trimmed.removePrefix("/host-files/projects/")
                val candidate = File(projectsRoot, rel)
                val rootCanonical = try { projectsRoot.canonicalPath } catch (_: Exception) { projectsRoot.absolutePath }
                val candCanonical = try { candidate.canonicalPath } catch (_: Exception) { candidate.absolutePath }
                val contained = candCanonical == rootCanonical || candCanonical.startsWith(rootCanonical.trimEnd('/') + "/")
                if (contained) candidate else File(rootfsPath, trimmed.removePrefix("/"))
            }
            else -> {
                // V0-c: consult the (V1) binding registry BEFORE the rootfs fallback.
                val bound = BindingRegistry.active.hostTargetFor(trimmed)
                if (bound != null) File(bound)
                else File(rootfsPath, trimmed.removePrefix("/"))
            }
        }
    }

    /**
     * WORKSPACE root → guest (the resolveWorkspacePath dialect, unified):
     * a workspace root the shell must cd into. /root-style guest spellings pass
     * through untouched; everything else goes through [hostToGuest] (which
     * already covers /storage/emulated/0 → /sdcard, filesDir → /host-files, and
     * rootfs paths → /). Returns null when the path is unreachable in the guest
     * (the caller then falls back to the shell default and LOGS it — never a
     * silent wrong directory).
     */
    fun workspaceToGuest(rawWorkspacePath: String, rootfsPath: String, hostFilesDir: String): String? {
        return when {
            rawWorkspacePath.startsWith("/root") -> rawWorkspacePath
            else -> hostToGuest(rawWorkspacePath, rootfsPath, hostFilesDir)
        }
    }
}

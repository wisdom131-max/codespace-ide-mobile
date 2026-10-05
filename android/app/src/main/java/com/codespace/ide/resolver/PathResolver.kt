package com.codespace.ide.resolver

import android.content.Context
import com.codespace.ide.security.TrustState
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.util.CanonicalPaths
import com.codespace.ide.util.ProjectPathResolver
import java.io.File

/**
 * V0 (resolver architecture, 2026-10-05 owner-approved): THE CORE FACADE.
 *
 * Every path/identity question in the app routes through this object; the
 * scattered dialects (ToolPathResolver, ProjectPathResolver, CanonicalPaths,
 * DapPathMapper, ProotInstaller's tables, IdeEnvironment's workspace branch)
 * are either absorbed here (translation tables + host resolution, pure and
 * JVM-tested in [PathDialects]/[HostResolution]) or DELEGATED to from here
 * (project-root discovery stays in ProjectPathResolver — the one source of
 * truth for workspace prefs/metadata; identity/containment stay in
 * CanonicalPaths — the P3b per-file identity core).
 *
 * V1-V8 migrate their surfaces to THESE entry points (thin forwarders keep the
 * legacy objects compiling meanwhile; staging = revertability only).
 */
object PathResolver {

    // ── HOST resolution (V0-a) ──────────────────────────────────────────────

    /** One host-path identity for tool/AI/editor paths. See [HostResolution.toHostPath]. */
    fun toHost(context: Context, raw: String, projectRoot: String? = TrustState.activeProjectRoot(context)): String =
        HostResolution.toHostPath(raw, projectRoot, context.filesDir.absolutePath, context.packageName, ProotInstaller.rootfsDir(context).absolutePath)

    // ── GUEST translation (V0-a) ────────────────────────────────────────────

    /** Host → guest spelling, or null when the path is unreachable inside proot (fail closed). */
    fun toGuest(context: Context, hostPath: String): String? =
        PathDialects.hostToGuest(hostPath, ProotInstaller.rootfsDir(context).absolutePath, context.filesDir.absolutePath)

    /** Guest → host file. Consults the V1 binding registry (V0-c hook) before the rootfs fallback. */
    fun guestToHost(context: Context, guestPath: String): File =
        PathDialects.guestToHost(guestPath, ProotInstaller.rootfsDir(context).absolutePath, context.filesDir.absolutePath)

    /**
     * Workspace root → guest spelling for shell cd / $WORKSPACE_PATH (the unified
     * resolveWorkspacePath dialect). Returns null when unreachable — the caller
     * must fall back to the shell default AND log it, never silently /root.
     */
    fun workspaceToGuest(context: Context, rawWorkspacePath: String): String? =
        PathDialects.workspaceToGuest(rawWorkspacePath, ProotInstaller.rootfsDir(context).absolutePath, context.filesDir.absolutePath)

    // ── IDENTITY (V0-b — P3b per-file identity, re-exported as the ONE door) ─

    /** Canonical identity key for a host-side file path (memoized). */
    fun identity(path: String): String = CanonicalPaths.canonicalKey(path)

    /** TRUE file identity: same string, canonical file, or boundary-suffix dialects. Never basename. */
    fun sameIdentity(a: String, b: String): Boolean = CanonicalPaths.sameFileIdentity(a, b)

    /** TRUE containment: child is root itself or inside it behind a separator boundary. */
    fun isInside(root: File, child: File): Boolean = CanonicalPaths.isInside(root, child)

    /** String-path [isInside]. */
    fun isInside(rootPath: String, childPath: String): Boolean = CanonicalPaths.isInside(rootPath, childPath)

    // ── PROJECT-ROOT discovery (delegates to the existing sources of truth) ─

    /** The ACTIVE project's root as a host path (last-opened project), or null. */
    fun activeProjectRoot(context: Context): String? = TrustState.activeProjectRoot(context)

    /** The project's on-disk root (workspace override > pathOrUrl > legacy), or null (deleted/empty/unknown). */
    fun projectRoot(context: Context, projectId: String?): String? =
        ProjectPathResolver.resolveProjectRoot(context, projectId)

    /** ALL workspace roots for the project (primary first), existing on disk only. */
    fun workspaceRoots(context: Context, projectId: String?): List<String> =
        ProjectPathResolver.getAllWorkspaceRoots(context, projectId)

    /** The workspace root CONTAINING [filePath] (multi-root aware), or the primary root. */
    fun containingProjectRoot(context: Context, projectId: String?, filePath: String?): File? =
        ProjectPathResolver.containingRoot(context, projectId, filePath)
}

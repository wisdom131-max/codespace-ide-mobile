package com.codespace.ide.util

/**
 * BATCH5-P2 (advisor-approved 2026-10-17, with CHANGES): pure path-prefix
 * math for workspace-root removal — the fix for "remove root leaves tabs
 * open" (device FAIL, check 13, run 39022633744). The old gates matched
 * tab path vs root with RAW string equals/startsWith, so ANY path-form
 * divergence (/sdcard vs /storage/emulated/0 alias, a relative tab path, a
 * trailing slash) silently routed Gate 1 to the notify-only branch and left
 * tabs open behind a success toast.
 *
 * ADVISOR RULINGS HONORED:
 *  - NO suffix-match fallback (it can match a path from another root and
 *    close the wrong tab). Only: trailing-slash trim, the
 *    /sdcard <-> /storage/emulated/0 alias, resolving a RELATIVE tab path
 *    against the project root, and exact "equals or startsWith(root + '/')".
 *  - A tab that is also under a root that REMAINS is NOT closed.
 *  - PURE: no file-system calls, no realpath. This helper does not call and
 *    does not change CanonicalPaths / canonical() — it is standalone string
 *    math so it stays JVM-testable without a device.
 *
 * Both gates (ProjectShellScreen Gate 1, EditorPane Gate 2) share the ONE
 * decision function so they can never disagree about who closes what.
 */
object PathUnder {

    /**
     * Normalized comparison form. Pure string math:
     *  1. trim whitespace;
     *  2. map the /sdcard alias onto /storage/emulated/0 (the same volume);
     *  3. trim trailing slashes;
     *  4. resolve a RELATIVE path against [projectRoot] when one is known
     *     (a project-rooted relative tab path belongs to that root).
     */
    fun normalize(path: String, projectRoot: String?): String {
        var p = path.trim()
        if (p == "/sdcard" || p.startsWith("/sdcard/")) {
            p = "/storage/emulated/0" + p.removePrefix("/sdcard")
        }
        p = p.trimEnd('/')
        if (p.isNotEmpty() && !p.startsWith("/") &&
            !projectRoot.isNullOrBlank() && projectRoot.startsWith("/")) {
            p = projectRoot.trimEnd('/') + "/" + p
        }
        return p
    }

    /**
     * Exact "equals or startsWith(root + '/')" after normalization.
     * A root that normalizes to empty matches nothing (fail closed).
     */
    fun isUnder(tabPath: String, rootPath: String, projectRoot: String? = null): Boolean {
        val t = normalize(tabPath, projectRoot)
        val r = normalize(rootPath, null)
        if (r.isEmpty()) return false
        return t == r || t.startsWith(r + "/")
    }

    /** The shared gate decision. Paths in the lists are ORIGINAL tab strings. */
    data class RemoveRootDecision(
        /** Open tabs that ARE under the removed root (normalized match). */
        val matchedTabs: List<String>,
        /** Matched tabs SAFE to close: not also under a REMAINING root. */
        val toCloseTabs: Set<String>,
        /** True when tabs are open but NONE matched — the path-form mismatch case. */
        val unmatchedTabsExist: Boolean,
    )

    /**
     * ONE pure decision for both gates: which open tabs match the removed
     * root, and which of those may be closed (a tab also under a remaining
     * root stays open — advisor ruling).
     */
    fun decideRemoveRoot(
        openTabs: List<String>,
        removedRoot: String,
        remainingRoots: List<String>,
        projectRoot: String?,
    ): RemoveRootDecision {
        val matched = openTabs.filter { isUnder(it, removedRoot, projectRoot) }
        val toClose = matched.filter { tab ->
            remainingRoots.none { r -> isUnder(tab, r, projectRoot) }
        }
        return RemoveRootDecision(
            matchedTabs = matched,
            toCloseTabs = toClose.toSet(),
            unmatchedTabsExist = openTabs.isNotEmpty() && matched.isEmpty(),
        )
    }
}

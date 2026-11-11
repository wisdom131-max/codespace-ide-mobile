package com.codespace.ide.agent

import java.io.File
import java.nio.file.Files

/**
 * Wave 0.1 (owner approval 2026-11-11): exact-text replacement across a workspace.
 *
 * Every write tool before this one touched a single file, so "rename this string everywhere" was
 * N separate calls, each of which could succeed while the next failed, leaving the repository in a
 * state no one asked for. This object does the whole decision first and the writes afterwards.
 *
 * It reads files but never writes them: the executor in AgentTools owns the writing, and EDIT mode
 * never writes at all (ChatSession stages each change through PendingChangesStore instead). That
 * split is what makes dry-run honest - a preview runs the exact same code path as the real thing.
 *
 * Bounded on purpose for cheap Android devices: at most [MAX_FILES] candidate files, at most
 * [MAX_FILE_BYTES] each, and never more than [MAX_TOTAL_BYTES] of changed content held in memory.
 * A request past any bound is REFUSED rather than silently truncated, because a partial replace
 * reported as success is worse than no replace at all.
 */
internal object MultiFileReplace {

    /** Files one operation may touch. Past this the operation refuses; it never processes a subset. */
    const val MAX_FILES = 200

    /** A single file larger than this is skipped with a reason instead of being loaded. */
    const val MAX_FILE_BYTES = 512L * 1024L

    /** Changed content held in memory at once. 16 MB is ~4x the largest single-file write path. */
    const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L

    /** Directories that are never searched: version control and build output. */
    private val SKIP_DIRS = setOf(".git", ".gradle", ".idea", ".cxx", "build", "node_modules")

    data class Target(val file: File, val rel: String)

    data class Skipped(val rel: String, val reason: String)

    /** [updated] is null for a dry run: a preview must not hold 200 files of new text in memory. */
    data class Change(val file: File, val rel: String, val updated: String?, val count: Int)

    data class Plan(
        val changes: List<Change>,
        val skipped: List<Skipped>,
        val total: Int,
        val examined: Int,
        val refused: String?,
    ) {
        val files: Int get() = changes.size
    }

    /**
     * Build the plan. Nothing is written here, ever.
     *
     * [filter] is optional: blank means the whole workspace, a value containing `*` is a glob over
     * the relative path, and anything else matches a path prefix or filename substring.
     */
    fun plan(
        root: File,
        search: String,
        replace: String,
        filter: String = "",
        dryRun: Boolean = false,
        maxFiles: Int = MAX_FILES,
    ): Plan {
        if (search.isEmpty()) return refused("search is empty. Pass the exact text to find.")
        if (search == replace) return refused("search and replace are identical, so nothing would change.")
        if (!root.isDirectory) return refused("no project root is open at " + root.path)

        val rootPath = try {
            root.canonicalFile
        } catch (t: Throwable) {
            return refused("could not resolve the project root: " + (t.message ?: "error"))
        }
        val changes = ArrayList<Change>()
        val skipped = ArrayList<Skipped>()
        var examined = 0
        var retainedBytes = 0L

        val candidates = collectCandidates(rootPath, filter, skipped)
            ?: return refused(
                "more than " + maxFiles + " files match this selection, so nothing was changed. " +
                    "Narrow it with paths (a directory, a filename, or a glob) and run again.",
            )

        for (target in candidates) {
            examined++
            val text = try {
                target.file.readText()
            } catch (t: Throwable) {
                skipped.add(Skipped(target.rel, "could not be read as text: " + (t.message ?: "error")))
                continue
            }
            val count = ReplaceText.countOf(text, search)
            if (count == 0) continue
            var updated: String? = null
            if (!dryRun) {
                val projected = text.length + count * (replace.length - search.length).coerceAtLeast(0)
                retainedBytes += projected
                if (retainedBytes > MAX_TOTAL_BYTES) {
                    return refused(
                        "the changed content would exceed " + (MAX_TOTAL_BYTES / (1024 * 1024)) +
                            " MB in memory, so nothing was changed. Narrow the selection.",
                    )
                }
                updated = text.replace(search, replace)
            }
            changes.add(Change(target.file, target.rel, updated, count))
        }

        val total = changes.sumOf { it.count }
        return Plan(changes, skipped, total, examined, null)
    }

    /** Candidate files in a stable order, or null when the selection exceeds [MAX_FILES]. */
    private fun collectCandidates(
        root: File,
        filter: String,
        skipped: MutableList<Skipped>,
    ): List<Target>? {
        val glob = globRegex(filter)
        val out = ArrayList<Target>()
        val walk = root.walkTopDown().onEnter { dir ->
            val name = dir.name
            if (dir != root && (name in SKIP_DIRS || isSymlink(dir))) {
                if (isSymlink(dir)) skipped.add(Skipped(rel(root, dir), "symlink (not followed)"))
                false
            } else true
        }
        for (f in walk) {
            if (!f.isFile) continue
            val r = rel(root, f)
            if (isSymlink(f)) {
                skipped.add(Skipped(r, "symlink (not followed)"))
                continue
            }
            if (!withinRoot(root, f)) {
                skipped.add(Skipped(r, "outside the workspace"))
                continue
            }
            if (!matchesFilter(r, filter, glob)) continue
            if (f.length() > MAX_FILE_BYTES) {
                skipped.add(Skipped(r, "larger than " + (MAX_FILE_BYTES / 1024) + " KB"))
                continue
            }
            if (looksBinary(f)) {
                skipped.add(Skipped(r, "binary"))
                continue
            }
            out.add(Target(f, r))
            if (out.size > MAX_FILES) return null
        }
        return out.sortedBy { it.rel }
    }

    /** NUL byte in the first 8 KB is the same heuristic git uses, and it never needs a full read. */
    fun looksBinary(file: File): Boolean = try {
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            val read = input.read(buf)
            var hasNul = false
            var i = 0
            while (i < read) {
                if (buf[i] == 0.toByte()) { hasNul = true; break }
                i++
            }
            hasNul
        }
    } catch (t: Throwable) {
        true
    }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (t: Throwable) {
        false
    }

    private fun withinRoot(root: File, f: File): Boolean = try {
        val p = f.canonicalPath
        p == root.canonicalPath || p.startsWith(root.canonicalPath + File.separator)
    } catch (t: Throwable) {
        false
    }

    /**
     * Workspace-relative name. Deliberately NOT canonical: a symlink canonicalises to its target,
     * which is outside the workspace, and a skip reason must still name the entry the user sees.
     */
    private fun rel(root: File, f: File): String {
        val base = root.absolutePath
        val p = f.absolutePath
        return if (p.startsWith(base)) p.removePrefix(base).trimStart(File.separatorChar) else f.name
    }

    internal fun globRegex(filter: String): Regex? {
        val f = filter.trim()
        if (f.isEmpty() || !f.contains('*')) return null
        val sb = StringBuilder()
        var i = 0
        while (i < f.length) {
            when (val c = f[i]) {
                '*' -> {
                    if (i + 1 < f.length && f[i + 1] == '*') {
                        sb.append(".*"); i++
                    } else sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        val body = sb.toString()
        // A bare "*.kt" must match at any depth, the way a person means it.
        val pattern = if (body.startsWith(".*/")) body else "(?:.*/)?" + body
        return try {
            Regex("^" + pattern + "$")
        } catch (t: Throwable) {
            null
        }
    }

    private fun matchesFilter(relPath: String, filter: String, glob: Regex?): Boolean {
        val f = filter.trim()
        if (f.isEmpty()) return true
        if (glob != null) return glob.matches(relPath)
        val needle = f.trimEnd('/')
        return relPath == needle || relPath.startsWith(needle + "/") ||
            relPath.substringAfterLast('/').contains(needle, ignoreCase = true) ||
            relPath.contains(needle, ignoreCase = true)
    }

    private fun refused(why: String) = Plan(emptyList(), emptyList(), 0, 0, why)

    /** The honest no-op: it says what was searched and that nothing matched. */
    fun noMatches(plan: Plan, filter: String): String {
        val where = if (filter.isBlank()) "the workspace" else "'" + filter + "'"
        return "No matches in " + where + ". Nothing was changed (" + plan.examined + " file(s) searched" +
            (if (plan.skipped.isEmpty()) ")" else ", " + plan.skipped.size + " skipped)")
    }

    /**
     * The result text. Every affected file and count is listed: an operation that reports "done"
     * over a truncated list is indistinguishable from one that quietly missed a file.
     */
    fun report(plan: Plan, dryRun: Boolean): String {
        if (plan.changes.isEmpty()) return noMatches(plan, "")
        val sb = StringBuilder()
        if (dryRun) {
            sb.append("DRY RUN - nothing was written. ")
        }
        sb.append(if (dryRun) "Would replace " else "Replaced ")
        sb.append(plan.total).append(" occurrence(s) in ").append(plan.files).append(" file(s)")
        sb.append(" (").append(plan.examined).append(" examined)")
        sb.append('\n')
        for (c in plan.changes) {
            sb.append("  ").append(c.rel).append(": ").append(c.count).append('\n')
        }
        if (plan.skipped.isNotEmpty()) {
            sb.append("Skipped ").append(plan.skipped.size).append(" file(s): ")
            sb.append(plan.skipped.joinToString(", ") { it.rel + " (" + it.reason + ")" })
        }
        return sb.toString().trimEnd()
    }
}

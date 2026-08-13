package com.codespace.ide.scm

import android.content.Context
import com.codespace.ide.data.SecureTokenStore

/**
 * GitService — high-level git operations returning structured domain models.
 *
 * Sits between GitCommandExecutor (raw git execution) and the UI/ViewModel.
 * Each method runs the appropriate git command(s) and parses the output into
 * ScmRepoState, ScmCommit, ScmBranch, ScmFileDiff, etc.
 *
 * Phase SCM-4: GitService. Pure business logic, no Compose/UI dependencies.
 *
 * Thread safety: all methods suspend and run on Dispatchers.IO via
 * ProotInstaller.execOnce's internal thread management. Callers are
 * responsible for coroutine scoping.
 */
class GitService(private val context: Context) {

    private val tokenStore = SecureTokenStore(context)
    private val token: String? get() = tokenStore.githubToken

    // ── Repository detection ──────────────────────────────────────────────

    /**
     * Check if a directory is a git repository.
     * @param workdir guest-side path (e.g. "/root/myproject")
     */
    fun isRepo(workdir: String): Boolean {
        val result = GitCommandExecutor.run(context, listOf("rev-parse", "--is-inside-work-tree"), workdir)
        return result is GitResult.Ok && result.output.trim() == "true"
    }

    /**
     * Get the repository root for a given path.
     * Returns the guest-side path to the repo root, or null if not in a repo.
     */
    fun repoRoot(workdir: String): String? {
        val result = GitCommandExecutor.run(context, listOf("rev-parse", "--show-toplevel"), workdir)
        return if (result is GitResult.Ok) result.output.trim().ifBlank { null } else null
    }

    // ── Status ───────────────────────────────────────────────────────────

    /**
     * Get full repository status.
     * @param workdir guest-side path to the repository
     * @return ScmRepoState or null if not a repo
     */
    fun status(workdir: String): ScmRepoState? {
        // Branch + upstream + ahead/behind
        val branchResult = GitCommandExecutor.run(context, listOf(
            "rev-parse", "--abbrev-ref", "--symbolic-full-name", "HEAD"
        ), workdir)
        val branch = if (branchResult is GitResult.Ok) branchResult.output.trim() else "(unknown)"

        // Check for detached HEAD
        val isDetached = branch == "HEAD"

        // Upstream tracking
        val upstreamResult = GitCommandExecutor.run(context, listOf(
            "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"
        ), workdir)
        val upstream = if (upstreamResult is GitResult.Ok) upstreamResult.output.trim() else null

        // Ahead/behind counts
        var ahead = 0
        var behind = 0
        if (upstream != null) {
            val countResult = GitCommandExecutor.run(context, listOf(
                "rev-list", "--left-right", "--count", "$upstream...HEAD"
            ), workdir)
            if (countResult is GitResult.Ok) {
                val parts = countResult.output.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    behind = parts[0].toIntOrNull() ?: 0
                    ahead = parts[1].toIntOrNull() ?: 0
                }
            }
        }

        // HEAD commit hash
        val headResult = GitCommandExecutor.run(context, listOf("rev-parse", "--short", "HEAD"), workdir)
        val headCommit = if (headResult is GitResult.Ok) headResult.output.trim().ifBlank { null } else null

        // File statuses via porcelain
        val statusResult = GitCommandExecutor.run(context, listOf(
            "status", "--porcelain"
        ), workdir)

        val staged = mutableListOf<ScmFileStatus>()
        val unstaged = mutableListOf<ScmFileStatus>()
        val untracked = mutableListOf<ScmFileStatus>()
        val conflicted = mutableListOf<ScmFileStatus>()

        if (statusResult is GitResult.Ok) {
            for (line in statusResult.lines) {
                val fs = ScmFileStatus.parse(line) ?: continue
                when {
                    fs.isConflicted -> conflicted.add(fs)
                    fs.isUntracked -> untracked.add(fs)
                    fs.isStaged -> staged.add(fs)
                    else -> unstaged.add(fs)
                }
            }
        }

        return ScmRepoState(
            branch = if (isDetached) "(detached HEAD)" else branch,
            ahead = ahead,
            behind = behind,
            upstream = upstream,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicted = conflicted,
            isDetached = isDetached,
            headCommit = headCommit,
        )
    }

    // ── Staging ──────────────────────────────────────────────────────────

    /**
     * Stage files (git add).
     * @param files list of file paths relative to repo root
     * @param workdir guest-side path to the repository
     */
    fun add(files: List<String>, workdir: String): GitResult {
        if (files.isEmpty()) return GitResult.Ok("")
        return GitCommandExecutor.run(
            context,
            listOf("add") + files,
            workdir,
            timeoutSeconds = 30,
        )
    }

    /**
     * Stage all changes (git add -A).
     */
    fun addAll(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("add", "-A"), workdir, timeoutSeconds = 30)
    }

    /**
     * Unstage files (git reset HEAD <files>).
     */
    fun unstage(files: List<String>, workdir: String): GitResult {
        if (files.isEmpty()) return GitResult.Ok("")
        return GitCommandExecutor.run(
            context,
            listOf("reset", "HEAD") + files,
            workdir,
            timeoutSeconds = 30,
        )
    }

    // ── Commit ───────────────────────────────────────────────────────────

    /**
     * Commit staged changes.
     * @param message commit message
     * @param workdir guest-side path to the repository
     */
    fun commit(message: String, workdir: String): GitResult {
        return GitCommandExecutor.run(
            context,
            listOf("commit", "-m", message),
            workdir,
            timeoutSeconds = 30,
        )
    }

    /**
     * Stage all and commit in one step.
     */
    fun addAllAndCommit(message: String, workdir: String): GitResult {
        val addResult = addAll(workdir)
        if (addResult is GitResult.Err) return addResult
        return commit(message, workdir)
    }

    // ── Push / Pull ──────────────────────────────────────────────────────

    /**
     * Push current branch to its upstream.
     * If no upstream is set, sets it with --set-upstream.
     */
    fun push(workdir: String): GitResult {
        // First check if upstream exists
        val state = status(workdir)
        if (state == null) return GitResult.Err(GitError.NotARepo(workdir))
        if (state.isDetached) return GitResult.Err(GitError.Unknown("Cannot push in detached HEAD state"))

        val args = if (state.upstream == null) {
            // Set upstream on first push
            listOf("push", "-u", "origin", state.branch)
        } else {
            listOf("push")
        }

        return GitCommandExecutor.run(
            context,
            args,
            workdir,
            timeoutSeconds = 120,
            token = token,
        )
    }

    /**
     * Pull from upstream, merging into current branch.
     */
    fun pull(workdir: String): GitResult {
        return GitCommandExecutor.run(
            context,
            listOf("pull"),
            workdir,
            timeoutSeconds = 120,
            token = token,
        )
    }

    /**
     * Fetch from remote without merging.
     */
    fun fetch(workdir: String, remote: String = "origin"): GitResult {
        return GitCommandExecutor.run(
            context,
            listOf("fetch", remote),
            workdir,
            timeoutSeconds = 120,
            token = token,
        )
    }

    // ── Branch operations ────────────────────────────────────────────────

    /**
     * List all branches (local and remote).
     */
    fun branches(workdir: String): List<ScmBranch> {
        val result = GitCommandExecutor.run(context, listOf(
            "branch", "-a", "--format=%(refname:short)|%(objectname)|%(upstream:short)"
        ), workdir)
        if (result !is GitResult.Ok) return emptyList()

        val currentBranch = run {
            val r = GitCommandExecutor.run(context, listOf("rev-parse", "--abbrev-ref", "HEAD"), workdir)
            if (r is GitResult.Ok) r.output.trim() else ""
        }

        return result.lines.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            val name = parts[0].trim()
            val upstream = parts.getOrNull(2)?.trim()?.ifBlank { null }
            val isRemote = name.startsWith("origin/") || name.contains("/")
            ScmBranch(
                name = name,
                isCurrent = name == currentBranch,
                isRemote = isRemote,
                upstream = upstream,
            )
        }
    }

    /**
     * Checkout an existing branch.
     */
    fun checkout(branch: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("checkout", branch), workdir, timeoutSeconds = 30)
    }

    /**
     * Create and checkout a new branch.
     */
    fun createBranch(name: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("checkout", "-b", name), workdir, timeoutSeconds = 30)
    }

    // ── Log / History ─────────────────────────────────────────────────────

    /**
     * Get commit history.
     * @param workdir guest-side path to repository
     * @param maxCount max number of commits to return (default 50)
     * @param file optional file path to filter history (git log --follow -- <file>)
     */
    fun log(workdir: String, maxCount: Int = 50, file: String? = null): List<ScmCommit> {
        val format = "--format=%H|%an|%ar|%s"
        val baseArgs = if (file != null) {
            listOf("log", "--follow", format, "-$maxCount", "--", file)
        } else {
            listOf("log", format, "-$maxCount")
        }

        val result = GitCommandExecutor.run(context, baseArgs, workdir, timeoutSeconds = 30)
        if (result !is GitResult.Ok) return emptyList()

        return result.lines.mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            ScmCommit(
                hash = parts[0].trim(),
                author = parts[1].trim(),
                date = parts[2].trim(),
                message = parts[3].trim(),
                isHead = false, // Could check if hash matches HEAD
            )
        }
    }

    // ── Diff ──────────────────────────────────────────────────────────────

    /**
     * Get diff for a file (unstaged changes vs index).
     * @param path file path relative to repo root
     * @param workdir guest-side path to repository
     */
    fun diffFile(path: String, workdir: String): ScmFileDiff {
        val result = GitCommandExecutor.run(context, listOf(
            "diff", "--unified=3", "--", path
        ), workdir, timeoutSeconds = 30)

        if (result !is GitResult.Ok) {
            return ScmFileDiff(path = path, hunks = emptyList())
        }

        return parseUnifiedDiff(path, result.output)
    }

    /**
     * Get diff of staged changes (index vs HEAD).
     */
    fun diffStaged(path: String, workdir: String): ScmFileDiff {
        val result = GitCommandExecutor.run(context, listOf(
            "diff", "--cached", "--unified=3", "--", path
        ), workdir, timeoutSeconds = 30)

        if (result !is GitResult.Ok) {
            return ScmFileDiff(path = path, hunks = emptyList())
        }

        return parseUnifiedDiff(path, result.output)
    }

    // ── Conflict detection ────────────────────────────────────────────────

    /**
     * Check if there are any merge conflicts in the repo.
     */
    fun hasConflicts(workdir: String): Boolean {
        val state = status(workdir) ?: return false
        return state.conflicted.isNotEmpty()
    }

    /**
     * List files with merge conflicts.
     */
    fun conflictedFiles(workdir: String): List<String> {
        val state = status(workdir) ?: return emptyList()
        return state.conflicted.map { it.path }
    }

    // ── Init / Remote ─────────────────────────────────────────────────────

    /**
     * Initialize a new git repository.
     */
    fun init(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("init"), workdir, timeoutSeconds = 15)
    }

    /**
     * Add a remote.
     */
    fun addRemote(name: String, url: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("remote", "add", name, url), workdir, timeoutSeconds = 15)
    }

    /**
     * Get the current remote URL for a given remote name (default: origin).
     */
    fun remoteUrl(name: String = "origin", workdir: String): String? {
        val result = GitCommandExecutor.run(context, listOf("remote", "get-url", name), workdir)
        return if (result is GitResult.Ok) result.output.trim().ifBlank { null } else null
    }

    // ── Stash ─────────────────────────────────────────────────────────────

    /**
     * Stash current changes.
     */
    fun stash(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("stash"), workdir, timeoutSeconds = 30)
    }

    /**
     * Pop the most recent stash.
     */
    fun stashPop(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("stash", "pop"), workdir, timeoutSeconds = 30)
    }

    // ── Diff Parsing ──────────────────────────────────────────────────────

    private fun parseUnifiedDiff(path: String, raw: String): ScmFileDiff {
        val hunks = mutableListOf<ScmDiffHunk>()
        var currentHunkLines = mutableListOf<ScmDiffLine>()
        var oldStart = 0
        var oldCount = 0
        var newStart = 0
        var newCount = 0
        var oldLine = 0
        var newLine = 0
        var isRenamed = false
        var oldPath: String? = null

        for (line in raw.lines()) {
            // Hunk header: @@ -oldStart,oldCount +newStart,newCount @@
            val hunkRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
            hunkRegex.find(line)?.let { m ->
                // Save previous hunk
                if (currentHunkLines.isNotEmpty()) {
                    hunks.add(ScmDiffHunk(oldStart, oldCount, newStart, newCount, currentHunkLines))
                    currentHunkLines = mutableListOf()
                }
                oldStart = m.groupValues[1].toIntOrNull() ?: 0
                oldCount = m.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: 1
                newStart = m.groupValues[3].toIntOrNull() ?: 0
                newCount = m.groupValues[4].ifBlank { "1" }.toIntOrNull() ?: 1
                oldLine = oldStart
                newLine = newStart
                currentHunkLines.add(ScmDiffLine(DiffLineType.HUNK_HEADER, line, null, null))
                return@let
            }

            // Diff header lines
            if (line.startsWith("diff --git")) continue  // skip
            if (line.startsWith("--- ")) {
                if (line != "--- /dev/null") oldPath = line.removePrefix("--- ").removePrefix("b/")
                continue
            }
            if (line.startsWith("+++ ")) continue
            if (line.startsWith("rename from") || line.startsWith("rename to")) {
                isRenamed = true
                continue
            }
            if (line.startsWith("old mode") || line.startsWith("new mode")) continue
            if (line.startsWith("index ")) continue
            if (line.startsWith("Binary files")) continue

            // Diff content
            when {
                line.startsWith("@@") -> {} // already handled above
                line.startsWith("+") -> {
                    currentHunkLines.add(ScmDiffLine(
                        DiffLineType.ADDED, line.substring(1), null, newLine
                    ))
                    newLine++
                }
                line.startsWith("-") -> {
                    currentHunkLines.add(ScmDiffLine(
                        DiffLineType.DELETED, line.substring(1), oldLine, null
                    ))
                    oldLine++
                }
                line.startsWith(" ") || line.isEmpty() -> {
                    val content = if (line.isEmpty()) "" else line.substring(1)
                    currentHunkLines.add(ScmDiffLine(
                        DiffLineType.CONTEXT, content, oldLine, newLine
                    ))
                    oldLine++
                    newLine++
                }
            }
        }

        // Don't forget the last hunk
        if (currentHunkLines.isNotEmpty()) {
            hunks.add(ScmDiffHunk(oldStart, oldCount, newStart, newCount, currentHunkLines))
        }

        return ScmFileDiff(
            path = path,
            hunks = hunks,
            isRenamed = isRenamed,
            oldPath = oldPath,
        )
    }
}

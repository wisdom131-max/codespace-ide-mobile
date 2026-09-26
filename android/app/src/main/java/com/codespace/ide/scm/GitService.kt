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

    /**
     * Resolve the git repo root for [workdir], falling back to [workdir] itself
     * if the root can't be determined.
     *
     * CRITICAL: `git status --porcelain` always reports paths relative to the
     * repo ROOT, regardless of which subdirectory it's invoked from. But
     * pathspec-based commands (add, reset, diff -- <path>, blame <path>) resolve
     * their path arguments relative to the CURRENT WORKING DIRECTORY, not the
     * repo root. If the app's "current project" is a subdirectory of the actual
     * git repo (e.g. multiple projects share one outer .git), running
     * `git add "ProjectName/file.txt"` from inside "ProjectName" fails with
     * "did not match any files" because git looks for "ProjectName/ProjectName/file.txt".
     * Any function that takes a repo-root-relative path (as reported by status())
     * MUST execute from the repo root, not the raw project workdir.
     */
    private fun rootFor(workdir: String): String = repoRoot(workdir) ?: workdir

    // ── Status ───────────────────────────────────────────────────────────

    /**
     * Get full repository status.
     * @param workdir guest-side path to the repository
     * @return ScmRepoState or null if not a repo
     */
    fun status(workdir: String): ScmRepoState? {
        // SG07 (P4g): ONE `status --porcelain -b` call now carries branch, upstream,
        // ahead/behind AND file statuses — the old implementation ran SIX proot
        // spawns per refresh (branch + upstream + rev-list + HEAD + porcelain, on
        // top of the caller's isRepo = 7 total, multi-second on-device), which is
        // also why auto-refresh (SG06) was never viable. --no-optional-locks: the
        // status call must never write the index, so the SG06 FileObserver never
        // reacts to our own refreshes and a refresh can never block a concurrent
        // stage/commit on .git/index.lock.
        val statusResult = GitCommandExecutor.run(context, listOf(
            "--no-optional-locks", "status", "--porcelain", "-b"
        ), workdir, timeoutSeconds = 30)
        if (statusResult !is GitResult.Ok) return null  // not a repo / git error → honest null

        // ── Branch header ("## ...", always the first line) ──
        // Shapes: "## main...origin/main [ahead 1, behind 2]" / "## main" /
        // "## HEAD (no branch)" / "## No commits yet on main".
        var branch = "(unknown)"
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var isDetached = false
        val header = statusResult.output.lines().firstOrNull { it.startsWith("## ") } ?: ""
        if (header.isNotEmpty()) {
            val body = header.removePrefix("## ")
            isDetached = body.startsWith("HEAD (no branch)")
            if (isDetached) {
                branch = "(detached HEAD)"
            } else if (body.startsWith("No commits yet on ")) {
                // Can ALSO carry tracking: "No commits yet on main...origin/main [gone]"
                val rest = body.removePrefix("No commits yet on ")
                val bStart = rest.indexOf(" [")
                val tracking = if (bStart >= 0) rest.substring(0, bStart) else rest
                val d = tracking.indexOf("...")
                branch = if (d >= 0) tracking.substring(0, d).trim() else tracking.trim()
            } else {
                val bracketStart = body.indexOf(" [")
                val trackingPart = if (bracketStart >= 0) body.substring(0, bracketStart) else body
                val bracket = if (bracketStart >= 0) body.substring(bracketStart + 2, body.length - 1) else ""
                val dots = trackingPart.indexOf("...")
                if (dots >= 0) {
                    branch = trackingPart.substring(0, dots).trim()
                    upstream = trackingPart.substring(dots + 3).trim().ifBlank { null }
                } else {
                    branch = trackingPart.trim()
                }
                Regex("""\bahead (\d+)""").find(bracket)?.let { ahead = it.groupValues[1].toIntOrNull() ?: 0 }
                Regex("""\bbehind (\d+)""").find(bracket)?.let { behind = it.groupValues[1].toIntOrNull() ?: 0 }
            }
        }

        // HEAD commit hash — the one field porcelain does not carry (HistoryDialog
        // HEAD badge + shell display); the single remaining extra spawn.
        val headResult = GitCommandExecutor.run(context, listOf("rev-parse", "--short", "HEAD"), workdir)
        val headCommit = if (headResult is GitResult.Ok) headResult.output.trim().ifBlank { null } else null

        val staged = mutableListOf<ScmFileStatus>()
        val unstaged = mutableListOf<ScmFileStatus>()
        val untracked = mutableListOf<ScmFileStatus>()
        val conflicted = mutableListOf<ScmFileStatus>()

        // File statuses — the same v1 "XY path" records ScmFileStatus.parse has
        // always consumed; the "## " header line is skipped.
        if (statusResult is GitResult.Ok) {
            for (line in statusResult.lines) {
                if (line.startsWith("## ")) continue
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
            rootFor(workdir),
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
            listOf("reset", "HEAD", "--") + files,
            rootFor(workdir),
            timeoutSeconds = 30,
        )
    }

    /**
     * Discard changes to a file — reverts it to its last committed state.
     * - Tracked file with modifications: `git checkout -- <path>` (restores HEAD version).
     * - Untracked file (never committed): deletes it from disk via `git clean -f -- <path>`.
     * Runs from the repo root (see [rootFor]) since [path] is repo-root-relative.
     */
    fun discardFile(path: String, isUntracked: Boolean, workdir: String): GitResult {
        val root = rootFor(workdir)
        return if (isUntracked) {
            GitCommandExecutor.run(context, listOf("clean", "-f", "--", path), root, timeoutSeconds = 30)
        } else {
            GitCommandExecutor.run(context, listOf("checkout", "--", path), root, timeoutSeconds = 30)
        }
    }

    // ── Commit ───────────────────────────────────────────────────────────

    /**
     * Commit staged changes.
     * @param message commit message
     * @param workdir guest-side path to the repository
     */
    fun commit(message: String, workdir: String): GitResult {
        // SG14 (P4g): NO fabricated identity. If user.name/user.email are unset —
        // or still hold the old silently-fabricated defaults — the SIGNED-IN
        // account provides the real author (configured once, in place); not
        // signed in → the commit fails honestly instead of misattributing it to
        // a "VN Code User" that persists in git config forever.
        when (val id = identityToConfigure(workdir)) {
            is IdentityCheck.Configured -> {}
            is IdentityCheck.Apply -> {
                GitCommandExecutor.run(context, listOf("config", "user.name", id.name), workdir)
                GitCommandExecutor.run(context, listOf("config", "user.email", id.email), workdir)
            }
            null -> return GitResult.Err(GitError.Unknown(
                "Git identity not configured. Sign in (Settings) or run in the terminal: git config user.email you@example.com"))
        }
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
            // SG05: caller-supplied token wins (URL may embed its own — SG16 path);
            // otherwise fall back to the stored GitHub token so CloneDialog
            // private clones get auth injection.
            token = token ?: this.token,
        )
    }

    /**
     * Pull from upstream, merging into current branch.
     */
    fun pull(workdir: String): GitResult {
        // SG13 (P4g): --ff-only — plain `git pull` silently created surprise merge
        // commits on diverged branches. Pull now fast-forwards or fails honestly;
        // diverged branches are the explicit Merge/Rebase buttons' job.
        return GitCommandExecutor.run(
            context,
            listOf("pull", "--ff-only"),
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
        // SG09 (P4g): refname:short collapses to "origin/feature/x", and the old
        // `startsWith("origin/") || contains("/")` classified ANY local branch
        // with a slash (feature/xyz, hotfix/abc) as a remote. The FULL refname is
        // the actual discriminator — refs/remotes/ — remote-name agnostic.
        val result = GitCommandExecutor.run(context, listOf(
            "branch", "-a", "--format=%(refname)|%(refname:short)|%(objectname)|%(upstream:short)"
        ), workdir)
        if (result !is GitResult.Ok) return emptyList()

        val currentBranch = run {
            val r = GitCommandExecutor.run(context, listOf("rev-parse", "--abbrev-ref", "HEAD"), workdir)
            if (r is GitResult.Ok) r.output.trim() else ""
        }

        return result.lines.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            val fullRef = parts[0].trim()
            val name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: fullRef
            val upstream = parts.getOrNull(3)?.trim()?.ifBlank { null }
            ScmBranch(
                name = name,
                isCurrent = name == currentBranch,
                isRemote = fullRef.startsWith("refs/remotes/"),  // SG09 (P4g)
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

    fun deleteBranch(name: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("branch", "-d", name), workdir, timeoutSeconds = 15)
    }

    fun renameBranch(oldName: String, newName: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("branch", "-m", oldName, newName), workdir, timeoutSeconds = 15)
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

        // If a specific file path is given, it's repo-root-relative (from status()),
        // so this must run from the repo root — same pathspec-mismatch fix as add/unstage.
        val execDir = if (file != null) rootFor(workdir) else workdir
        val result = GitCommandExecutor.run(context, baseArgs, execDir, timeoutSeconds = 30)
        if (result !is GitResult.Ok) return emptyList()

        val commits = result.lines.mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            ScmCommit(
                hash = parts[0].trim(),
                author = parts[1].trim(),
                date = parts[2].trim(),
                message = parts[3].trim(),
                isHead = false,
            )
        }
        // SG13 (P4g): git log starts FROM HEAD, so with no file filter the first
        // listed commit IS HEAD — the HistoryDialog badge was dead (hard-coded
        // false since the dialog was written). With --follow (file filter) the
        // first entry is the file's latest change, NOT HEAD — badge stays false.
        return if (file == null && commits.isNotEmpty()) {
            commits.toMutableList().also { it[0] = commits[0].copy(isHead = true) }
        } else commits
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
        ), rootFor(workdir), timeoutSeconds = 30)

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
        ), rootFor(workdir), timeoutSeconds = 30)

        if (result !is GitResult.Ok) {
            return ScmFileDiff(path = path, hunks = emptyList())
        }

        return parseUnifiedDiff(path, result.output)
    }

    /**
     * SG08 (P4g): diff of one file AT a given commit — the timeline git rows
     * were inert (no tap action at all); this makes a row show what the commit
     * changed in THIS file (`git show <hash> -- <path>`). The commit header
     * (Author:/Date:/message) is sliced off before the shared parser — only
     * the `diff --git` tail is a unified diff; a file untouched by the commit
     * (or renamed away) yields an honest empty diff.
     */
    fun showFileAtCommit(hash: String, path: String, workdir: String): ScmFileDiff {
        val result = GitCommandExecutor.run(context, listOf(
            "show", "--unified=3", hash, "--", path
        ), rootFor(workdir), timeoutSeconds = 30)
        if (result !is GitResult.Ok) return ScmFileDiff(path = path, hunks = emptyList())
        val raw = result.output
        val idx = raw.indexOf("diff --git")
        if (idx < 0) return ScmFileDiff(path = path, hunks = emptyList())
        return parseUnifiedDiff(path, raw.substring(idx))
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

    // ── Merge / Rebase ─────────────────────────────────────────────────────

    /**
     * Merge a branch into the current branch.
     */
    fun merge(branch: String, workdir: String): GitResult {
        val result = GitCommandExecutor.run(context, listOf("merge", branch), workdir, timeoutSeconds = 120, token = token)
        return result
    }

    /**
     * Abort an in-progress merge.
     */
    fun abortMerge(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("merge", "--abort"), workdir, token = token)
    }

    /**
     * Rebase onto a branch.
     */
    fun rebase(branch: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("rebase", branch), workdir, timeoutSeconds = 120, token = token)
    }

    /**
     * Abort an in-progress rebase.
     */
    fun abortRebase(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("rebase", "--abort"), workdir)
    }

    /**
     * Continue a rebase after resolving conflicts.
     */
    fun continueRebase(workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("rebase", "--continue"), workdir, token = token)
    }

    /**
     * Mark a conflicted file as resolved (git add).
     * Same as stageFiles but named for clarity in conflict resolution flow.
     */
    fun resolveConflict(filePath: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("add", "--", filePath), rootFor(workdir))
    }

    // ── Tags ──────────────────────────────────────────────────────────────

    /**
     * List all tags.
     */
    fun tags(workdir: String): List<String> {
        val result = GitCommandExecutor.run(context, listOf("tag", "--list"), workdir)
        return if (result is GitResult.Ok) result.lines else emptyList()
    }

    /**
     * Create a tag.
     */
    fun createTag(name: String, message: String? = null, workdir: String): GitResult {
        val args = if (message != null) listOf("tag", "-a", name, "-m", message) else listOf("tag", name)
        return GitCommandExecutor.run(context, args, workdir)
    }

    /**
     * Delete a tag.
     */
    fun deleteTag(name: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("tag", "-d", name), workdir)
    }

    // ── Remotes ────────────────────────────────────────────────────────────

    /**
     * List all remotes (name + URL).
     */
    fun remotes(workdir: String): List<Pair<String, String>> {
        val result = GitCommandExecutor.run(context, listOf("remote", "-v"), workdir)
        if (result !is GitResult.Ok) return emptyList()
        return result.lines.mapNotNull { line ->
            // Format: "origin	git@github.com:user/repo.git (fetch)"
            val parts = line.split("\t")
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val url = parts[1].trim().removeSuffix(" (fetch)").removeSuffix(" (push)")
                Pair(name, url)
            } else null
        }.distinctBy { it.first }
    }

    /**
     * Remove a remote.
     */
    fun removeRemote(name: String, workdir: String): GitResult {
        return GitCommandExecutor.run(context, listOf("remote", "remove", name), workdir)
    }

    // ── Init / Remote ─────────────────────────────────────────────────────

    /**
     * Initialize a new git repository.
     */
    fun init(workdir: String): GitResult {
        // SG14 (P4g): init no longer pre-writes an identity — commit() decides the
        // author honestly at commit time (signed-in account, or typed failure).
        return GitCommandExecutor.run(context, listOf("init"), workdir, timeoutSeconds = 15)
    }

    // ── SG14 (P4g): honest commit identity ──────────────────────────────

    /** SG14 (P4g): outcome of the commit-time identity check. */
    private sealed class IdentityCheck {
        object Configured : IdentityCheck()
        data class Apply(val name: String, val email: String) : IdentityCheck()
    }

    /** The old silent fabrication (SG14 removed it; still detected + upgraded). */
    private val fabricatedIdentityName = "VN Code User"
    private val fabricatedIdentityEmail = "user@codespace.local"

    /**
     * SG14 (P4g): decide the commit identity honestly.
     * - real identity already configured → leave it untouched (Configured)
     * - unset OR holding the old fabricated defaults → apply the signed-in
     *   account's identity (Apply) — the same in-place upgrade pattern as SK04's
     *   legacy PIN migration
     * - not signed in (or no email on the account) → null → the caller fails
     *   with instructions instead of inventing an author.
     */
    private fun identityToConfigure(workdir: String): IdentityCheck? {
        val name = configValue("user.name", workdir)
        val email = configValue("user.email", workdir)
        val needsApply = name.isBlank() || email.isBlank() ||
            name == fabricatedIdentityName || email == fabricatedIdentityEmail
        if (!needsApply) return IdentityCheck.Configured
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return null
        val accountEmail = user.email ?: return null
        val accountName = user.displayName?.takeIf { it.isNotBlank() } ?: accountEmail.substringBefore("@")
        return IdentityCheck.Apply(accountName, accountEmail)
    }

    private fun configValue(key: String, workdir: String): String {
        val r = GitCommandExecutor.run(context, listOf("config", key), workdir)
        return if (r is GitResult.Ok) r.output.trim() else ""
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
                // SG11 (P4g): the --- header carries the a/ prefix (not b/) —
                // oldPath kept a bogus "a/" on every renamed diff.
                if (line != "--- /dev/null") oldPath = line.removePrefix("--- ").removePrefix("a/")
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

    // ── Branch Graph ───────────────────────────────────────────────────────

    /**
     * Get ASCII branch graph (git log --graph --oneline).
     * @param workdir guest-side path to repository
     * @param maxCount max commits to show (default 100)
     */
    fun graphLog(workdir: String, maxCount: Int = 100): String {
        val result = GitCommandExecutor.run(
            context,
            listOf("log", "--graph", "--oneline", "--all", "--decorate", "-n", maxCount.toString()),
            workdir,
            timeoutSeconds = 15
        )
        return if (result is GitResult.Ok) result.output else ""
    }

    // ── Clone ──────────────────────────────────────────────────────────────

    /**
     * Clone a repository from URL into a destination directory.
     * @param url repository URL (https:// or git@)
     * @param destDir destination directory (guest-side path)
     * @param workdir working directory for running the command (parent of dest)
     */
    fun clone(url: String, destDir: String, workdir: String, token: String? = null): GitResult {
        // SG05 (P3a): the executor's auth injection (needsAuth includes clone)
        // only fires when a token is passed — this call never sent one, so a
        // private repo URL typed into CloneDialog failed with git's raw auth
        // error even while signed in (RepoBrowserSheet worked because it
        // embeds the token itself — SG16's separate path).
        return GitCommandExecutor.run(
            context,
            listOf("clone", url, destDir),
            workdir,
            timeoutSeconds = 120,
            token = token,
        )
    }

    // ── Blame ───────────────────────────────────────────────────────────────

    /**
     * Get git blame data for a file (porcelain format).
     * @param filePath relative file path within the repo
     * @param workdir guest-side path to repository
     * @return Map of line number (1-based) to BlameLine(author, date, shortSha)
     */
    fun blame(filePath: String, workdir: String): Map<Int, BlameLine> {
        val result = GitCommandExecutor.run(
            context,
            listOf("blame", "--line-porcelain", "--", filePath),
            rootFor(workdir),
            timeoutSeconds = 30
        )
        if (result !is GitResult.Ok) return emptyMap()
        val map = mutableMapOf<Int, BlameLine>()
        var lineNum = 0
        var currentSha = ""
        var currentAuthor = ""
        var currentDate = ""
        for (line in result.lines) {
            // SHA header: <40-hex-sha> <orig-line> <final-line> [groups...]
            val shaMatch = Regex("^[0-9a-f]{40}\\s+\\d+\\s+\\d+").find(line)
            if (shaMatch != null) {
                val parts = line.split("\\s+".toRegex())
                currentSha = parts[0].take(8)
                lineNum = parts[2].toIntOrNull() ?: 0
            } else if (line.startsWith("author ")) {
                currentAuthor = line.removePrefix("author ").trim()
            } else if (line.startsWith("author-time ")) {
                val timestamp = line.removePrefix("author-time ").trim().toLongOrNull() ?: 0L
                currentDate = if (timestamp > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(timestamp * 1000))
                } else ""
            } else if (line.startsWith("\t")) {
                if (lineNum > 0) {
                    map[lineNum] = BlameLine(currentAuthor.take(12), currentDate, currentSha)
                }
            }
        }
        return map
    }
}

/**
 * Git blame line data.
 */
data class BlameLine(
    val author: String,
    val date: String,
    val shortSha: String
)

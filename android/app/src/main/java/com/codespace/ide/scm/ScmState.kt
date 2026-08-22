package com.codespace.ide.scm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ScmState — lightweight state holder for the Source Control panel.
 *
 * Follows the project's existing pattern (produceState + rememberCoroutineScope)
 * rather than introducing a full ViewModel. The Composable calls these suspend
 * methods from a coroutine scope and updates its own remember/state.
 *
 * Phase SCM-5: State management. No Compose imports, no UI.
 *
 * Thread safety: all methods run on Dispatchers.IO. The caller is responsible
 * for coroutine scoping and state mutation on the main thread.
 */
class ScmState(private val context: Context) {

    private val service = GitService(context)

    /**
     * Guest-side workdir for the current project.
     * Resolved from the host path via ProotInstaller.hostToGuestPath.
     * Returns null if the path isn't reachable inside proot.
     */
    fun resolveWorkdir(hostPath: String): String? {
        return com.codespace.ide.terminal.ProotInstaller.hostToGuestPath(context, hostPath)
    }

    /**
     * Load full repository status.
     * Returns null if not a git repo or path not reachable.
     */
    suspend fun loadStatus(hostPath: String): ScmRepoState? = withContext(Dispatchers.IO) {
        val workdir = resolveWorkdir(hostPath) ?: return@withContext null
        if (!service.isRepo(workdir)) return@withContext null
        service.status(workdir)
    }

    /**
     * Stage files. Returns a result message for UI display.
     */
    suspend fun stageFiles(hostPath: String, files: List<String>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.add(files, workdir)) {
                is GitResult.Ok -> true to "Staged ${files.size} file(s)"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Stage all changes.
     */
    suspend fun stageAll(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.addAll(workdir)) {
                is GitResult.Ok -> true to "All changes staged"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Unstage files.
     */
    suspend fun unstageFiles(hostPath: String, files: List<String>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.unstage(files, workdir)) {
                is GitResult.Ok -> true to "Unstaged ${files.size} file(s)"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Discard changes to a file (revert to last commit, or delete if untracked).
     * Destructive — caller should confirm with the user before calling this.
     */
    suspend fun discardFile(hostPath: String, path: String, isUntracked: Boolean): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.discardFile(path, isUntracked, workdir)) {
                is GitResult.Ok -> true to "Discarded changes to $path"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Commit staged changes.
     */
    suspend fun commit(hostPath: String, message: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.commit(message, workdir)) {
                is GitResult.Ok -> true to "Committed: ${message.take(50)}"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Stage all and commit.
     */
    suspend fun stageAllAndCommit(hostPath: String, message: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.addAllAndCommit(message, workdir)) {
                is GitResult.Ok -> true to "Committed: ${message.take(50)}"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Push to remote.
     */
    suspend fun push(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.push(workdir)) {
                is GitResult.Ok -> true to "Pushed to remote"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Pull from remote.
     */
    suspend fun pull(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.pull(workdir)) {
                is GitResult.Ok -> true to "Pulled from remote"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Fetch from remote.
     */
    suspend fun fetch(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.fetch(workdir)) {
                is GitResult.Ok -> true to "Fetched from remote"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Get commit history.
     */
    suspend fun log(hostPath: String, maxCount: Int = 50, file: String? = null): List<ScmCommit> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyList()
            service.log(workdir, maxCount, file)
        }

    /**
     * Get branches.
     */
    suspend fun branches(hostPath: String): List<ScmBranch> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyList()
            service.branches(workdir)
        }

    /**
     * Get diff for a file.
     */
    suspend fun diffFile(hostPath: String, filePath: String): ScmFileDiff =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext ScmFileDiff(filePath, emptyList())
            service.diffFile(filePath, workdir)
        }

    /**
     * Get staged diff for a file.
     */
    suspend fun diffStaged(hostPath: String, filePath: String): ScmFileDiff =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext ScmFileDiff(filePath, emptyList())
            service.diffStaged(filePath, workdir)
        }

    /**
     * Check for conflicts.
     */
    suspend fun hasConflicts(hostPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false
            service.hasConflicts(workdir)
        }

    /**
     * Get conflicted files.
     */
    suspend fun conflictedFiles(hostPath: String): List<String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyList()
            service.conflictedFiles(workdir)
        }

    /**
     * Checkout a branch.
     */
    suspend fun checkout(hostPath: String, branch: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.checkout(branch, workdir)) {
                is GitResult.Ok -> true to "Checked out $branch"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Create a new branch.
     */
    suspend fun deleteBranch(hostPath: String, name: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Not a git repository"
            when (val r = service.deleteBranch(name, workdir)) {
                is GitResult.Ok -> true to "Branch '$name' deleted"
                is GitResult.Err -> false to r.error.message
            }
        }

    suspend fun renameBranch(hostPath: String, oldName: String, newName: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Not a git repository"
            when (val r = service.renameBranch(oldName, newName, workdir)) {
                is GitResult.Ok -> true to "Branch renamed to '$newName'"
                is GitResult.Err -> false to r.error.message
            }
        }

    suspend fun createBranch(hostPath: String, name: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.createBranch(name, workdir)) {
                is GitResult.Ok -> true to "Created branch $name"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Stash changes.
     */
    suspend fun stash(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.stash(workdir)) {
                is GitResult.Ok -> true to "Changes stashed"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Pop stash.
     */
    suspend fun stashPop(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.stashPop(workdir)) {
                is GitResult.Ok -> true to "Stash popped"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Merge a branch into the current branch.
     */
    suspend fun merge(hostPath: String, branch: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.merge(branch, workdir)) {
                is GitResult.Ok -> true to "Merged $branch"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Abort an in-progress merge.
     */
    suspend fun abortMerge(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.abortMerge(workdir)) {
                is GitResult.Ok -> true to "Merge aborted"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Rebase onto a branch.
     */
    suspend fun rebase(hostPath: String, branch: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.rebase(branch, workdir)) {
                is GitResult.Ok -> true to "Rebased onto $branch"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Abort an in-progress rebase.
     */
    suspend fun abortRebase(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.abortRebase(workdir)) {
                is GitResult.Ok -> true to "Rebase aborted"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Continue a rebase after resolving conflicts.
     */
    suspend fun continueRebase(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.continueRebase(workdir)) {
                is GitResult.Ok -> true to "Rebase continued"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Mark a conflicted file as resolved.
     */
    suspend fun resolveConflict(hostPath: String, file: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.resolveConflict(file, workdir)) {
                is GitResult.Ok -> true to "Resolved: $file"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * List all tags.
     */
    suspend fun tags(hostPath: String): List<String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyList()
            service.tags(workdir)
        }

    /**
     * Create a tag.
     */
    suspend fun createTag(hostPath: String, name: String, message: String? = null): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.createTag(name, message, workdir)) {
                is GitResult.Ok -> true to "Created tag $name"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Delete a tag.
     */
    suspend fun deleteTag(hostPath: String, name: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.deleteTag(name, workdir)) {
                is GitResult.Ok -> true to "Deleted tag $name"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * List all remotes.
     */
    suspend fun remotes(hostPath: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyList()
            service.remotes(workdir)
        }

    /**
     * Remove a remote.
     */
    suspend fun removeRemote(hostPath: String, name: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.removeRemote(name, workdir)) {
                is GitResult.Ok -> true to "Removed remote $name"
                is GitResult.Err -> false to r.error.message
            }
        }

    suspend fun addRemote(hostPath: String, name: String, url: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.addRemote(name, url, workdir)) {
                is GitResult.Ok -> true to "Added remote $name"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Initialize a new repository.
     */
    suspend fun initRepo(hostPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext false to "Path not reachable"
            when (val r = service.init(workdir)) {
                is GitResult.Ok -> true to "Repository initialized"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Get ASCII branch graph.
     */
    suspend fun graphLog(hostPath: String, maxCount: Int = 100): String =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext ""
            service.graphLog(workdir, maxCount)
        }

    /**
     * Clone a repository from URL.
     */
    suspend fun cloneRepo(hostPath: String, url: String, destDir: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: hostPath
            when (val r = service.clone(url, destDir, workdir)) {
                is GitResult.Ok -> true to "Cloned $url"
                is GitResult.Err -> false to r.error.message
            }
        }

    /**
     * Get git blame data for a file.
     */
    suspend fun blame(hostPath: String, filePath: String): Map<Int, com.codespace.ide.scm.BlameLine> =
        withContext(Dispatchers.IO) {
            val workdir = resolveWorkdir(hostPath) ?: return@withContext emptyMap()
            service.blame(filePath, workdir)
        }
}

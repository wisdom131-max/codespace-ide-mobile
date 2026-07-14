package com.codespace.ide.git

import com.codespace.ide.domain.AppError
import com.codespace.ide.domain.AppResult
import com.codespace.ide.domain.GitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val timestamp: Long,       // epoch millis
)

data class StashEntry(
    val index: Int,
    val message: String,
    val sha: String,
)

data class TagInfo(
    val name: String,
    val sha: String,
    val message: String,      // empty for lightweight tags
    val isAnnotated: Boolean,
)

/**
 * On-device Git built on JGit.
 *
 * Phase 3 fixes: real ahead/behind via rev-walk, per-file diff filter.
 * Phase 6 additions: commitLog, deleteBranch, renameBranch,
 *                    stashSave/stashPop/listStashes,
 *                    createTag/deleteTag/listTags,
 *                    conflictedFiles.
 */
@Singleton
class GitEngine @Inject constructor() {

    private fun creds(token: String?): CredentialsProvider? =
        token?.let { UsernamePasswordCredentialsProvider("x-access-token", it) }

    // ── clone ─────────────────────────────────────────────────────────────────
    suspend fun clone(url: String, dest: File, token: String? = null): AppResult<File> =
        io {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .apply { creds(token)?.let { setCredentialsProvider(it) } }
                .call().use { }
            dest
        }

    // ── status ────────────────────────────────────────────────────────────────
    suspend fun status(repoDir: File): AppResult<GitStatus> = io {
        Git.open(repoDir).use { git ->
            val status = git.status().call()
            val branch = git.repository.branch
            var ahead = 0; var behind = 0
            try {
                val repo = git.repository
                val headId     = repo.resolve("HEAD")
                val upstreamId = repo.resolve("@{upstream}")
                if (headId != null && upstreamId != null) {
                    RevWalk(repo).use { rw ->
                        rw.markStart(rw.parseCommit(headId))
                        rw.markUninteresting(rw.parseCommit(upstreamId))
                        ahead = rw.count()
                    }
                    RevWalk(repo).use { rw ->
                        rw.markStart(rw.parseCommit(upstreamId))
                        rw.markUninteresting(rw.parseCommit(headId))
                        behind = rw.count()
                    }
                }
            } catch (_: Exception) {}
            GitStatus(
                branch   = branch,
                ahead    = ahead,
                behind   = behind,
                staged   = (status.added + status.changed + status.removed).toList(),
                modified = status.modified.toList(),
                untracked = status.untracked.toList(),
            )
        }
    }

    // ── conflicted files ──────────────────────────────────────────────────────
    suspend fun conflictedFiles(repoDir: File): AppResult<List<String>> = io {
        Git.open(repoDir).use { git ->
            git.status().call().conflicting.toList()
        }
    }

    // ── stage / commit / pull / push ──────────────────────────────────────────
    suspend fun stageAll(repoDir: File): AppResult<Unit> = io {
        Git.open(repoDir).use { it.add().addFilepattern(".").call() }
        Unit
    }

    suspend fun commit(
        repoDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): AppResult<String> = io {
        Git.open(repoDir).use { git ->
            val rev = git.commit()
                .setMessage(message)
                .setAuthor(PersonIdent(authorName, authorEmail))
                .call()
            rev.name
        }
    }

    suspend fun pull(repoDir: File, token: String? = null): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.pull().apply { creds(token)?.let { setCredentialsProvider(it) } }.call()
        }
        Unit
    }

    suspend fun push(repoDir: File, token: String? = null): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.push().apply { creds(token)?.let { setCredentialsProvider(it) } }.call()
        }
        Unit
    }

    // ── branches ──────────────────────────────────────────────────────────────
    suspend fun listBranches(repoDir: File): AppResult<List<String>> = io {
        Git.open(repoDir).use { git ->
            git.branchList().call().map { it.name.removePrefix("refs/heads/") }
        }
    }

    suspend fun createBranch(repoDir: File, name: String): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.checkout().setCreateBranch(true).setName(name).call()
        }
        Unit
    }

    suspend fun checkout(repoDir: File, name: String): AppResult<Unit> = io {
        Git.open(repoDir).use { it.checkout().setName(name).call() }
        Unit
    }

    /** Delete a local branch. Pass force=true to delete even if unmerged. */
    suspend fun deleteBranch(repoDir: File, name: String, force: Boolean = false): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.branchDelete().setBranchNames(name).setForce(force).call()
        }
        Unit
    }

    /** Rename (move) a branch. If [oldName] is null, renames the current branch. */
    suspend fun renameBranch(repoDir: File, oldName: String?, newName: String): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.branchRename()
                .apply { if (oldName != null) setOldName(oldName) }
                .setNewName(newName)
                .call()
        }
        Unit
    }

    suspend fun merge(repoDir: File, branch: String): AppResult<MergeResult.MergeStatus> = io {
        Git.open(repoDir).use { git ->
            val ref = git.repository.findRef(branch)
            git.merge().include(ref).call().mergeStatus
        }
    }

    // ── commit log ────────────────────────────────────────────────────────────
    /** Returns up to [limit] commits from HEAD, newest first. */
    suspend fun commitLog(
        repoDir: File,
        limit: Int = 100,
        filePath: String? = null,
    ): AppResult<List<CommitInfo>> = io {
        Git.open(repoDir).use { git ->
            val cmd = git.log().setMaxCount(limit)
            if (filePath != null) cmd.addPath(filePath)
            cmd.call().map { c: RevCommit ->
                CommitInfo(
                    sha        = c.name,
                    shortSha   = c.name.take(7),
                    message    = c.fullMessage.trim(),
                    authorName = c.authorIdent.name,
                    authorEmail = c.authorIdent.emailAddress,
                    timestamp  = c.authorIdent.`when`.time,
                )
            }
        }
    }

    // ── stash ─────────────────────────────────────────────────────────────────
    suspend fun stashSave(repoDir: File, message: String = ""): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.stashCreate()
                .apply { if (message.isNotBlank()) setWorkingDirectoryMessage(message) }
                .call()
        }
        Unit
    }

    suspend fun listStashes(repoDir: File): AppResult<List<StashEntry>> = io {
        Git.open(repoDir).use { git ->
            git.stashList().call().mapIndexed { i, c ->
                StashEntry(
                    index   = i,
                    message = c.fullMessage.trim(),
                    sha     = c.name,
                )
            }
        }
    }

    /** Pop stash at [index] (default 0 = most recent). */
    suspend fun stashPop(repoDir: File, index: Int = 0): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            val stashRef = "stash@{$index}"
            git.stashApply().setStashRef(stashRef).call()
            git.stashDrop().setStashRef(index).call()
        }
        Unit
    }

    // ── tags ─────────────────────────────────────────────────────────────────
    suspend fun listTags(repoDir: File): AppResult<List<TagInfo>> = io {
        Git.open(repoDir).use { git ->
            git.tagList().call().map { ref: Ref ->
                val name = ref.name.removePrefix("refs/tags/")
                val peeledRef  = git.repository.refDatabase.peel(ref)
                val isAnnotated = peeledRef.peeledObjectId != null
                val sha = (peeledRef.peeledObjectId ?: ref.objectId).name
                // Try to get tag message for annotated tags
                val msg = if (isAnnotated) {
                    try {
                        RevWalk(git.repository).use { rw ->
                            val tagObj = rw.parseTag(ref.objectId)
                            tagObj.fullMessage.trim()
                        }
                    } catch (_: Exception) { "" }
                } else ""
                TagInfo(name = name, sha = sha, message = msg, isAnnotated = isAnnotated)
            }
        }
    }

    suspend fun createTag(
        repoDir: File,
        name: String,
        message: String = "",
        annotated: Boolean = true,
    ): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.tag()
                .setName(name)
                .setAnnotated(annotated)
                .apply { if (message.isNotBlank()) setMessage(message) }
                .call()
        }
        Unit
    }

    suspend fun deleteTag(repoDir: File, name: String): AppResult<Unit> = io {
        Git.open(repoDir).use { git ->
            git.tagDelete().setTags(name).call()
        }
        Unit
    }

    // ── diff ──────────────────────────────────────────────────────────────────
    suspend fun diff(repoDir: File, filePath: String? = null): AppResult<String> = io {
        Git.open(repoDir).use { git ->
            val out = java.io.ByteArrayOutputStream()
            val cmd = git.diff().setOutputStream(out)
            if (filePath != null) cmd.setPathFilter(
                org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
            )
            cmd.call()
            out.toString("UTF-8")
        }
    }

    // ── internal ──────────────────────────────────────────────────────────────
    private suspend inline fun <T> io(crossinline block: () -> T): AppResult<T> =
        withContext(Dispatchers.IO) {
            try {
                AppResult.Success(block())
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Git(t.message ?: "Git operation failed"))
            }
        }
}

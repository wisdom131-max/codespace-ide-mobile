package com.codespace.ide.scm

/**
 * SCM Domain Models — structured representations of git state.
 *
 * Replaces the raw string parsing that was scattered across ExplorerPane,
 * ProjectShellScreen, EditorPane, and the old SourceControlPane.
 *
 * Phase SCM-2: Domain models. No git execution, no UI — pure data.
 */

// ── Git Execution Result ───────────────────────────────────────────────────

/**
 * Result of any git command execution.
 * Ok contains stdout (trimmed, noise-stripped). Err contains a structured error.
 */
sealed class GitResult {
    data class Ok(val output: String) : GitResult() {
        val lines: List<String> get() = output.lines().filter { it.isNotBlank() }
    }
    data class Err(val error: GitError) : GitResult()
}

/**
 * Structured git errors — replaces "Exit code 128" string matching.
 */
sealed class GitError(val message: String) {
    data class NotARepo(val path: String) :
        GitError("Not a git repository: $path")

    data class NoUpstream(val branch: String) :
        GitError("No upstream configured for branch '$branch'")

    data class AuthFailed(val detail: String) :
        GitError("Authentication failed: $detail")

    data class MergeConflict(val files: List<String>) :
        GitError("Merge conflicts in: ${files.joinToString(", ")}")

    data class NetworkFailed(val detail: String) :
        GitError("Network error: $detail")

    data class LockFailed(val detail: String) :
        GitError("Could not acquire lock: $detail")

    data class ExecFailed(val exitCode: Int, val stderr: String) :
        GitError("git exited $exitCode: ${stderr.take(300)}")

    data class Timeout(val command: String) :
        GitError("Timed out running: $command")

    data class Unknown(val detail: String) :
        GitError(detail)
}

// ── File Status ────────────────────────────────────────────────────────────

/**
 * XY status from git status --porcelain.
 * X = staged status, Y = working tree status.
 */
enum class FileChange {
    UNMODIFIED, MODIFIED, ADDED, DELETED, RENAMED, COPIED, UPDATED, UNTRACKED, IGNORED
}

/**
 * A single file's git status — parsed from porcelain output.
 */
data class ScmFileStatus(
    val path: String,
    val stagedChange: FileChange,
    val workingChange: FileChange,
) {
    val isStaged: Boolean
        get() = stagedChange != FileChange.UNMODIFIED && stagedChange != FileChange.UNTRACKED

    val isUnstaged: Boolean
        get() = workingChange != FileChange.UNMODIFIED && workingChange != FileChange.UNTRACKED

    val isUntracked: Boolean
        get() = stagedChange == FileChange.UNTRACKED

    val isConflicted: Boolean
        get() = stagedChange == FileChange.UPDATED || workingChange == FileChange.UPDATED

    companion object {
        /**
         * Parse a single line of git status --porcelain output.
         * Format: "XY <path>" where X and Y are status codes.
         */
        fun parse(line: String): ScmFileStatus? {
            if (line.length < 3) return null
            val x = line[0]
            val y = line[1]
            val rest = line.substring(3)

            // Handle rename arrows: "R  old -> new"
            val arrowIdx = rest.indexOf(" -> ")
            val path = if (arrowIdx >= 0) rest.substring(arrowIdx + 4) else rest

            return ScmFileStatus(
                path = path.trim(),
                stagedChange = parseChange(x),
                workingChange = parseChange(y),
            )
        }

        private fun parseChange(c: Char): FileChange = when (c) {
            ' ' -> FileChange.UNMODIFIED
            'M' -> FileChange.MODIFIED
            'A' -> FileChange.ADDED
            'D' -> FileChange.DELETED
            'R' -> FileChange.RENAMED
            'C' -> FileChange.COPIED
            'U' -> FileChange.UPDATED
            '?' -> FileChange.UNTRACKED
            '!' -> FileChange.IGNORED
            else -> FileChange.UNMODIFIED
        }
    }
}

// ── Branch / Repo State ────────────────────────────────────────────────────

/**
 * Full repository state — replaces the old GitStatus model in domain/Models.kt.
 */
data class ScmRepoState(
    val branch: String,
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstream: String? = null,
    val staged: List<ScmFileStatus> = emptyList(),
    val unstaged: List<ScmFileStatus> = emptyList(),
    val untracked: List<ScmFileStatus> = emptyList(),
    val conflicted: List<ScmFileStatus> = emptyList(),
    val isDetached: Boolean = false,
    val headCommit: String? = null,
) {
    val totalChanges: Int
        get() = staged.size + unstaged.size + untracked.size + conflicted.size

    companion object {
        val EMPTY = ScmRepoState(branch = "(no branch)")
    }
}

// ── Commit ─────────────────────────────────────────────────────────────────

data class ScmCommit(
    val hash: String,
    val author: String,
    val date: String,
    val message: String,
    val isHead: Boolean = false,
)

// ── Branch ─────────────────────────────────────────────────────────────────

data class ScmBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val upstream: String? = null,
)

// ── Diff ────────────────────────────────────────────────────────────────────

data class ScmDiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<ScmDiffLine>,
)

data class ScmDiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

enum class DiffLineType { CONTEXT, ADDED, DELETED, HUNK_HEADER }

data class ScmFileDiff(
    val path: String,
    val hunks: List<ScmDiffHunk>,
    val isBinary: Boolean = false,
    val isRenamed: Boolean = false,
    val oldPath: String? = null,
)

// ── Operation State ─────────────────────────────────────────────────────────

/**
 * What the SCM panel is currently doing. Drives the UI progress indicator.
 */
sealed class ScmOperation {
    data object Idle : ScmOperation()
    data class Loading(val message: String) : ScmOperation()
    data class Committing(val message: String) : ScmOperation()
    data class Pushing(val upstream: String) : ScmOperation()
    data class Pulling(val upstream: String) : ScmOperation()
    data class Staging(val files: List<String>) : ScmOperation()
    data class Unstaging(val files: List<String>) : ScmOperation()
    data class Fetching(val remote: String) : ScmOperation()
    data class Cloning(val url: String) : ScmOperation()
    data class Error(val error: GitError) : ScmOperation()
}

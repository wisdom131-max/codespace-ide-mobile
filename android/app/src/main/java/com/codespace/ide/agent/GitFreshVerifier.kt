package com.codespace.ide.agent

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PROJECT FOUR, STAGE 4.7 (2026-11-16): ask the repository, not the receipt.
 *
 * Stage 4.6 verified git by parsing the text a tool had already returned. That text can be stale by
 * the time it is read, and it is the tool's own account of itself. This reads the repository again,
 * read-only, and hands the result to the same parser so both paths agree on what a repository state is.
 *
 * Read-only by construction: only `status`, `rev-parse` and `log` are ever run, never a write, so
 * verifying a commit cannot change anything and cannot duplicate an operation. Bounded: at most four
 * commands per call, each with a short timeout, and nothing runs when the directory is not a repository.
 */
object GitFreshVerifier {

    /** One reading of a repository, with what could and could not be established. */
    data class Snapshot(
        val root: String?,
        val state: GitStateVerifier.State?,
        val head: String?,
        val subject: String?,
        val readable: Boolean,
        val note: String,
    )

    private const val TIMEOUT_SECONDS = 5L

    fun read(root: String?): Snapshot {
        if (root.isNullOrBlank()) {
            return Snapshot(null, null, null, null, false, "no project directory is open, so the repository could not be read")
        }
        if (!File(root).isDirectory) {
            return Snapshot(root, null, null, null, false, "the project directory is not there")
        }
        val status = git(root, "status", "--porcelain")
            ?: return Snapshot(root, null, null, null, false, "git is not available here, so the repository state is unknown")
        // An empty porcelain status IS a clean tree, not an unreadable one; only an explicit "not a
        // repository" (or unparseable output) means the state is unknown.
        val parsed = GitStateVerifier.parseStatus(status.text)
        val state = parsed ?: if (status.text.isBlank()) GitStateVerifier.State() else
            return Snapshot(root, null, null, null, false, "the directory is not a git repository")
        // Porcelain output carries no branch, so the branch is read separately; "HEAD" means detached.
        val branchRaw = git(root, "rev-parse", "--abbrev-ref", "HEAD")?.text?.trim()
        val branch = branchRaw?.takeIf { it.isNotEmpty() && it != "HEAD" }
        val current = state.copy(branch = branch ?: state.branch)
        val head = git(root, "rev-parse", "HEAD")?.text?.trim()?.takeIf { it.length >= 7 }
        val subject = git(root, "log", "-1", "--pretty=%s")?.text?.trim()?.takeIf { it.isNotEmpty() }
        return Snapshot(root, current, head, subject, true, "the repository was read")
    }

    fun verifyClean(root: String?): GitStateVerifier.Outcome {
        val snapshot = read(root)
        val state = snapshot.state ?: return unavailable(snapshot)
        return if (state.clean) {
            GitStateVerifier.Outcome(GitStateVerifier.Result.VERIFIED, "the working tree is clean")
        } else {
            GitStateVerifier.Outcome(
                GitStateVerifier.Result.REFUTED,
                state.changed.size.toString() + " changed and " + state.untracked.size.toString() + " untracked file(s) remain",
            )
        }
    }

    fun verifyCommit(root: String?, expectedSubject: String? = null, expectedHead: String? = null): GitStateVerifier.Outcome {
        val snapshot = read(root)
        if (snapshot.state == null) return unavailable(snapshot)
        val head = snapshot.head
            ?: return GitStateVerifier.Outcome(GitStateVerifier.Result.INCONCLUSIVE, "HEAD could not be read")
        if (expectedHead != null && !sameCommit(head, expectedHead)) {
            return GitStateVerifier.Outcome(
                GitStateVerifier.Result.REFUTED,
                "HEAD is " + head.take(7) + ", not " + expectedHead.take(7),
            )
        }
        if (expectedSubject != null && expectedSubject.isNotBlank() &&
            (snapshot.subject == null || !snapshot.subject.contains(expectedSubject))
        ) {
            return GitStateVerifier.Outcome(
                GitStateVerifier.Result.REFUTED,
                "the newest commit is \"" + (snapshot.subject ?: "unreadable") + "\", not \"" + expectedSubject + "\"",
            )
        }
        return GitStateVerifier.Outcome(
            GitStateVerifier.Result.VERIFIED,
            "the repository is at " + head.take(7) + (snapshot.subject?.let { " (\"" + it + "\")" } ?: ""),
        )
    }

    fun verifyCheckout(root: String?, expectedBranch: String): GitStateVerifier.Outcome {
        val snapshot = read(root)
        val state = snapshot.state ?: return unavailable(snapshot)
        val branch = state.branch
        return when {
            branch == null -> GitStateVerifier.Outcome(
                GitStateVerifier.Result.INCONCLUSIVE, "HEAD is detached, so there is no branch to confirm",
            )
            branch == expectedBranch -> GitStateVerifier.Outcome(GitStateVerifier.Result.VERIFIED, "on branch " + branch)
            else -> GitStateVerifier.Outcome(
                GitStateVerifier.Result.REFUTED, "the branch is " + branch + ", not " + expectedBranch,
            )
        }
    }

    fun verifyStaging(root: String?, expectedPaths: List<String>): GitStateVerifier.Outcome {
        val snapshot = read(root)
        val state = snapshot.state ?: return unavailable(snapshot)
        if (expectedPaths.isEmpty()) return GitStateVerifier.Outcome(GitStateVerifier.Result.NOT_CHECKABLE, "no paths were named")
        val missing = expectedPaths.filter { path -> state.staged.none { it.endsWith(path.trim()) } }
        if (missing.isNotEmpty()) {
            return GitStateVerifier.Outcome(GitStateVerifier.Result.REFUTED, "not staged: " + missing.joinToString(", "))
        }
        val unexpected = state.staged.filter { staged -> expectedPaths.none { staged.endsWith(it.trim()) } }
        return if (unexpected.isEmpty()) {
            GitStateVerifier.Outcome(GitStateVerifier.Result.VERIFIED, "exactly the intended paths are staged")
        } else {
            GitStateVerifier.Outcome(
                GitStateVerifier.Result.VERIFIED,
                "the intended paths are staged; also staged: " + unexpected.take(5).joinToString(", "),
            )
        }
    }

    private fun unavailable(snapshot: Snapshot): GitStateVerifier.Outcome =
        GitStateVerifier.Outcome(GitStateVerifier.Result.NOT_CHECKABLE, snapshot.note)

    private fun sameCommit(a: String, b: String): Boolean =
        a.startsWith(b.take(7)) || b.startsWith(a.take(7))

    private data class Output(val text: String)

    private fun git(root: String, vararg args: String): Output? {
        return runCatching {
            val command = ArrayList<String>(args.size + 3)
            command.add("git")
            command.add("-C")
            command.add(root)
            command.addAll(args)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            Output(process.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()
    }
}

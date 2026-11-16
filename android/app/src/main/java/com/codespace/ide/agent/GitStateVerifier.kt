package com.codespace.ide.agent

/**
 * PROJECT FOUR, STAGE 4.6 (2026-11-16): read the repository, do not trust the receipt.
 *
 * A git command that printed "committed" has not been checked. This parses what the git tools already
 * return (`git_status`, `git_log`) into facts and compares them with what was asked for. It is pure
 * parsing: no command is run, nothing in the repository is changed, and destructive repairs are never
 * attempted to make a verification pass.
 */
object GitStateVerifier {

    enum class Result { VERIFIED, REFUTED, INCONCLUSIVE, NOT_CHECKABLE }

    data class Outcome(val result: Result, val note: String) {
        val ok: Boolean get() = result == Result.VERIFIED
    }

    data class State(
        val branch: String? = null,
        val staged: List<String> = emptyList(),
        val unstaged: List<String> = emptyList(),
        val untracked: List<String> = emptyList(),
        val conflicted: List<String> = emptyList(),
        val inProgress: String? = null,
    ) {
        val changed: List<String> get() = (staged + unstaged).distinct()
        val clean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicted.isEmpty()
    }

    private val IN_PROGRESS = listOf(
        "rebase in progress" to "a rebase is in progress",
        "you are currently rebasing" to "a rebase is in progress",
        "you are currently cherry-picking" to "a cherry-pick is in progress",
        "you are currently merging" to "a merge is in progress",
        "all conflicts fixed but you are still merging" to "a merge is in progress",
        "unmerged paths" to "a merge has conflicts",
    )

    /** Reads both `git status --porcelain` and the human form, because both are already produced. */
    fun parseStatus(output: String): State? {
        val text = output.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val notRepo = lower.contains("not a git repository")
        if (notRepo) return null

        var branch: String? = null
        Regex("^##\\s+(?:No commits yet on\\s+)?([^\\s.]+)", RegexOption.MULTILINE).find(text)
            ?.let { branch = it.groupValues[1].trim() }
        Regex("^On branch\\s+(\\S+)", RegexOption.MULTILINE).find(text)
            ?.let { branch = it.groupValues[1].trim() }
        Regex("^HEAD detached at\\s+(\\S+)", RegexOption.MULTILINE).find(text)
            ?.let { branch = null }

        val staged = ArrayList<String>()
        val unstaged = ArrayList<String>()
        val untracked = ArrayList<String>()
        val conflicted = ArrayList<String>()

        text.lineSequence().forEach { line ->
            val code = line.take(2)
            if (line.length > 3 && (code[0] != ' ' || code[1] != ' ') &&
                (code[0] in "MADRCU?!" || code[1] in "MADRCU?!")
            ) {
                val path = line.drop(3).trim()
                if (path.isEmpty()) return@forEach
                when {
                    code == "??" -> untracked.add(path)
                    code.contains('U') || code == "AA" || code == "DD" -> conflicted.add(path)
                    code[0] != ' ' && code[0] != '?' -> staged.add(path)
                    else -> unstaged.add(path)
                }
            }
        }
        val inProgress = IN_PROGRESS.firstOrNull { lower.contains(it.first) }?.second
        return State(branch, staged, unstaged, untracked, conflicted, inProgress)
    }

    fun verifyCommit(logOutput: String, expectedSubject: String? = null, expectedHead: String? = null): Outcome {
        val text = logOutput.trim()
        if (text.isEmpty()) return Outcome(Result.NOT_CHECKABLE, "no git log was read, so nothing is verified")
        val lower = text.lowercase()
        if (lower.contains("not a git repository")) return Outcome(Result.NOT_CHECKABLE, "this is not a repository")
        if (lower.contains("fatal:") || lower.contains("error:")) {
            return Outcome(Result.INCONCLUSIVE, "git log reported a problem: " + text.lineSequence().first())
        }
        val head = Regex("\\b([0-9a-f]{7,40})\\b").find(text)?.groupValues?.get(1)
        if (expectedHead != null && head != null &&
            !head.startsWith(expectedHead.take(7)) && !expectedHead.startsWith(head.take(7))
        ) {
            return Outcome(Result.REFUTED, "HEAD is " + head.take(7) + ", not " + expectedHead.take(7))
        }
        if (expectedSubject != null && expectedSubject.isNotBlank() && !lower.contains(expectedSubject.lowercase())) {
            return Outcome(Result.REFUTED, "the expected commit \"" + expectedSubject + "\" is not in the log")
        }
        return Outcome(Result.VERIFIED, "the commit is in the log" + (head?.let { ", HEAD " + it.take(7) } ?: ""))
    }

    fun verifyCheckout(statusOutput: String, expectedBranch: String): Outcome {
        val state = parseStatus(statusOutput)
            ?: return Outcome(Result.NOT_CHECKABLE, "no repository state was read")
        val branch = state.branch
        return when {
            branch == null -> Outcome(Result.INCONCLUSIVE, "the branch could not be read from git status")
            branch == expectedBranch -> Outcome(Result.VERIFIED, "on branch " + branch)
            else -> Outcome(Result.REFUTED, "the branch is " + branch + ", not " + expectedBranch)
        }
    }

    fun verifyStaging(statusOutput: String, expectedPaths: List<String>): Outcome {
        val state = parseStatus(statusOutput)
            ?: return Outcome(Result.NOT_CHECKABLE, "no repository state was read")
        if (state.branch == null && state.clean) {
            // Nothing readable was in the output: that is not a repository state to judge.
            return Outcome(Result.NOT_CHECKABLE, "the git status output could not be read as a repository state")
        }
        if (expectedPaths.isEmpty()) return Outcome(Result.NOT_CHECKABLE, "no paths were named")
        val missing = expectedPaths.filter { path -> state.staged.none { it.endsWith(path.trim()) } }
        if (missing.isNotEmpty()) {
            return Outcome(Result.REFUTED, "not staged: " + missing.joinToString(", "))
        }
        val unexpected = state.staged.filter { staged -> expectedPaths.none { staged.endsWith(it.trim()) } }
        return if (unexpected.isEmpty()) {
            Outcome(Result.VERIFIED, "exactly the intended paths are staged")
        } else {
            Outcome(Result.VERIFIED, "the intended paths are staged; also staged: " + unexpected.take(5).joinToString(", "))
        }
    }

    fun verifyClean(statusOutput: String): Outcome {
        val state = parseStatus(statusOutput) ?: return Outcome(Result.NOT_CHECKABLE, "no repository state was read")
        return if (state.clean) Outcome(Result.VERIFIED, "the working tree is clean") else
            Outcome(Result.REFUTED, "the working tree still holds " + state.changed.size + " change(s) and " +
                state.untracked.size + " untracked file(s)")
    }

    /** After a commit the tree should be clean for the staged paths and the operation finished. */
    fun verifySettled(statusOutput: String): Outcome {
        val state = parseStatus(statusOutput) ?: return Outcome(Result.NOT_CHECKABLE, "no repository state was read")
        return when {
            state.conflicted.isNotEmpty() -> Outcome(Result.REFUTED, "the repository still has conflicts")
            state.inProgress != null -> Outcome(Result.INCONCLUSIVE, state.inProgress!! + ", so the operation did not finish")
            else -> Outcome(Result.VERIFIED, "no conflict or half-finished operation is present")
        }
    }
}

package com.codespace.ide.agent

import java.io.File

/**
 * PHASE 1 (owner approval 2026-11-14): the rules a Git commit or push must satisfy, kept as pure
 * functions so they can be tested without a device, a repository, or a running git binary.
 *
 * The defect this exists to close: `git_commit_push` ran `git add -A`, `git commit` and
 * `git push` as ONE dispatch behind ONE approval, so a single tap authorised a network write, and
 * staging took every file in the tree including ones nobody mentioned.
 *
 * Nothing here executes git. The caller resolves paths, asks, then runs.
 */
internal object GitSafety {

    /** A staging decision: the exact paths to stage, and anything that makes it unsafe. */
    data class Plan(val paths: List<String>, val problems: List<String>) {
        val ok: Boolean get() = problems.isEmpty() && paths.isNotEmpty()
    }

    /** Names that are never staged by a legacy call that did not say what it wanted. */
    private val SECRETISH = listOf(
        ".env", ".env.local", "id_rsa", "id_ed25519", ".keystore", ".jks",
        "google-services.json", "local.properties",
    )

    /** Characters or tokens that would let one argument mean "the whole tree". */
    private val WILDCARDS = setOf("-A", "-a", "--all", ".", "./", "*", ":/", ":(top)")

    fun isProtectedBranch(branch: String): Boolean =
        branch.trim().lowercase().removePrefix("refs/heads/") in setOf("main", "master")

    /** The commit subject git will actually store: first non-blank line, collapsed. */
    fun subject(message: String): String =
        message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(72) ?: ""

    /**
     * Decide what to stage.
     *
     * [requested] is what the model named. [trackedModified] is what the tree says changed, used
     * ONLY when the model named nothing (the documented legacy behaviour) and never as a wildcard.
     */
    fun planStaging(requested: List<String>, trackedModified: List<String>): Plan {
        val problems = ArrayList<String>()
        val cleaned = ArrayList<String>()

        val named = requested.map { it.trim() }.filter { it.isNotEmpty() }
        if (named.isEmpty() && trackedModified.isEmpty()) {
            return Plan(emptyList(), listOf("Nothing to commit: no tracked file has changed."))
        }
        for (raw in (if (named.isEmpty()) trackedModified else named)) {
            if (raw in WILDCARDS || raw.endsWith("/*") || raw == "**") {
                problems.add("'$raw' would stage more than you named; list the files instead.")
                continue
            }
            if (raw.startsWith("/")) {
                problems.add("'$raw' is an absolute path; give a path inside the repository.")
                continue
            }
            val normalised = File(raw).invariantSeparatorsPath.removePrefix("./")
            if (normalised.split('/').any { it == ".." }) {
                problems.add("'$raw' climbs out of the repository.")
                continue
            }
            if (named.isEmpty() && SECRETISH.any { normalised.endsWith(it) }) {
                problems.add("'$normalised' looks like a credential file; stage it explicitly if that is really intended.")
                continue
            }
            if (normalised.isNotEmpty() && !cleaned.contains(normalised)) cleaned.add(normalised)
        }
        if (cleaned.isEmpty() && problems.isEmpty()) problems.add("Nothing to commit: the paths listed do not exist in the change set.")
        return Plan(cleaned, problems)
    }

    /** Tracked, modified paths from `git status --porcelain`. Untracked files are NOT included. */
    fun trackedModifiedFromStatus(porcelain: String): List<String> {
        val out = ArrayList<String>()
        for (line in porcelain.lineSequence()) {
            if (line.length < 4) continue
            val code = line.substring(0, 2)
            if (code.contains('?') || code.contains('!')) continue
            var path = line.substring(3).trim()
            if (path.contains(" -> ")) path = path.substringAfter(" -> ").trim()
            if (path.isNotEmpty()) out.add(path)
        }
        return out
    }

    /** A push target: where the commit would go, spelled so the user can refuse it. */
    data class PushTarget(val remote: String, val branch: String, val url: String?) {
        val label: String get() = remote + "/" + branch
    }

    /**
     * Read `git remote -v` output. Returns null when no remote is configured, which is a reason to
     * commit locally and say so rather than invent a target.
     */
    fun pushTarget(remoteList: String, branch: String): PushTarget? {
        val line = remoteList.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.split('\t', ' ').filter { it.isNotBlank() }
        val name = parts.firstOrNull() ?: return null
        val url = parts.getOrNull(1)
        if (branch.isBlank() || branch == "HEAD") return null
        return PushTarget(name, branch.trim(), url)
    }

    fun stageApprovalText(plan: Plan, message: String): String {
        val subject = subject(message)
        val listed = plan.paths.take(12).joinToString("\n  ")
        val more = if (plan.paths.size > 12) "\n  … and " + (plan.paths.size - 12) + " more" else ""
        return "Stage " + plan.paths.size + " file(s) and commit as \"" + subject + "\"?\n  " + listed + more
    }

    fun pushApprovalText(target: PushTarget): String =
        "Push the new commit from this branch to " + target.label +
            (target.url?.let { "\n  " + it } ?: "") + "?\nDeclining keeps the commit on this device."

    fun mainPushApprovalText(target: PushTarget): String =
        "This pushes directly to " + target.branch + " on " + target.remote + ". That cannot be undone " +
            "from this device.\nPush to " + target.label + "?"

    fun declinedPush(target: PushTarget, commitSummary: String?): String =
        "Pushed nothing. The commit is kept locally, ready to push to " + target.label + "." +
            (commitSummary?.takeIf { it.isNotBlank() }?.let { "\n" + it } ?: "")

    fun noRemoteText(commitSummary: String?): String =
        "Committed locally. No remote is configured, so there was nothing to push." +
            (commitSummary?.takeIf { it.isNotBlank() }?.let { "\n" + it } ?: "")
}

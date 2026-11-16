package com.codespace.ide.agent

import org.json.JSONObject

/**
 * PROJECT FOUR, STAGE 4.6 (2026-11-16): one place that asks the right verifier.
 *
 * The filesystem verifier answers "is the file there?"; git and archive need their own reading of the
 * output the tool already produced. This dispatches on the tool so the live gate has a single call.
 * Nothing here runs a command or changes anything.
 */
object OutcomeVerifier {

    enum class Result { VERIFIED, REFUTED, INCONCLUSIVE, NOT_CHECKABLE }

    data class Verification(val result: Result, val note: String) {
        val ok: Boolean get() = result != Result.REFUTED
        val proven: Boolean get() = result == Result.VERIFIED
    }

    private val NOTHING = Verification(Result.NOT_CHECKABLE, "")

    fun verify(tool: String, output: String, args: JSONObject, projectRoot: String?): Verification {
        return try {
            when (tool) {
                // The status tool IS a fresh read, so its own output is the fresh state.
                "git_status" -> wrap(GitStateVerifier.verifySettled(output))
                // STAGE 4.7 COMPLETION: state-changing operations are verified by re-reading the
                // repository, not by re-reading the tool's own account of itself.
                "git_commit_push", "git_commit" -> freshGit(projectRoot) {
                    GitFreshVerifier.verifyCommit(projectRoot, args.commitSubject())
                } ?: wrap(GitStateVerifier.verifyCommit(output, args.commitSubject()))
                "git_checkout", "git_switch" -> args.firstOf("branch", "name")?.let { branch ->
                    freshGit(projectRoot) { GitFreshVerifier.verifyCheckout(projectRoot, branch) }
                        ?: wrap(GitStateVerifier.verifyCheckout(output, branch))
                } ?: NOTHING
                "git_add", "git_stage" -> args.firstOf("paths", "path")?.let { raw ->
                    val paths = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    freshGit(projectRoot) { GitFreshVerifier.verifyStaging(projectRoot, paths) }
                        ?: wrap(GitStateVerifier.verifyStaging(output, paths))
                } ?: NOTHING
                "zip_list" -> wrap(ArchiveVerifier.verifyListing(output))
                // Extraction is checked on disk: the listing the tool printed is not the evidence.
                "zip_extract" -> {
                    val archive = resolve(projectRoot, args.firstOf("archive", "path", "file"))
                    val dest = resolve(projectRoot, args.firstOf("dest", "destination", "to"))
                    val onDisk = ArchiveDestinationVerifier.verify(archive, dest)
                    when (onDisk.result) {
                        ArchiveDestinationVerifier.Result.VERIFIED -> Verification(Result.VERIFIED, onDisk.note)
                        ArchiveDestinationVerifier.Result.REFUTED -> Verification(Result.REFUTED, onDisk.note)
                        ArchiveDestinationVerifier.Result.INCONCLUSIVE -> Verification(Result.INCONCLUSIVE, onDisk.note)
                        ArchiveDestinationVerifier.Result.NOT_CHECKABLE -> {
                            // Unknown is never success: if the disk cannot be read, fall back to the
                            // listing only to REFUTE, never to confirm.
                            val listing = ArchiveVerifier.verifyListing(output)
                            if (listing.result == ArchiveVerifier.Result.REFUTED) wrap(listing) else
                                Verification(Result.NOT_CHECKABLE, onDisk.note)
                        }
                    }
                }
                else -> {
                    val filesystem = SideEffectVerifier.verify(tool, args, projectRoot)
                    when (filesystem.result) {
                        SideEffectVerifier.Result.VERIFIED -> Verification(Result.VERIFIED, filesystem.note)
                        SideEffectVerifier.Result.REFUTED -> Verification(Result.REFUTED, filesystem.note)
                        SideEffectVerifier.Result.NOT_CHECKABLE -> NOTHING
                    }
                }
            }
        } catch (t: Throwable) {
            NOTHING
        }
    }

    /**
     * Fresh state when a project is open. A fresh read that cannot be made is reported as unknown
     * rather than quietly downgraded to the tool's own acknowledgement; only when no project is open
     * does the text parse stand in, and then it can still only refute.
     */
    private fun freshGit(projectRoot: String?, fresh: () -> GitStateVerifier.Outcome): Verification? {
        if (projectRoot.isNullOrBlank()) return null
        val outcome = fresh()
        return if (outcome.result == GitStateVerifier.Result.NOT_CHECKABLE) {
            Verification(Result.NOT_CHECKABLE, outcome.note)
        } else {
            wrap(outcome)
        }
    }

    /** Absolute paths are used as given; a relative one is resolved inside the open project. */
    private fun resolve(projectRoot: String?, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val file = java.io.File(value)
        if (file.isAbsolute) return value
        val root = projectRoot?.trim().orEmpty()
        if (root.isEmpty()) return null
        return java.io.File(root, value).absolutePath
    }

    private fun wrap(outcome: GitStateVerifier.Outcome): Verification = Verification(
        when (outcome.result) {
            GitStateVerifier.Result.VERIFIED -> Result.VERIFIED
            GitStateVerifier.Result.REFUTED -> Result.REFUTED
            GitStateVerifier.Result.INCONCLUSIVE -> Result.INCONCLUSIVE
            GitStateVerifier.Result.NOT_CHECKABLE -> Result.NOT_CHECKABLE
        },
        outcome.note,
    )

    private fun wrap(outcome: ArchiveVerifier.Outcome): Verification = Verification(
        when (outcome.result) {
            ArchiveVerifier.Result.VERIFIED -> Result.VERIFIED
            ArchiveVerifier.Result.REFUTED -> Result.REFUTED
            ArchiveVerifier.Result.INCONCLUSIVE -> Result.INCONCLUSIVE
            ArchiveVerifier.Result.NOT_CHECKABLE -> Result.NOT_CHECKABLE
        },
        outcome.note,
    )

    private fun JSONObject.firstOf(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** git_commit_push takes `message`; the subject is its first line. */
    private fun JSONObject.commitSubject(): String? =
        firstOf("message", "commit_message", "summary")?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

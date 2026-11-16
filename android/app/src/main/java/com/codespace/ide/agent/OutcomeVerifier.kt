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
                "git_status" -> wrap(GitStateVerifier.verifySettled(output))
                "git_commit_push", "git_commit" -> wrap(
                    GitStateVerifier.verifyCommit(output, args.commitSubject())
                )
                "git_checkout", "git_switch" -> args.firstOf("branch", "name")?.let {
                    wrap(GitStateVerifier.verifyCheckout(output, it))
                } ?: NOTHING
                "git_add", "git_stage" -> args.firstOf("paths", "path")?.let { raw ->
                    wrap(GitStateVerifier.verifyStaging(output, raw.split(",").map { it.trim() }))
                } ?: NOTHING
                "zip_list" -> wrap(ArchiveVerifier.verifyListing(output))
                "zip_extract" -> {
                    val listing = ArchiveVerifier.verifyListing(output)
                    if (listing.result == ArchiveVerifier.Result.REFUTED) wrap(listing) else wrap(listing)
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

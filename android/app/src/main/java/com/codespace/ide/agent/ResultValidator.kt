package com.codespace.ide.agent

import org.json.JSONObject

/**
 * PROJECT FOUR, STAGE 4.5 (2026-11-16): execution success is not task success.
 *
 * One place that answers "may this result be treated as evidence?", composed from the existing
 * [ToolResultCheck] plus a tool's [ToolOutputContract]. It never runs the tool, never retries and
 * never grants anything: it only says what was actually established, so a prerequisite cannot be
 * satisfied by a call that merely returned.
 */
object ResultValidator {

    enum class Outcome {
        /** Returned, and the output says the operation happened. */
        VALID,
        /** Returned empty and empty is a real answer here (no matches, empty directory). */
        EMPTY_VALID,
        /** Returned empty where content was required. */
        EMPTY_UNEXPECTED,
        /** Returned something, but not the shape the tool promises. */
        MALFORMED,
        /** Real output, cut off: real, but not the whole answer. */
        PARTIAL,
        /** Returned normally without acknowledging the change that was asked for. */
        UNACKNOWLEDGED,
        /** Clear failure. */
        FAILED,
        /** The validator itself could not decide. Never counted against the tool. */
        VALIDATOR_FAILED,
    }

    data class Validation(
        val outcome: Outcome,
        val reason: String,
        /** May this be used to satisfy a prerequisite that needs a complete result? */
        val satisfiesPrerequisite: Boolean,
        /** True when the operation was proved to have happened (completely or in part). */
        val establishesSomething: Boolean,
    ) {
        val validated: Boolean get() = outcome == Outcome.VALID || outcome == Outcome.EMPTY_VALID
        /** A genuine tool problem, as opposed to truncation or a validator bug. */
        val countsAgainstTool: Boolean
            get() = outcome == Outcome.FAILED || outcome == Outcome.MALFORMED || outcome == Outcome.UNACKNOWLEDGED ||
                outcome == Outcome.EMPTY_UNEXPECTED
        val name: String get() = outcome.name.lowercase().replace('_', ' ')
    }

    fun validate(
        tool: String,
        output: String,
        expectsJson: Boolean = false,
        threw: Throwable? = null,
    ): Validation {
        return try {
            val check = ToolResultCheck.check(tool, output, threw, expectsJson)
            val contract = ToolOutputContract.of(tool)
            val text = output.trim()

            // The base check's reading comes first: a clear failure is a failure whatever the
            // contract says, and a truncated result is partial even when the contract allows it.
            when (check.verdict) {
                ToolResultCheck.Verdict.FAILED ->
                    return Validation(Outcome.FAILED, check.reason, false, false)
                ToolResultCheck.Verdict.INCOMPLETE -> return Validation(
                    Outcome.PARTIAL,
                    check.reason + ", so it may not be the whole answer",
                    satisfiesPrerequisite = false,
                    establishesSomething = true,
                )
                ToolResultCheck.Verdict.MALFORMED ->
                    return Validation(Outcome.MALFORMED, check.reason, false, false)
                ToolResultCheck.Verdict.SUSPICIOUS_SUCCESS -> {
                    if (text.isEmpty()) {
                        return Validation(Outcome.EMPTY_UNEXPECTED, check.reason, false, false)
                    }
                    return Validation(Outcome.FAILED, check.reason, false, false)
                }
                else -> Unit
            }

            if (contract != null) {
                if (text.isEmpty() && !contract.emptyAllowed) {
                    return Validation(Outcome.EMPTY_UNEXPECTED, "no content came back from " + tool, false, false)
                }
                if (text.isEmpty()) {
                    return Validation(Outcome.EMPTY_VALID, "empty is a real answer for " + tool, true, true)
                }
                if (contract.jsonFields.isNotEmpty()) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return Validation(Outcome.MALFORMED, "expected a JSON object", false, false)
                    val missing = contract.jsonFields.filter { !json.has(it) }
                    if (missing.isNotEmpty()) {
                        return Validation(
                            Outcome.MALFORMED,
                            "the result is missing " + missing.joinToString(", "),
                            false,
                            true,
                        )
                    }
                }
                if (contract.acknowledgement.isNotEmpty()) {
                    val lower = text.lowercase()
                    val acked = contract.acknowledgement.any { lower.contains(it) }
                    if (!acked) {
                        return Validation(
                            Outcome.UNACKNOWLEDGED,
                            "it returned without confirming the change was made",
                            false,
                            false,
                        )
                    }
                }
            }

            if (check.verdict == ToolResultCheck.Verdict.LEGITIMATELY_EMPTY) {
                return Validation(Outcome.EMPTY_VALID, check.reason, true, true)
            }
            Validation(Outcome.VALID, check.reason, true, true)
        } catch (t: Throwable) {
            // A validator bug is never evidence about the tool.
            Validation(
                Outcome.VALIDATOR_FAILED,
                "the result could not be checked (" + (t::class.simpleName ?: "error") + ")",
                satisfiesPrerequisite = false,
                establishesSomething = false,
            )
        }
    }

    /** One line for the transcript, or empty when the result needs no caveat. */
    fun note(tool: String, validation: Validation): String = when (validation.outcome) {
        Outcome.VALID, Outcome.EMPTY_VALID -> ""
        Outcome.PARTIAL -> "Note: " + tool + " returned a partial result (" + validation.reason +
            "). Do not report the whole task as done."
        Outcome.EMPTY_UNEXPECTED -> "Note: " + tool + " succeeded but returned nothing: " + validation.reason +
            ". That is not evidence the task happened."
        Outcome.MALFORMED -> "Note: " + tool + " returned output in the wrong form (" + validation.reason +
            "). Do not parse it as a result."
        Outcome.UNACKNOWLEDGED -> "Note: " + tool + " ran, but " + validation.reason +
            ". Read it back before treating it as done."
        Outcome.FAILED -> "Note: " + validation.reason + ". The operation did not succeed."
        Outcome.VALIDATOR_FAILED -> "Note: the result could not be validated (" + validation.reason +
            "). Treat it as unproven rather than as either success or failure."
    }
}

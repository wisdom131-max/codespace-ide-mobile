package com.codespace.ide.agent.heal

import com.codespace.ide.agent.ToolFailurePolicy

/**
 * PROJECT 3.5 (2026-11-15): recovery that argues for itself, or does not happen.
 *
 * The audit found Brahma's `AutoHealManager.executeWithHealing` is optimistic: it classifies a failure
 * into one of six categories, rewrites the arguments (a package name, a launch Intent), retries up to
 * some count, and prepends `[Auto-Heal Recovered]: ` to the output. There is no visible approval gate,
 * no distinction between reading and writing, and the repair can launch an Intent. That is a capable
 * design and an unsafe one, and this project must not copy the second property.
 *
 * So recovery here produces a *plan with a verdict*, not an action:
 *
 *  - The original failure is always preserved verbatim. A recovery that hides the error it was
 *    recovering from has destroyed the only evidence the user needed.
 *  - Every repair is one of a small closed set, and each knows whether it may be applied without the
 *    owner. A read-only repair (re-read a path, resolve a stale path inside the project, narrow a
 *    range) is safe by construction. Anything that changes the project, touches a shell, or needs an
 *    approval is a proposal, never an automatic fix.
 *  - A call with a side effect whose outcome is unknown is never retried. That case belongs to the
 *    dispatch guard and its reconciler, because only they can establish what already happened.
 *  - Ask mode executes nothing at all, by owner's decision that Ask is read-only.
 *  - Attempts are bounded twice over: per failure, and per ledger. There is no loop to get into.
 *
 * Pure Kotlin: the caller applies the plan. This decides only what is safe and what is not.
 */
internal object RecoveryPlanner {

    /** Failure classes a repair can act on. Deliberately a superset of the retry policy's kinds. */
    enum class Category(val phrase: String) {
        INVALID_ARGUMENTS("the arguments were not accepted"),
        MISSING_RESOURCE("a file or resource it needs is not there"),
        PERMISSION("permission was refused"),
        TOOL_EXECUTION("the tool itself failed"),
        PROVIDER_NETWORK("the provider or network failed"),
        TIMEOUT("it timed out or was interrupted"),
        DEPENDENCY("something it depends on has not happened"),
        STATE_CONFLICT("the project changed underneath it"),
        RESOURCE_EXHAUSTION("a size, memory or quota limit was hit"),
        UNKNOWN("the failure was not recognised"),
        ;

        companion object {
            /** Map the existing retry taxonomy in, so there is one reading of an error message. */
            fun of(kind: ToolFailurePolicy.Kind): Category = when (kind) {
                ToolFailurePolicy.Kind.INVALID_ARGUMENTS -> INVALID_ARGUMENTS
                ToolFailurePolicy.Kind.PREREQUISITE -> MISSING_RESOURCE
                ToolFailurePolicy.Kind.PERMISSION -> PERMISSION
                ToolFailurePolicy.Kind.UNAVAILABLE_TOOL -> DEPENDENCY
                ToolFailurePolicy.Kind.TIMEOUT -> TIMEOUT
                ToolFailurePolicy.Kind.RESOURCE_LIMIT -> RESOURCE_EXHAUSTION
                ToolFailurePolicy.Kind.NETWORK -> PROVIDER_NETWORK
                ToolFailurePolicy.Kind.APPROVAL_NEEDED -> PERMISSION
                ToolFailurePolicy.Kind.UNKNOWN -> UNKNOWN
            }
        }
    }

    enum class RepairKind {
        /** Read the thing again; safe, read-only, and often the whole fix. */
        RE_READ,
        /** The path is stale. Look for a file with that name inside the project before failing. */
        RESOLVE_PATH,
        /** The request was larger than a limit allows. Ask for a narrower range. */
        NARROW_RANGE,
        /** A read-only call that failed transiently. One retry, never more. */
        RETRY_READ,
        /** A read-only call after a wait, for rate limits and provider hiccups. */
        RETRY_AFTER_WAIT,
        /** Needs the owner: it changes something, needs approval, or the outcome is unknown. */
        REQUEST_APPROVAL,
        /** Ask for a decision because the repair is not ours to choose. */
        ASK_USER,
        /** Nothing safe to do. Report it, with the original error, and stop. */
        REPORT_ONLY,
    }

    enum class Mode { ASK, EDIT, AGENT }

    data class Failure(
        val tool: String,
        val error: String,
        val category: Category,
        /** True when the call changes the project, the repository or stored state. */
        val sideEffecting: Boolean = false,
        /** False when it may have reached the side effect before failing. */
        val outcomeKnown: Boolean = true,
        val attemptsSoFar: Int = 0,
        val mode: Mode = Mode.AGENT,
    )

    data class Repair(
        val kind: RepairKind,
        val description: String,
        /** True only for a repair that cannot change the project and needs nothing from the owner. */
        val autoApply: Boolean,
        val needsApproval: Boolean,
    ) {
        val isProposal: Boolean get() = !autoApply
    }

    data class Plan(
        val category: Category,
        val cause: String,
        val repairs: List<Repair>,
        /** The failure exactly as it arrived. Never rewritten by a repair. */
        val original: String,
        val tool: String,
        /** Why nothing was applied automatically, when that is the case. */
        val withheld: String = "",
    ) {
        /** The one repair that may be applied without asking, if any. */
        fun auto(): Repair? = repairs.firstOrNull { it.autoApply }
        val needsUser: Boolean get() = repairs.any { it.needsApproval || it.kind == RepairKind.ASK_USER }
        fun summary(): String = buildString {
            append(category.phrase).append(": ").append(cause)
            val auto = repairs.firstOrNull { it.autoApply }
            if (auto != null) {
                append("\nrecovery: ").append(auto.description).append(" (applied automatically)")
            }
            repairs.filter { it.isProposal && it.kind != RepairKind.REPORT_ONLY }.forEach {
                append("\nproposed: ").append(it.description)
            }
            if (repairs.any { it.kind == RepairKind.REPORT_ONLY }) {
                append("\nno safe repair; the failure above stands")
            }
            if (withheld.isNotBlank()) append("\nnot applied automatically: ").append(withheld)
        }
    }

    /** One automatic repair per failure. A second attempt is not a second chance, it is a loop. */
    const val MAX_AUTO_REPAIRS = 1

    /** Classify from the raw error, reusing the retry policy's reading of the message. */
    fun classify(failure: Failure): Category {
        if (failure.category != Category.UNKNOWN) return failure.category
        return Category.of(ToolFailurePolicy.classify(failure.error).kind)
    }

    fun plan(failure: Failure, repairsAlreadyApplied: Int = 0): Plan {
        val category = classify(failure)
        val original = failure.error.trim().ifBlank { "the tool failed without a message" }
        val writey = failure.sideEffecting || !failure.outcomeKnown
        val outOfAttempts = repairsAlreadyApplied >= MAX_AUTO_REPAIRS || failure.attemptsSoFar >= 2
        val askMode = failure.mode == Mode.ASK

        fun safe(kind: RepairKind, why: String): Repair {
            // Three independent reasons a repair is not automatic: it writes (or may have written),
            // its budget is spent, or the session is Ask, where nothing executes.
            val auto = !writey && !outOfAttempts && !askMode
            return Repair(kind, why, autoApply = auto, needsApproval = !auto)
        }

        val (cause, repairs) = when (category) {
            Category.INVALID_ARGUMENTS -> "the call did not match what the tool accepts" to listOf(
                Repair(RepairKind.REQUEST_APPROVAL,
                    "Re-read the tool's schema and make one corrected call with the arguments it declares.",
                    autoApply = false, needsApproval = true),
            )
            Category.MISSING_RESOURCE -> "the path or resource does not exist as given" to listOf(
                safe(RepairKind.RESOLVE_PATH, "Find a file with that name inside the project and retry the read at the real path."),
                Repair(RepairKind.ASK_USER, "If no such file exists, confirm what was meant instead of creating one.", false, true),
            )
            Category.PERMISSION -> "the device or the approval policy refused this" to listOf(
                Repair(RepairKind.REQUEST_APPROVAL,
                    "This needs the owner's approval. Nothing is retried and nothing is worked around.",
                    autoApply = false, needsApproval = true),
            )
            Category.TOOL_EXECUTION -> "the tool failed while doing its own work" to listOf(
                safe(RepairKind.RE_READ, "Re-read the target to establish the state the failed call left behind."),
                Repair(RepairKind.REPORT_ONLY, "Report the tool failure with its message rather than guessing.", false, false),
            )
            Category.PROVIDER_NETWORK -> "the provider or network was unreachable" to listOf(
                safe(RepairKind.RETRY_AFTER_WAIT, "Wait briefly and retry the call once."),
            )
            Category.TIMEOUT -> "the call ran out of time or was interrupted" to listOf(
                safe(RepairKind.RETRY_READ, "Retry once, and narrow the request if it times out again."),
            )
            Category.DEPENDENCY -> "a step this depends on has not happened" to listOf(
                Repair(RepairKind.ASK_USER,
                    "Do the prerequisite first, then this step becomes runnable. Do not fake its result.",
                    autoApply = false, needsApproval = true),
            )
            Category.STATE_CONFLICT -> "the project changed while this ran" to listOf(
                safe(RepairKind.RE_READ, "Re-read the changed target before relying on the earlier result."),
                Repair(RepairKind.ASK_USER, "If the change was yours, say which version should win.", false, true),
            )
            Category.RESOURCE_EXHAUSTION -> "a size or memory limit was hit" to listOf(
                safe(RepairKind.NARROW_RANGE, "Ask for a range, a page or a smaller dimension instead of the whole thing."),
            )
            Category.UNKNOWN -> "the failure was not recognised" to listOf(
                Repair(RepairKind.REPORT_ONLY,
                    "Report it plainly, with the message, and stop. An unrecognised failure is not a retry candidate.",
                    autoApply = false, needsApproval = false),
            )
        }

        val adjusted = if (writey) {
            repairs.map { repair ->
                if (repair.autoApply) {
                    // A write, or a call whose outcome is unknown, is never repaired automatically.
                    repair.copy(
                        autoApply = false,
                        needsApproval = true,
                        description = repair.description + " (not applied: this call changes the project " +
                            "or its outcome is unknown, so the owner decides)",
                    )
                } else {
                    repair
                }
            }
        } else {
            repairs
        }
        val withheld = if (writey) {
            "this call changes the project or its outcome is unknown, so the owner decides"
        } else if (askMode) {
            "Ask mode executes nothing; the repairs above are proposals"
        } else {
            ""
        }
        return Plan(
            category = category,
            cause = cause,
            repairs = adjusted,
            original = original,
            tool = failure.tool,
            withheld = withheld,
        )
    }

    /**
     * Whether a repair may be executed now. The second gate: even an `autoApply` repair is refused
     * when the mode forbids execution or the ledger says this failure has been repaired already.
     */
    fun mayExecute(plan: Plan, mode: Mode, repairsAlreadyApplied: Int, cancelled: Boolean = false): Boolean {
        if (mode == Mode.ASK) return false
        if (cancelled) return false
        if (repairsAlreadyApplied >= MAX_AUTO_REPAIRS) return false
        val auto = plan.auto() ?: return false
        return auto.autoApply && !auto.needsApproval
    }

    /** A repair is only a success when something was observed after it. */
    fun verdict(repair: Repair, evidence: String): Verdict {
        val text = evidence.trim()
        if (!repair.autoApply) return Verdict.Proposed
        if (text.isEmpty()) {
            return Verdict.Failed("the repair ran but produced no evidence; the original failure stands")
        }
        val lowered = text.lowercase()
        val stillBroken = listOf("still fails", "still failing", "same error", "failed again", "not found")
            .any { lowered.contains(it) }
        return if (stillBroken) {
            Verdict.Failed("the repair completed but the evidence shows the same problem: " + text.take(160))
        } else {
            Verdict.Recovered(text.take(240))
        }
    }

    sealed class Verdict {
        data class Recovered(val evidence: String) : Verdict()
        data class Failed(val reason: String) : Verdict()
        object Proposed : Verdict()
    }

    /**
     * The audit trail. Recovery that cannot be read back is indistinguishable from a guess, so every
     * attempt keeps the original error, what was tried, and what was seen afterwards.
     */
    class Ledger(private val cap: Int = 40) {
        data class Entry(
            val tool: String,
            val category: Category,
            val repair: RepairKind,
            val original: String,
            val outcome: String,
            val recovered: Boolean,
            val seq: Int,
        )

        private val entries = ArrayList<Entry>()
        private var seq = 0

        fun record(plan: Plan, repair: Repair, outcome: String, recovered: Boolean): Entry {
            seq += 1
            val entry = Entry(
                tool = plan.tool,
                category = plan.category,
                repair = repair.kind,
                original = plan.original,
                outcome = outcome,
                recovered = recovered,
                seq = seq,
            )
            entries.add(entry)
            while (entries.size > cap) entries.removeAt(0)
            return entry
        }

        fun attemptsFor(fingerprint: String): Int = entries.count { it.original == fingerprint }

        fun all(): List<Entry> = entries.toList()

        fun lines(): List<String> = entries.map { entry ->
            (if (entry.recovered) "recovered" else "not recovered") + ": " + entry.tool +
                " (" + entry.category.phrase + ") via " + entry.repair.name.lowercase() +
                " - " + entry.outcome.take(120)
        }
    }
}

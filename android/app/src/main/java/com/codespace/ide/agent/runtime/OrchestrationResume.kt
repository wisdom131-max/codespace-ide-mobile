package com.codespace.ide.agent.runtime

/**
 * STAGE 4.3: what to do with work that was interrupted.
 *
 * The dangerous case is not a step that never started, it is a write that may or may not have
 * landed. A missing result is not evidence that nothing happened, so this classifies the persisted
 * record instead of re-running it:
 *
 *  - a recorded success with a side effect is DONE: repeating it is the duplicate this exists to stop;
 *  - a recorded success that changed nothing can be repeated cheaply, so it is RETRY_SAFE;
 *  - a failure that changed nothing is RETRY_SAFE; a failure with a side effect needs the owner;
 *  - anything recorded under another conversation or plan revision is BLOCKED, because permission and
 *    context do not travel with a file.
 */
internal object OrchestrationResume {

    enum class Disposition { DONE, RETRY_SAFE, NEEDS_APPROVAL, BLOCKED }

    data class Step(val capability: String, val tool: String, val disposition: Disposition, val why: String)

    data class Report(val steps: List<Step>) {
        fun resumable(): List<Step> = steps.filter { it.disposition == Disposition.RETRY_SAFE }
        fun settled(): List<Step> = steps.filter { it.disposition == Disposition.DONE }
        fun needsOwner(): List<Step> = steps.filter { it.disposition == Disposition.NEEDS_APPROVAL }
        fun blocked(): List<Step> = steps.filter { it.disposition == Disposition.BLOCKED }
        fun lines(): List<String> = steps.map { it.capability + ": " + it.disposition.name.lowercase().replace('_', ' ') + " (" + it.why + ")" }
    }

    fun plan(
        entries: Collection<PrereqLedger.Entry>,
        session: String,
        revision: Int,
    ): Report {
        val steps = entries.map { entry ->
            val why: String
            val disposition = when {
                entry.session.isNotBlank() && session.isNotBlank() && entry.session != session -> {
                    why = "it was proved in another conversation"
                    Disposition.BLOCKED
                }
                entry.revision > 0 && revision > 0 && entry.revision != revision -> {
                    why = "the plan has been revised since; the evidence may not describe it"
                    Disposition.BLOCKED
                }
                entry.failed && entry.sideEffect -> {
                    why = "it failed after it may have changed something; confirm before trying again"
                    Disposition.NEEDS_APPROVAL
                }
                entry.failed -> {
                    why = "it failed without changing anything, so it can be tried again"
                    Disposition.RETRY_SAFE
                }
                entry.sideEffect -> {
                    why = "it already succeeded and changed something; repeating it would duplicate that"
                    Disposition.DONE
                }
                else -> {
                    why = "it succeeded and changed nothing; repeating it is cheap"
                    Disposition.RETRY_SAFE
                }
            }
            Step(entry.capability, entry.solvedBy, disposition, why)
        }
        return Report(steps)
    }
}

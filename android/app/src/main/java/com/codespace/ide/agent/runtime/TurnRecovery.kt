package com.codespace.ide.agent.runtime

import com.codespace.ide.agent.goal.TaskPlanEngine

/**
 * PROJECT FOUR, STAGE 4.4 (2026-11-16): what an interrupted turn left behind.
 *
 * Stage 4.3 made delegated waves resumable. A chat turn had no such answer: a process death in the
 * middle of a plan left steps marked RUNNING with no honest statement about what had happened, and
 * a user reopening the conversation could not tell a finished write from one that never ran.
 *
 * This object is a report, not an engine. It reads the plan and the durable ledger and sorts what it
 * finds into four answers, reusing [OrchestrationResume] rather than inventing a second classifier.
 */
internal object TurnRecovery {

    enum class Kind { PROVEN, UNKNOWN, OPEN, BLOCKED }

    data class Line(val kind: Kind, val text: String)

    data class Status(
        val lines: List<Line>,
        /** True when continuing safely needs the owner, because a write may have landed. */
        val needsOwner: Boolean,
        val resumed: Int,
        val unknown: Int,
        val unfinishedSteps: List<String>,
    ) {
        val quiet: Boolean get() = lines.isEmpty()
    }

    fun report(
        plan: TaskPlanEngine.Plan?,
        prior: List<PrereqLedger.Entry>,
        session: String,
        revision: Int,
    ): Status {
        val resume = OrchestrationResume.plan(prior, session, revision)
        val failedByCapability = prior.groupBy { it.capability }.mapValues { pair ->
            pair.value.any { it.failed }
        }
        val out = ArrayList<Line>()
        resume.settled().forEach { out.add(Line(Kind.PROVEN, it.capability + " is proved: " + it.why)) }
        // A read that succeeded is proved as well as cheap to repeat; the resume classifier calls it
        // retry-safe for the orchestrator's benefit, which is a different question from "did it
        // happen". Only a FAILURE under that label means there is nothing to show.
        resume.resumable().forEach {
            if (failedByCapability[it.capability] != true) {
                out.add(Line(Kind.PROVEN, it.capability + " is proved by " + it.tool + ": " + it.why))
            }
        }
        resume.needsOwner().forEach {
            out.add(Line(Kind.UNKNOWN, it.capability + " may already have happened: " + it.why +
                ". Confirm it before it runs again."))
        }
        resume.resumable().forEach { out.add(Line(Kind.OPEN, it.capability + " can run again safely: " + it.why)) }
        resume.blocked().forEach { out.add(Line(Kind.BLOCKED, it.capability + " cannot be reused: " + it.why)) }
        val open = plan?.steps?.filter {
            it.status == TaskPlanEngine.Status.RUNNING || it.status == TaskPlanEngine.Status.PENDING
        }?.map { it.id + " '" + it.title + "'" }.orEmpty()
        open.forEach { out.add(Line(Kind.OPEN, "step " + it + " was not finished")) }
        return Status(
            lines = out,
            needsOwner = resume.needsOwner().isNotEmpty(),
            resumed = resume.resumable().size,
            unknown = resume.needsOwner().size,
            unfinishedSteps = open,
        )
    }

    /** One paragraph for the transcript: what survived, what is uncertain, what is left. */
    fun note(status: Status): String? {
        if (status.lines.isEmpty()) return null
        val proven = status.lines.count { it.kind == Kind.PROVEN }
        val parts = ArrayList<String>()
        if (proven > 0) parts.add(proven.toString() + " thing(s) are proved by their evidence")
        if (status.unknown > 0) parts.add(status.unknown.toString() +
            " change(s) may or may not have landed and need confirmation before anything repeats them")
        if (status.unfinishedSteps.isNotEmpty()) parts.add(status.unfinishedSteps.size.toString() + " step(s) never finished")
        if (parts.isEmpty()) return null
        return "This turn was interrupted. " + parts.joinToString("; ") + "."
    }
}

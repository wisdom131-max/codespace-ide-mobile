package com.codespace.ide.agent.goal

/**
 * PROJECT 3.2 (2026-11-15): the plan that outlives one tool call.
 *
 * [TaskPlanEngine] is pure and stateless; something has to hold the plan between the model's calls,
 * and that something must be scoped to a conversation rather than to the process, or two sessions
 * would overwrite each other's plan and both would look finished.
 *
 * Deliberately not persisted to disk yet: a plan describes work in flight, and a plan restored after
 * a restart would claim steps are running that nothing is running. When the resume path lands it
 * will save the plan alongside the transcript.
 */
object PlanState {

    private val plans = HashMap<String, TaskPlanEngine.Plan>()
    private var currentSession: String? = null

    /** The chat surface tells this store which conversation it is showing, once. */
    fun claim(sessionId: String) {
        currentSession = sessionId
    }

    fun plan(sessionId: String? = currentSession): TaskPlanEngine.Plan? =
        sessionId?.let { plans[it] }

    fun put(sessionId: String?, plan: TaskPlanEngine.Plan) {
        val key = sessionId ?: currentSession ?: return
        plans[key] = plan
    }

    fun clear(sessionId: String? = currentSession) {
        sessionId?.let { plans.remove(it) }
    }

    /** The visible lines for the transcript: what is done, what is next and what is dead. */
    fun lines(sessionId: String? = currentSession): List<String> =
        plan(sessionId)?.lines().orEmpty()

    fun summary(sessionId: String? = currentSession): String {
        val plan = plan(sessionId) ?: return "No plan is active."
        val progress = plan.progress()
        val milestones = plan.milestones()
            .joinToString(", ") { it.name + " " + it.done + "/" + it.total }
        return plan.objective + " — " + progress.summary() + " (revision " + plan.revision + ")" +
            (if (milestones.isBlank()) "" else "; milestones: " + milestones)
    }
}

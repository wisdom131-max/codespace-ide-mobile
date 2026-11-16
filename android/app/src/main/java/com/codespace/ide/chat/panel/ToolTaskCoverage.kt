package com.codespace.ide.chat.panel

/**
 * PROJECT FOUR, STAGE 4.2 (2026-11-16): a whole request, not one capability at a time.
 *
 * Stage 4.1 could say "this capability needs a tool you do not have". It could not say whether the
 * REQUEST as a whole is doable, which is what a user actually asked. This object takes the same
 * [ToolCapabilityPlan] assessment, adds the dependency graph from [DependencyPlanner], and answers
 * three questions in one shape:
 *
 *  1. what can be done now, and in what order;
 *  2. what cannot be done, and the specific reason;
 *  3. whether the complete request is executable, or only part of it.
 *
 * It does not duplicate the capability catalogue or the prerequisites: both are read from the objects
 * that own them. Health is used only to break ties between tools that are already suitable, so a
 * healthy but wrong tool can never be chosen.
 */
internal object ToolTaskCoverage {

    data class Report(
        val assessment: ToolCapabilityPlan.Assessment,
        val plan: DependencyPlanner.Plan,
        /** Capabilities the request needs, in dependency order. */
        val order: List<String>,
        val canDo: List<String>,
        val cannotDo: List<String>,
        val reasons: List<String>,
        val executable: Boolean,
    ) {
        /** One block for the transcript: what will run, what will not, and why. */
        fun lines(): List<String> {
            val out = ArrayList<String>()
            if (order.isNotEmpty()) out.add("order: " + order.joinToString(" -> "))
            canDo.forEach { out.add("can do: " + it) }
            cannotDo.forEach { out.add("cannot do: " + it) }
            reasons.forEach { out.add("- " + it) }
            if (!executable) out.add("this request cannot be completed as asked")
            return out
        }
    }

    fun report(
        request: String,
        mode: ChatMode,
        offered: Set<String>,
        satisfied: Set<String> = com.codespace.ide.agent.runtime.PrereqLedger.satisfiedSet(),
        failed: Set<String> = com.codespace.ide.agent.runtime.PrereqLedger.failedSet(),
        assess: (String, ChatMode, Set<String>) -> ToolCapabilityPlan.Assessment =
            { r, m, o -> ToolCapabilityPlan.assess(r, m, o) },
        prereqs: (String) -> List<ToolPrerequisites.Prereq> = { ToolPrerequisites.prereqsOf(it) },
    ): Report {
        val assessment = assess(request, mode, offered)
        val capabilities = assessment.needs.map { it.id }
        // What this mode can actually reach: offered now, or loadable on demand. A tool that only
        // another mode exposes is not available here, and the report says so rather than pretending.
        val available = assessment.findings
            .filter { it.state == ToolCapabilityPlan.State.OFFERED || it.state == ToolCapabilityPlan.State.LOADABLE }
            .map { it.need.id }
            .toSet()
        val plan = DependencyPlanner.plan(capabilities, satisfied, available, failed, prereqs)
        val order = plan.waves.flatten()
        val canDo = plan.nodes
            .filter { it.state == DependencyPlanner.State.READY || it.state == DependencyPlanner.State.WAITING }
            .map { it.capability }
        val blocked = plan.nodes.filter {
            it.state == DependencyPlanner.State.IMPOSSIBLE || it.state == DependencyPlanner.State.BLOCKED
        }
        val reasons = ArrayList<String>(plan.notes)
        assessment.findings.forEach { finding ->
            when (finding.state) {
                ToolCapabilityPlan.State.MISSING ->
                    reasons.add("nothing here can do " + finding.need.label)
                ToolCapabilityPlan.State.OTHER_MODE ->
                    reasons.add(finding.need.label + " needs " + finding.tool + ", only exposed in " +
                        (finding.mode ?: "another mode"))
                ToolCapabilityPlan.State.LOADABLE ->
                    reasons.add(finding.need.label + " is available if " + finding.tool + " is loaded")
                ToolCapabilityPlan.State.OFFERED -> Unit
            }
        }
        return Report(
            assessment = assessment,
            plan = plan,
            order = order,
            canDo = canDo,
            cannotDo = blocked.map { it.capability },
            reasons = reasons.distinct(),
            executable = plan.executable && assessment.covered,
        )
    }

    /**
     * Which of several suitable tools to use. Suitability is decided by the caller (the candidates
     * already serve the same capability); this only breaks the tie, preferring one this turn can use
     * and then the healthier one. It never chooses a tool that serves a different capability, and it
     * never removes a tool from consideration.
     */
    fun choose(
        candidates: List<String>,
        offered: Set<String>,
        health: (String) -> Int = { com.codespace.ide.chat.panel.ToolHealth.adjustment(it) },
    ): String {
        if (candidates.isEmpty()) return ""
        return candidates.withIndex().maxWithOrNull(
            compareBy(
                { if (it.value in offered) 1 else 0 },
                { health(it.value) },
                { -it.index },
            ),
        )!!.value
    }
}

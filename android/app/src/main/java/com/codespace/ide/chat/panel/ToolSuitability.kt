package com.codespace.ide.chat.panel

import com.codespace.ide.agent.ToolOutputContract
import com.codespace.ide.agent.runtime.PrereqLedger
import com.codespace.ide.chat.panel.ToolPrerequisites

/**
 * PROJECT FOUR, STAGE 4.6 (2026-11-16): choosing between tools that could all do the job.
 *
 * [ToolCapabilityPlan] answers "is anything here able to do this?". This answers the harder question:
 * of the tools that CAN, which one SHOULD be used, and why.
 *
 * Two layers, kept apart on purpose:
 *
 *  - [eligible] is mandatory. A tool that is not offered in this mode, is not registered, or does not
 *    belong to the capability is out, whatever its score. A score can never buy back eligibility, so
 *    ranking can never become a second permission system.
 *  - [score] is a preference, made of named signals with their reasons attached. Every weight is
 *    documented where it is declared, and unknown facts stay unknown instead of being guessed into
 *    a number.
 *
 * Nothing here executes, approves or blocks a call. `AgentTools.executeTool` remains the only gate.
 */
internal object ToolSuitability {

    /** One named reason for a candidate's score, kept so the choice can be explained. */
    data class Signal(val name: String, val weight: Int, val earned: Int, val why: String)

    data class Candidate(
        val tool: String,
        val score: Int,
        val signals: List<Signal>,
        val eligible: Boolean,
        val ineligibleBecause: String? = null,
    ) {
        val explanation: String
            get() = if (!eligible) {
                tool + " is not eligible: " + (ineligibleBecause ?: "it cannot be used here")
            } else {
                tool + " scores " + score + ": " +
                    signals.filter { it.earned != 0 }.joinToString(", ") { it.name + " (" + it.why + ")" }
            }
    }

    data class Choice(
        val best: Candidate?,
        val ranked: List<Candidate>,
        val alternatives: List<Candidate>,
        /** Said only when no candidate is eligible: why nothing can be chosen. */
        val refusal: String?,
    ) {
        fun explain(): String = when {
            refusal != null -> refusal
            best == null -> "No tool was proposed for this."
            alternatives.isEmpty() -> best.explanation
            else -> best.explanation + ". Alternatives: " + alternatives.joinToString("; ") { it.explanation }
        }
    }

    // Weights exist to express a documented preference, not to look precise. Capability fit dominates
    // because a capable tool with a poor score still does the job, while a healthy tool that cannot do
    // it does nothing at all.
    private const val W_CAPABILITY = 40      // it belongs to the capability the request needs
    private const val W_TERMS = 20           // the request's own words appear in its name or description
    private const val W_CONTRACT = 10        // its output can be validated, so evidence will be trustworthy
    private const val W_PREREADY = 10        // what it depends on is already proved in this plan
    private const val W_HEALTH = 15          // recent real failures
    private const val W_DESTRUCTIVE = 8      // a read-only path is preferable when both work

    /**
     * @param capability the capability the request needs, when it is known.
     */
    fun rank(
        request: String,
        candidates: List<String>,
        mode: ChatMode,
        capability: String? = null,
        isOffered: (String) -> Boolean = { name -> ToolRegistry.allows(mode, name) },
        exists: (String) -> Boolean = { name -> ToolRegistry.spec(name) != null },
        healthPenalty: (String) -> Int = { name -> ToolHealth.adjustment(name) },
        demoted: Set<String> = ToolHealth.demoted(),
    ): Choice {
        val terms = request.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 4 }.toSet()
        val scored = candidates.distinct().map { name ->
            val spec = ToolRegistry.spec(name)
            val reasons = ArrayList<Signal>()
            if (spec == null) {
                return@map Candidate(name, 0, emptyList(), false, "no such tool is registered")
            }
            if (!exists(name)) {
                return@map Candidate(name, 0, emptyList(), false, "the registry says this tool is not available")
            }
            if (!isOffered(name)) {
                return@map Candidate(name, 0, emptyList(), false, mode.name.lowercase() + " mode does not offer it")
            }
            val declared = capability ?: ToolPrerequisites.capabilityOf(name)
            if (capability != null && ToolPrerequisites.capabilityOf(name) != null &&
                ToolPrerequisites.capabilityOf(name) != capability
            ) {
                return@map Candidate(name, 0, emptyList(), false, "it serves " + ToolPrerequisites.capabilityOf(name) + ", not " + capability)
            }
            if (declared != null) {
                reasons.add(Signal("capability", W_CAPABILITY, W_CAPABILITY, "it provides " + declared))
            } else {
                reasons.add(Signal("capability", W_CAPABILITY, 0, "its capability is unknown, so no credit is given"))
            }
            val haystack = (spec.name + " " + spec.displayName + " " + spec.description).lowercase()
            val hits = terms.filter { haystack.contains(it) }
            if (hits.isNotEmpty()) {
                reasons.add(Signal("task words", W_TERMS, minOf(W_TERMS, hits.size * 7), "the request mentions " + hits.take(3).joinToString(", ")))
            }
            val contract = ToolOutputContract.of(name)
            if (contract != null) {
                reasons.add(Signal("output contract", W_CONTRACT, W_CONTRACT, "its result can be validated"))
            }
            val prereqs = if (declared != null) ToolPrerequisites.requiredOf(declared) else emptyList()
            val ready = prereqs.filter { PrereqLedger.satisfied(it.of) }
            if (prereqs.isNotEmpty()) {
                val ratio = ready.size * W_PREREADY / prereqs.size
                reasons.add(Signal("prerequisites", W_PREREADY, ratio, ready.size.toString() + " of " + prereqs.size + " already proved"))
            } else {
                reasons.add(Signal("prerequisites", W_PREREADY, W_PREREADY, "it needs nothing first"))
            }
            val penalty = healthPenalty(name).coerceAtMost(W_HEALTH)
            if (penalty > 0) {
                reasons.add(Signal("health", W_HEALTH, -penalty, "recent failures, counted but never disqualifying"))
            } else {
                reasons.add(Signal("health", W_HEALTH, W_HEALTH, "no recent failures"))
            }
            val destructive = ToolMetaIndex.metaFor(spec).destructive
            if (destructive) {
                reasons.add(Signal("safer alternative", W_DESTRUCTIVE, -W_DESTRUCTIVE, "it can change or remove things"))
            }
            val total = reasons.sumOf { it.earned }
            Candidate(name, total, reasons, true)
        }.sortedWith(compareByDescending<Candidate> { it.eligible }.thenByDescending { it.score }.thenBy { it.tool })

        val eligible = scored.filter { it.eligible }
        val best = eligible.firstOrNull()
        return Choice(
            best = best,
            ranked = scored,
            alternatives = eligible.drop(1),
            refusal = when {
                candidates.isEmpty() -> null
                eligible.isEmpty() -> "None of the proposed tools can run here: " +
                    scored.take(3).joinToString("; ") { it.explanation }
                else -> null
            },
        )
    }

    /** The one-line note for a plan block, or null when there is nothing worth saying. */
    fun note(choice: Choice, demoted: Set<String> = ToolHealth.demoted()): String? {
        val best = choice.best ?: return choice.refusal
        val flagged = listOfNotNull(
            best.takeIf { it.score < 0 }?.let { "the best available choice is a poor fit (" + it.score + ")" },
            best.takeIf { it.tool in demoted }?.let { it.tool + " has a poor record but nothing better fits" },
        )
        return if (flagged.isEmpty()) null else flagged.joinToString("; ")
    }
}

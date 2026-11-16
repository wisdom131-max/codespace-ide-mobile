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
    private const val W_COST = 10            // a cheaper tool of equal fit is preferable
    private const val W_NETWORK = 8          // no network needed today is preferable
    private const val W_TIME = 6             // a faster tool of equal fit is preferable
    private const val W_MEMORY = 4           // a lighter tool is preferable on a phone
    private const val W_PROVIDER = 6         // a tool whose provider is connected is preferable

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
        /** Declared cost class; null is "not known", never a guess. */
        costOf: (String) -> ToolCost? = { name -> ToolRegistry.spec(name)?.let { ToolMetaIndex.metaFor(it).cost } },
        /** True when the tool reaches the network, false when it cannot, null when nobody declared it. */
        needsNetwork: (String) -> Boolean? = { name -> NetworkNeed.of(name) },
        /** Whether the network is usable right now. Null is unknown, which earns nothing and claims nothing. */
        networkAvailable: Boolean? = null,
        /** Whether the tool's provider is connected. Null is unknown, never assumed either way. */
        providerConnected: (String) -> Boolean? = { _ -> null },
        /** Remaining time budget for this turn, when the session has one. */
        timeBudgetMs: Long? = null,
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
            // STAGE 4.7: cost, network, time, memory and provider. Every one of these can be unknown,
            // and an unknown earns nothing rather than being guessed into a preference.
            val cost = costOf(name)
            if (cost == null) {
                reasons.add(Signal("cost", W_COST, 0, "its cost is unknown"))
            } else {
                val earned = when (cost) {
                    ToolCost.CHEAP -> W_COST
                    ToolCost.NORMAL -> W_COST / 2
                    ToolCost.HEAVY -> 0
                }
                reasons.add(Signal("cost", W_COST, earned, "declared as " + cost.name.lowercase()))
            }
            val network = needsNetwork(name)
            when {
                network == false -> reasons.add(Signal("network", W_NETWORK, W_NETWORK, "it works without the network"))
                network == true && networkAvailable == true ->
                    reasons.add(Signal("network", W_NETWORK, W_NETWORK, "it needs the network, which is up"))
                network == true && networkAvailable == false ->
                    reasons.add(Signal("network", W_NETWORK, 0, "it needs the network, which is not available"))
                else -> reasons.add(Signal("network", W_NETWORK, 0, "its network requirement is unknown"))
            }
            if (cost != null) {
                val earned = when (cost) {
                    ToolCost.CHEAP -> W_TIME
                    ToolCost.NORMAL -> W_TIME / 2
                    ToolCost.HEAVY -> 0
                }
                val why = if (timeBudgetMs != null && timeBudgetMs < 15_000 && cost == ToolCost.HEAVY) {
                    "heavy, and little time is left in this turn"
                } else {
                    "declared as " + cost.name.lowercase()
                }
                reasons.add(Signal("time", W_TIME, earned, why))
                reasons.add(
                    Signal(
                        "resources",
                        W_MEMORY,
                        if (cost == ToolCost.HEAVY) 0 else W_MEMORY,
                        if (cost == ToolCost.HEAVY) "heavy on a phone" else "light enough for this device",
                    )
                )
            }
            val provider = providerConnected(name)
            when (provider) {
                true -> reasons.add(Signal("provider", W_PROVIDER, W_PROVIDER, "its provider is connected"))
                false -> reasons.add(Signal("provider", W_PROVIDER, 0, "its provider is not connected, so a call would fail"))
                null -> reasons.add(Signal("provider", W_PROVIDER, 0, "provider availability is unknown"))
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

    /** Which tools reach the network, declared once so the signal never guesses per call. */
    internal object NetworkNeed {
        private val LOCAL_ONLY = setOf(
            "read_file", "write_file", "replace_string_in_file", "multi_replace_string_in_file",
            "grep_search", "grep_replace_all", "file_search", "list_dir", "create_directory",
            "run_command", "runTests", "git_status", "git_log", "git_diff", "git_commit_push",
            "zip_list", "zip_create", "zip_extract", "get_diagnostics",
        )

        private val NETWORK = setOf(
            "web_search", "web_fetch", "fetch_url", "http_request", "connector_call", "mcp_call",
            "research_search", "github_api", "slack_message",
        )

        fun of(tool: String): Boolean? = when (tool) {
            in LOCAL_ONLY -> false
            in NETWORK -> true
            else -> {
                val spec = ToolRegistry.spec(tool)
                if (spec == null) null else when (ToolMetaIndex.metaFor(spec).category) {
                    ToolCategory.WEB, ToolCategory.CONNECTORS, ToolCategory.MCP -> true
                    else -> null
                }
            }
        }
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

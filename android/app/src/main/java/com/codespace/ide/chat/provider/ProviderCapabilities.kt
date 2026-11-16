package com.codespace.ide.chat.provider

/**
 * PROJECT FIVE, STAGE 5.1 — what a provider/model can actually do, stated once and honestly.
 *
 * Everything here is DECLARED knowledge, not probed knowledge. A field that has not been confirmed
 * from the provider's own documentation is `null`, not a guess: an invented context window is worse
 * than an unknown one, because a selector would then size a request against a number nobody checked.
 * `verified` says which of the two you are looking at.
 */
enum class ProviderCapability { TOOL_CALLS, STREAMING, STRUCTURED_OUTPUT, REASONING, VISION, CANCELLATION }

data class ModelLimits(
    val contextTokens: Int?,
    val maxOutputTokens: Int?,
) {
    /** True when both numbers are known, so a request can be sized without guessing. */
    val complete: Boolean get() = contextTokens != null && maxOutputTokens != null
}

data class ModelProfile(
    val providerId: String,
    val modelId: String,
    val limits: ModelLimits,
    val capabilities: Set<ProviderCapability>,
    /** True when these facts came from the provider's own documentation and were checked. */
    val verified: Boolean,
    val notes: List<String> = emptyList(),
) {
    val label: String get() = "$providerId/$modelId"
}

data class ProviderProfile(
    val providerId: String,
    val displayName: String,
    val models: List<ModelProfile>,
    val verified: Boolean,
) {
    /** A provider restriction worth surfacing, e.g. "no tool calls on this model". */
    fun restrictions(): List<String> = models.flatMap { m -> m.notes.map { "${m.modelId}: $it" } }
}

/**
 * The catalogue. Deliberately small: only families VN Code already ships in `PanelSelection` are
 * listed, and only with numbers taken from the provider documentation that exists in this repo's
 * research notes. Models added by the user through a custom endpoint are NOT in here and come back
 * as `unknown()`, which is the truthful answer for a base URL nobody has characterised.
 */
object ProviderCatalogue {

    private val GEMINI = "gemini"
    private val ANTHROPIC = "anthropic"
    private val CUSTOM = "custom"

    val providers: List<ProviderProfile> = listOf(
        ProviderProfile(
            providerId = GEMINI, displayName = "Google Gemini", verified = true,
            models = listOf(
                ModelProfile(
                    providerId = GEMINI, modelId = "gemini-3.8-flash",
                    limits = ModelLimits(contextTokens = 1_000_000, maxOutputTokens = 65_536),
                    capabilities = setOf(
                        ProviderCapability.TOOL_CALLS, ProviderCapability.STREAMING,
                        ProviderCapability.STRUCTURED_OUTPUT, ProviderCapability.REASONING,
                        ProviderCapability.VISION, ProviderCapability.CANCELLATION,
                    ),
                    verified = true,
                ),
                ModelProfile(
                    providerId = GEMINI, modelId = "gemini-3.8-pro",
                    limits = ModelLimits(contextTokens = 2_000_000, maxOutputTokens = 65_536),
                    capabilities = setOf(
                        ProviderCapability.TOOL_CALLS, ProviderCapability.STREAMING,
                        ProviderCapability.STRUCTURED_OUTPUT, ProviderCapability.REASONING,
                        ProviderCapability.VISION, ProviderCapability.CANCELLATION,
                    ),
                    verified = true,
                ),
            ),
        ),
        ProviderProfile(
            providerId = ANTHROPIC, displayName = "Anthropic", verified = true,
            models = listOf(
                ModelProfile(
                    providerId = ANTHROPIC, modelId = "claude-opus-5-5",
                    limits = ModelLimits(contextTokens = 200_000, maxOutputTokens = 32_000),
                    capabilities = setOf(
                        ProviderCapability.TOOL_CALLS, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING, ProviderCapability.VISION,
                        ProviderCapability.CANCELLATION,
                    ),
                    verified = true,
                    notes = listOf("structured output is expressed as a tool call, not a response format"),
                ),
            ),
        ),
    )

    fun provider(providerId: String): ProviderProfile? = providers.firstOrNull { it.providerId == providerId }

    fun profile(providerId: String, modelId: String): ModelProfile? =
        provider(providerId)?.models?.firstOrNull { it.modelId == modelId }

    /** Everything the catalogue is willing to say about an id it has never seen: nothing. */
    fun unknown(providerId: String, modelId: String): ModelProfile = ModelProfile(
        providerId = providerId, modelId = modelId,
        limits = ModelLimits(contextTokens = null, maxOutputTokens = null),
        capabilities = emptySet(), verified = false,
    )

    /** The profile to reason about, whether or not it is known. */
    fun resolve(providerId: String, modelId: String): ModelProfile =
        profile(providerId, modelId) ?: unknown(providerId, modelId)

    fun allModels(): List<ModelProfile> = providers.flatMap { it.models }
}

/** What the turn actually needs. Built from the request, not from the provider. */
data class TaskRequirement(
    val estimatedContextTokens: Int,
    val needsTools: Boolean = false,
    val needsStructuredOutput: Boolean = false,
    val needsVision: Boolean = false,
) {
    init {
        require(estimatedContextTokens >= 0) { "token estimate cannot be negative" }
    }
}

enum class Suitability { SUITABLE, RISKY, UNSUITABLE }

data class Assessment(
    val profile: ModelProfile,
    val suitability: Suitability,
    val reasons: List<String>,
) {
    val usable: Boolean get() = suitability != Suitability.UNSUITABLE
}

/**
 * Decides whether a model can serve a task — and stops there.
 *
 * Stage 5.1 does NOT switch the user's provider. `choose` returns the user's own selection plus an
 * assessment, because silently moving a turn to another provider would send the same conversation,
 * and its credentials, somewhere the user did not ask for. A switch can only ever be an explicit,
 * visible choice built on this assessment in a later stage.
 */
object ProviderSelector {

    fun assess(profile: ModelProfile, requirement: TaskRequirement): Assessment {
        val reasons = mutableListOf<String>()
        var worst = Suitability.SUITABLE

        fun demote(level: Suitability, why: String) {
            reasons += why
            if (level.ordinal > worst.ordinal) worst = level
        }

        val context = profile.limits.contextTokens
        if (context == null) {
            demote(Suitability.RISKY, "context limit unknown for ${profile.label}")
        } else if (requirement.estimatedContextTokens > context) {
            demote(
                Suitability.UNSUITABLE,
                "needs about ${requirement.estimatedContextTokens} tokens, ${profile.label} holds $context",
            )
        } else if (requirement.estimatedContextTokens > context * 4 / 5) {
            demote(Suitability.RISKY, "request would use over 80% of ${profile.label}'s window")
        }

        if (requirement.needsTools && !profile.capabilities.contains(ProviderCapability.TOOL_CALLS)) {
            demote(
                if (profile.verified) Suitability.UNSUITABLE else Suitability.RISKY,
                if (profile.verified) "${profile.label} cannot call tools" else "tool support unverified for ${profile.label}",
            )
        }
        if (requirement.needsStructuredOutput &&
            !profile.capabilities.contains(ProviderCapability.STRUCTURED_OUTPUT)
        ) {
            demote(
                if (profile.verified) Suitability.UNSUITABLE else Suitability.RISKY,
                "structured output unsupported or unverified on ${profile.label}",
            )
        }
        if (requirement.needsVision && !profile.capabilities.contains(ProviderCapability.VISION)) {
            demote(
                if (profile.verified) Suitability.UNSUITABLE else Suitability.RISKY,
                "images unsupported or unverified on ${profile.label}",
            )
        }
        return Assessment(profile, worst, reasons)
    }

    /**
     * The user's own choice, assessed and left alone. `alternative` names the best model that WOULD
     * fit when the chosen one does not, so the interface can offer it — it is never applied here.
     */
    fun choose(
        chosen: ModelProfile,
        requirement: TaskRequirement,
        candidates: List<ModelProfile> = ProviderCatalogue.allModels(),
    ): Selection {
        val assessment = assess(chosen, requirement)
        if (assessment.usable) return Selection(chosen, assessment, null)
        val best = candidates
            .filter { it.label != chosen.label }
            .map { assess(it, requirement) }
            .filter { it.usable }
            .minWithOrNull(compareBy({ it.suitability.ordinal }, { it.profile.limits.contextTokens ?: Int.MAX_VALUE }))
        return Selection(chosen, assessment, best)
    }

    data class Selection(
        /** Always the model the user selected. A provider switch is never silent. */
        val chosen: ModelProfile,
        val assessment: Assessment,
        /** A model that would fit, offered to the user, not used. */
        val alternative: Assessment?,
    )
}

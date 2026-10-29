package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ModelCatalog

/**
 * BATCH 8 — the searchable model picker, connected (owner mission part 8).
 *
 * WHAT WAS WRONG: ModelPickerSpec was complete - search, Other Models group, effort gating,
 * honest cost labels - and had NO production caller. The dialog in front of the user was
 * still provider rows plus a free-text "Model id" box, so the spec was decoration and the
 * only way to choose a model was typing its id from memory.
 *
 * WHAT THIS IS: the data half. It turns "what does this build actually know about the
 * selected provider" into the [PickerModel] rows the spec expects, and turns a chosen row
 * into the [PanelSelection] the next request will use.
 *
 * THE HONESTY RULE THAT SHAPES THE LIST:
 * - PREFERRED rows are models this provider is actually configured with: its preset
 *   default, plus any the owner added by hand, plus any the endpoint reported live. These
 *   are the ones that will work.
 * - EVERYTHING ELSE the catalog knows goes into the spec's existing "Other Models" group and
 *   is visibly separated, because the bundled catalog says what a model id IS, not that this
 *   endpoint serves it. Hiding them entirely would pretend we know nothing; mixing them in
 *   would offer ids that 404.
 * - A provider with no stored key never reaches this picker at all (the dialog already
 *   labels "no key stored"), so the rows shown are ones the user can actually send with.
 */
internal object ModelPickerData {

    /**
     * @param providerDefault the preset's bundled default for this provider ("" or null none).
     * @param manual model ids the owner typed/saved for this endpoint.
     * @param live model ids the endpoint reported on the last fetch.
     */
    fun modelsFor(
        providerId: String,
        providerDefault: String?,
        manual: List<String> = emptyList(),
        live: List<String> = emptyList(),
    ): List<PickerModel> {
        val preferred = LinkedHashSet<String>()
        providerDefault?.trim()?.takeIf { it.isNotEmpty() }?.let(preferred::add)
        manual.forEach { it.trim().takeIf { s -> s.isNotEmpty() }?.let(preferred::add) }
        live.forEach { it.trim().takeIf { s -> s.isNotEmpty() }?.let(preferred::add) }

        val rows = ArrayList<PickerModel>(preferred.size + 6)
        preferred.forEach { id -> rows.add(row(providerId, id, isOther = false)) }
        ModelCatalog.BUNDLED.forEach { cap ->
            if (cap.idMatch !in preferred) rows.add(row(providerId, cap.idMatch, isOther = true))
        }
        return rows
    }

    private fun row(providerId: String, modelId: String, isOther: Boolean): PickerModel {
        val cap = ModelCatalog.capabilityFor(providerId, modelId)
        return PickerModel(
            providerId = providerId,
            modelId = modelId,
            displayName = modelId,
            supportsReasoning = cap?.supportsReasoning ?: false,
            contextTokens = cap?.contextTokens,
            // No price data exists in this build, so the spec's rule applies: say nothing
            // rather than invent a multiplier.
            priceText = null,
            isOther = isOther,
        )
    }

    /**
     * The selection a tapped row produces.
     *
     * SAME PROVIDER (the only case the picker UI produces today): the model changes and the
     * provider, endpoint and key slot are carried over untouched.
     *
     * CROSS PROVIDER: delegated to the caller's resolver - the same SelectionResolver the
     * provider rows already use, which owns the "never invent a mixed pair" rules. The
     * earlier version tried to derive the endpoint from ProviderPresets, which was wrong:
     * the native providers (gemini, anthropic) are NOT presets, so their lookups returned
     * null and a Gemini model could have been paired with the previous provider's endpoint.
     * Without a resolver the picker declines to change providers at all rather than guess.
     */
    fun selectionFor(
        model: PickerModel,
        current: PanelSelection,
        slotKey: (providerId: String) -> String,
        applyProvider: ((providerId: String, modelId: String) -> PanelSelection?)? = null,
    ): PanelSelection {
        if (model.providerId == current.providerId) return current.copy(modelId = model.modelId)
        return applyProvider?.invoke(model.providerId, model.modelId) ?: current
    }

    /**
     * The effort the ENGINE will actually use for this provider today: the native adapters
     * send a fixed 1024-token reasoning budget, everything else sends none. Deriving the
     * label from that keeps the row summary truthful instead of advertising a control that
     * does not exist yet.
     */
    fun effortFor(providerId: String): Effort =
        Effort.fromBudget(
            if (providerId == "gemini" || providerId == "anthropic") 1024 else 0,
        )

    /** True when the model came from the bundled catalog rather than this endpoint. */
    fun isCatalogOnly(model: PickerModel): Boolean = model.isOther
}

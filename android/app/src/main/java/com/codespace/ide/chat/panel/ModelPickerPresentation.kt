package com.codespace.ide.chat.panel

/**
 * PROJECT SEVEN 7.6 — what the provider/model picker SAYS.
 *
 * The picker already has one source of truth for choices ([ModelPickerSpec] / [PickerModel] built from
 * Project Five's catalogue). What it lacked was wording that keeps three things apart: which provider,
 * which model, and which capabilities are actually KNOWN. A custom endpoint's capabilities are not
 * known, and saying "no reasoning support" for one would be a claim nobody made.
 *
 * Nothing here selects anything. Every alternative is offered as a choice, never applied.
 */
internal object ModelPickerPresentation {

    /** "OpenAI · gpt-4o" - provider identity and model identity, never merged into one blob. */
    fun header(providerLabel: String, model: PickerModel?): String {
        if (model == null) return "No model selected"
        val provider = providerLabel.ifBlank { model.providerId }
        val name = model.displayName.ifBlank { model.modelId }
        return provider + " \u00b7 " + name
    }

    /**
     * What the picker may say about capabilities. `null` context size means the catalogue does not know,
     * and the text says exactly that instead of leaving a blank the user will read as "fine".
     */
    fun capabilityLines(model: PickerModel, customEndpoint: Boolean): List<String> {
        val out = ArrayList<String>()
        if (customEndpoint) {
            out.add("Custom endpoint: its capabilities are not verified. Nothing here assumes them.")
        }
        out.add(
            if (model.supportsReasoning) "Reasoning effort: supported."
            else if (customEndpoint) "Reasoning effort: not known for this endpoint."
            else "Reasoning effort: not offered by this model.",
        )
        out.add(
            model.contextTokens?.let { "Context window: " + it + " tokens (from the catalogue)." }
                ?: "Context window: not known for this model. VN Code will not invent one.",
        )
        model.priceText?.let { out.add("Price: " + it + ".") }
        return out
    }

    /** The selected row's own note: what will actually be used if the user does nothing. */
    fun selectedNote(model: PickerModel?, providerLabel: String): String =
        if (model == null) "No model is selected. Sending is disabled until one is."
        else "Using " + header(providerLabel, model) + ". Choosing another model replaces it."

    /** An alternative is a suggestion the user must accept; it never becomes the selection by itself. */
    fun alternative(model: PickerModel, reason: String, providerLabel: String): String =
        "Try " + header(providerLabel, model) + " instead? " + reason +
            " Nothing changes unless you pick it."

    /** Which models this endpoint cannot serve, said as a property of the endpoint. */
    fun unsupported(model: PickerModel, customEndpoint: Boolean): List<String> {
        val out = ArrayList<String>()
        if (!model.supportsReasoning && !customEndpoint) out.add("No reasoning-effort control for this model.")
        if (model.contextTokens == null) out.add("Context limit unknown: long conversations may be reduced sooner.")
        return out
    }

    /** The empty search state, which must not read like a provider failure. */
    fun emptySearch(query: String): String =
        if (query.isBlank()) "No models are available from this provider right now."
        else "No model matches \"" + query + "\". The list is filtered, not empty."

    /**
     * Closing the picker without choosing keeps the previous selection. Stated as a function because it
     * is the promise the UI has to keep: there is no partial state to fall back to.
     */
    fun selectionAfterDismiss(before: Pair<String, String>?, chosen: Pair<String, String>?): Pair<String, String>? =
        chosen ?: before
}

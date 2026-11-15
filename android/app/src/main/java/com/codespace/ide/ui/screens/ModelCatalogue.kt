package com.codespace.ide.ui.screens

import com.codespace.ide.engine.ModelCapability

/**
 * PHASE 8a (2026-11-15): the model catalogue, shown as rows instead of a number the user has to
 * trust. Pure: the formatting and the honesty wording are tested without Compose.
 *
 * The catalogue only ever carries what it actually knows. A model it has no row for is not
 * "unsupported" - the app keeps offering tools exactly as before - so the list says "not listed"
 * rather than implying a limit nobody verified.
 */
internal object ModelCatalogue {

    data class Entry(
        val id: String,
        /** "1.05M" / "131K": tokens, abbreviated, never rounded up past what is there. */
        val contextLabel: String,
        val images: Boolean,
        val pdf: Boolean,
        /** "tools", "no tools", or "tools not verified" for the tri-state null. */
        val toolsLabel: String,
        val reasoning: Boolean,
        val providers: String,
    )

    fun entries(rows: List<ModelCapability>): List<Entry> = rows.map { row ->
        Entry(
            id = row.idMatch,
            contextLabel = contextLabel(row.contextTokens),
            images = row.supportsImages,
            pdf = row.supportsPdf,
            toolsLabel = when (row.supportsTools) {
                true -> "tools"
                false -> "no tools"
                null -> "tools not verified"
            },
            reasoning = row.supportsReasoning,
            providers = if (row.providers.isEmpty()) "any provider" else row.providers.joinToString(", "),
        )
    }

    /**
     * Context window, abbreviated by floor so no row claims more room than it has: 131_072 tokens
     * reads as 131K, not 132K.
     */
    fun contextLabel(tokens: Int): String = when {
        tokens <= 0 -> "unknown"
        tokens >= 1_000_000 -> {
            val millionths = tokens / 10_000
            val whole = millionths / 100
            val frac = millionths % 100
            if (frac == 0) whole.toString() + "M"
            else {
                val f = frac.toString().padStart(2, '0')
                whole.toString() + "." + f.trimEnd('0') + "M"
            }
        }
        tokens >= 1_000 -> (tokens / 1_000).toString() + "K"
        else -> tokens.toString() + " tokens"
    }

    /** Why a model can be missing from the list. Shown once, above the rows. */
    fun footer(): String =
        "A model that is not listed still works: it keeps tools and its configured endpoint. " +
            "Unknown is not a limit, and the app never strips tools from a model it cannot verify."
}

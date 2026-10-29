package com.codespace.ide.chat.panel

/**
 * U4 - the VS Code model picker, as pure data.
 *
 * Reference (owner screenshot): a popup with a search field and a gear, then rows:
 *   Auto (with a note) / models with the selected one checked / each with a right-hand
 *   "effort . cost" summary and a chevron that opens a "Thinking Effort" submenu
 *   (Low / Medium (default) / High, each with a one-line description), then an
 *   expandable "Other Models" group.
 *
 * THE PICKER ONLY SELECTS MODELS (owner ruling). No add-endpoint, no key UI. The gear
 * leads to Settings > Providers and Models, which is where those live.
 *
 * Two honesty rules, both tested:
 *  1. COST: the reference shows "3x" and "10% discount". Our catalog carries NO price
 *     data, so a cost label is shown only when a price is actually supplied. Absent
 *     price -> no cost text at all. Never a made-up multiplier.
 *  2. EFFORT: the submenu is shown only for models whose catalog row says
 *     supportsReasoning. Sending reasoning to a non-reasoning model is a 400 on some
 *     providers, so offering the control there would be a trap, not a feature.
 */
internal enum class Effort(val label: String, val description: String, val budgetTokens: Int) {
    LOW("Low", "Faster responses with less reasoning", 1024),
    MEDIUM("Medium (default)", "Balanced reasoning and speed", 4096),
    HIGH("High", "Maximum reasoning depth", 16384);

    companion object {
        val DEFAULT = MEDIUM
        val menuOrder: List<Effort> = listOf(LOW, MEDIUM, HIGH)

        /** The short word shown in the row summary and toolbar chip: "Low", "Medium", "High". */
        fun shortLabel(e: Effort): String = e.label.substringBefore(" ")

        /**
         * Inverse of the adapters' mapping (OpenAIAdapter.reasoningEffort: <4096 low,
         * <16384 medium, else high). Used to show the effort a stored budget implies.
         */
        fun fromBudget(budgetTokens: Int): Effort = when {
            budgetTokens < 4096 -> LOW
            budgetTokens < 16384 -> MEDIUM
            else -> HIGH
        }
    }
}

/** One model the owner could pick. Built from the live/manual/catalog lists by the caller. */
internal data class PickerModel(
    val providerId: String,
    val modelId: String,
    /** Human label; falls back to the id. */
    val displayName: String,
    val supportsReasoning: Boolean,
    /** Context window in tokens when the catalog knows it. */
    val contextTokens: Int?,
    /** Optional supplied price text, e.g. "$3 / M in". Null = unknown, shown as nothing. */
    val priceText: String? = null,
    /** True for models outside the owner's preferred set (the "Other Models" group). */
    val isOther: Boolean = false,
)

internal sealed interface PickerRow {
    /** The top "Auto" row. Present only when [autoNote] has something true to say. */
    data class Auto(val selected: Boolean, val note: String?) : PickerRow

    data class Model(
        val model: PickerModel,
        val selected: Boolean,
        /** Right-hand summary, e.g. "Medium" or "Medium . $3 / M in"; empty when nothing to say. */
        val summary: String,
        /** True when the chevron / effort submenu should be offered. */
        val hasEffortMenu: Boolean,
    ) : PickerRow

    /** The collapsible "Other Models" header. */
    data class OtherHeader(val expanded: Boolean, val count: Int) : PickerRow
}

internal data class ModelPickerState(
    val query: String,
    val rows: List<PickerRow>,
    /** Always true: the gear is part of the reference and routes to Settings. */
    val showsManageGear: Boolean,
    /** Shown when a search matches nothing. */
    val emptyMessage: String?,
)

internal object ModelPickerSpec {

    /** Label of the group header, from the reference. */
    const val OTHER_MODELS = "Other Models"

    /**
     * Build the picker rows.
     *
     * @param selected the currently selected (provider, model) or null.
     * @param effort the effort for the SELECTED model; other models show the default.
     * @param autoNote text for the Auto row, or null to hide Auto entirely. Auto is a
     *        routing behaviour we do not have, so callers pass null today and the row
     *        simply does not appear (engine honesty).
     */
    fun build(
        models: List<PickerModel>,
        selected: Pair<String, String>?,
        effort: Effort,
        query: String,
        otherExpanded: Boolean,
        autoNote: String? = null,
    ): ModelPickerState {
        val q = query.trim().lowercase()
        val matches = models.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.modelId.lowercase().contains(q) ||
                it.providerId.lowercase().contains(q)
        }
        val rows = ArrayList<PickerRow>()
        if (autoNote != null && q.isEmpty()) rows.add(PickerRow.Auto(selected = false, note = autoNote))

        fun rowFor(m: PickerModel): PickerRow.Model {
            val isSel = selected != null && selected.first == m.providerId && selected.second == m.modelId
            return PickerRow.Model(
                model = m,
                selected = isSel,
                summary = summaryFor(m, if (isSel) effort else Effort.DEFAULT),
                hasEffortMenu = m.supportsReasoning,
            )
        }

        matches.filter { !it.isOther }.forEach { rows.add(rowFor(it)) }
        val others = matches.filter { it.isOther }
        if (others.isNotEmpty()) {
            // A search opens the group so a hit inside it is never hidden.
            val open = otherExpanded || q.isNotEmpty()
            rows.add(PickerRow.OtherHeader(expanded = open, count = others.size))
            if (open) others.forEach { rows.add(rowFor(it)) }
        }
        return ModelPickerState(
            query = query,
            rows = rows,
            showsManageGear = true,
            emptyMessage = if (matches.isEmpty() && q.isNotEmpty()) "No models match \"" + query.trim() + "\"" else null,
        )
    }

    /** "Medium" / "Medium . $3 / M in" / "$3 / M in" / "" - only true facts. */
    fun summaryFor(m: PickerModel, effort: Effort): String {
        val parts = ArrayList<String>()
        if (m.supportsReasoning) parts.add(Effort.shortLabel(effort))
        m.priceText?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" \u00B7 ")
    }

    /** The toolbar chip text: "model" or "model . Effort" when effort applies and room allows. */
    fun chipText(m: PickerModel?, effort: Effort, showEffort: Boolean): String {
        if (m == null) return "Pick model"
        val name = m.displayName.ifBlank { m.modelId }
        return if (showEffort && m.supportsReasoning) name + " \u00B7 " + Effort.shortLabel(effort) else name
    }

    /** What the effort submenu offers for a model: nothing when it cannot reason. */
    fun effortMenu(m: PickerModel, current: Effort): List<Pair<Effort, Boolean>> =
        if (!m.supportsReasoning) emptyList() else Effort.menuOrder.map { it to (it == current) }
}

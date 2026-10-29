package com.codespace.ide.chat.panel

/**
 * U3 - the VS Code chat input toolbar, as pure data (no Compose imports).
 *
 * Reference (owner screenshots + agent-mode video, 2026-10-29), left to right:
 *   [+ Add Context]  |  [Agent v]  [model . effort]  [tools]   ...   [send / stop + chevron]
 * and under the card a status row:
 *   [Local v] | [shield Default Approvals v]                      ...   [context ring 3%]
 *
 * The phone adaptation the owner asked for: the toolbar COLLAPSES into an overflow
 * menu when narrow, never clips, never wraps a label one letter per line, and every
 * touch target is at least 48dp. Which control lives where at which width is a
 * DECISION, so it is data and it is tested on the JVM at 280/360/480 dp, with and
 * without the keyboard. The Compose layer renders [ChatToolbarLayout.forWidth] and
 * decides nothing itself.
 */
internal enum class ToolbarItem {
    ADD_CONTEXT,   // "+" opens the attach/context menu
    MODE,          // Agent / Ask / Edit dropdown
    MODEL,         // "model . effort" chip; opens the model picker (selection only)
    MIC,           // dictation; reserves its slot even on narrow widths
    TOOLS,         // tools / configure icon
}

internal enum class ChatMode(val label: String) {
    AGENT("Agent"),
    ASK("Ask"),
    EDIT("Edit");

    companion object {
        /** VS Code's dropdown order in the video: Ask, Edit, Agent. */
        val dropdownOrder: List<ChatMode> = listOf(ASK, EDIT, AGENT)
    }
}

internal data class ChatToolbarSpec(
    /** Items drawn inline, in order. */
    val inline: List<ToolbarItem>,
    /** Items moved into the overflow menu, in order. Empty = no overflow button. */
    val overflow: List<ToolbarItem>,
    /** Model chip shows "model . effort" (true) or just the model short name (false). */
    val modelShowsEffort: Boolean,
    /** Mode shows its label (true) or only its icon (false). */
    val modeShowsLabel: Boolean,
    /** Max width the model chip may take before it ellipsizes, in dp. */
    val modelChipMaxDp: Int,
    /** Minimum touch target edge in dp. */
    val touchTargetDp: Int,
    /** Status row: show the text next to the Local/Approvals icons. */
    val statusShowsText: Boolean,
) {
    val hasOverflow: Boolean get() = overflow.isNotEmpty()
}

internal object ChatToolbarLayout {

    const val TOUCH_TARGET_DP = 48

    /** Width the fixed right-hand cluster (send + chevron + overflow) reserves. */
    private const val RIGHT_CLUSTER_DP = 128

    private const val ADD_DP = 48
    private const val MODE_ICON_DP = 48
    private const val MODE_LABEL_DP = 92
    private const val MODEL_MIN_DP = 96
    private const val MODEL_WITH_EFFORT_DP = 168
    private const val TOOLS_DP = 48
    private const val MIC_DP = 48

    /** Width at or above which the status row keeps its text labels. */
    const val STATUS_TEXT_FROM_DP = 330

    fun forWidth(widthDp: Int, imeVisible: Boolean): ChatToolbarSpec {
        val w = if (widthDp < 0) 0 else widthDp
        // The keyboard changes height, not width, but the field above is already
        // squeezed; reserve a little more so the send cluster is never crowded.
        val budget = w - RIGHT_CLUSTER_DP - (if (imeVisible) 8 else 0)

        // Priority when space runs out (last to leave first to stay):
        // MODEL is the point of the row, then ADD_CONTEXT, then MODE, then TOOLS.
        val inline = ArrayList<ToolbarItem>()
        val overflow = ArrayList<ToolbarItem>()
        var left = budget

        fun fits(cost: Int): Boolean = left >= cost

        // Cheapest honest form of each item first; widen only if there is room after.
        // The model chip is ALWAYS inline: it is the point of the row, and a toolbar
        // that hides the model while keeping a lesser control is the wrong trade at any
        // width. Below its own floor it simply keeps the floor and ellipsizes.
        inline.add(ToolbarItem.MODEL); left -= MODEL_MIN_DP

        if (fits(ADD_DP)) { inline.add(ToolbarItem.ADD_CONTEXT); left -= ADD_DP }
        else overflow.add(ToolbarItem.ADD_CONTEXT)

        if (fits(MODE_ICON_DP)) { inline.add(ToolbarItem.MODE); left -= MODE_ICON_DP }
        else overflow.add(ToolbarItem.MODE)

        if (fits(MIC_DP)) { inline.add(ToolbarItem.MIC); left -= MIC_DP }
        else overflow.add(ToolbarItem.MIC)

        if (fits(TOOLS_DP)) { inline.add(ToolbarItem.TOOLS); left -= TOOLS_DP }
        else overflow.add(ToolbarItem.TOOLS)

        // Spend what is left upgrading forms, most valuable first.
        var modeShowsLabel = false
        var modelShowsEffort = false
        if (ToolbarItem.MODE in inline && left >= MODE_LABEL_DP - MODE_ICON_DP) {
            modeShowsLabel = true; left -= (MODE_LABEL_DP - MODE_ICON_DP)
        }
        if (ToolbarItem.MODEL in inline && left >= MODEL_WITH_EFFORT_DP - MODEL_MIN_DP) {
            modelShowsEffort = true; left -= (MODEL_WITH_EFFORT_DP - MODEL_MIN_DP)
        }

        // Keep inline in the VS Code visual order regardless of priority order above.
        val order = listOf(
            ToolbarItem.ADD_CONTEXT, ToolbarItem.MODE, ToolbarItem.MODEL,
            ToolbarItem.MIC, ToolbarItem.TOOLS,
        )
        return ChatToolbarSpec(
            inline = order.filter { it in inline },
            overflow = order.filter { it in overflow },
            modelShowsEffort = modelShowsEffort,
            modeShowsLabel = modeShowsLabel,
            modelChipMaxDp = if (modelShowsEffort) MODEL_WITH_EFFORT_DP else MODEL_MIN_DP + (left.coerceAtLeast(0)),
            touchTargetDp = TOUCH_TARGET_DP,
            statusShowsText = w >= STATUS_TEXT_FROM_DP,
        )
    }
}

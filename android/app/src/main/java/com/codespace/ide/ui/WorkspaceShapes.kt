package com.codespace.ide.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Centralized shape and spacing tokens for the rounded workspace container architecture.
 *
 * Each major panel (Activity Bar, Explorer, Editor, Bottom Panel, Chat) is ONE rounded
 * rectangular container. Individual child elements (icons, tabs, rows) do NOT get their
 * own rounding — only the container does.
 *
 * Reference: VS Code rounded-panel workspace architecture.
 */
object WorkspaceShapes {

    // ── Corner radii (design tokens) ──
    // Small: minor controls, command field
    // Medium: activity bar, chat, bottom panel
    // Large: explorer, editor (the biggest surfaces)

    /** Activity Bar — rounded on all corners (it's a standalone vertical container) */
    val ActivityBarShape = RoundedCornerShape(
        topStart = 10.dp, topEnd = 10.dp,
        bottomStart = 10.dp, bottomEnd = 10.dp,
    )

    /** Explorer / Left Sidebar — rounded on all four corners */
    val ExplorerShape = RoundedCornerShape(
        topStart = 10.dp, topEnd = 10.dp,
        bottomStart = 10.dp, bottomEnd = 10.dp,
    )

    /** Main Editor / Workspace — rounded on all four corners */
    val EditorShape = RoundedCornerShape(
        topStart = 10.dp, topEnd = 10.dp,
        bottomStart = 10.dp, bottomEnd = 10.dp,
    )

    /** Bottom Panel (Problems/Output/Debug/Terminal) — rounded on all four corners */
    val BottomPanelShape = RoundedCornerShape(
        topStart = 10.dp, topEnd = 10.dp,
        bottomStart = 10.dp, bottomEnd = 10.dp,
    )

    /** Chat / AI Panel — rounded on all four corners */
    val ChatShape = RoundedCornerShape(
        topStart = 10.dp, topEnd = 10.dp,
        bottomStart = 10.dp, bottomEnd = 10.dp,
    )

    /** Top command field — rounded rectangle (NOT pill/capsule) */
    val CommandFieldShape = RoundedCornerShape(8.dp)

    /** Outer workspace surface */
    val WorkspaceOuterShape = RoundedCornerShape(12.dp)

    // ── Gaps between panels (the ash/gray background shows through here) ──
    /** Gap between Activity Bar and Explorer */
    val PanelGapSmall = 3.dp

    /** Gap between Explorer and Editor, Editor and Chat, etc. */
    val PanelGapMedium = 4.dp

    /** Outer padding — workspace surface to screen edge */
    val WorkspacePadding = 3.dp
}

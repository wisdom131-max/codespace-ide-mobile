package com.codespace.ide.ui.panes

import androidx.compose.ui.graphics.Color

/**
 * TAB-STRIP CONSOLIDATION (2026-09-11): the shell's 35dp mirror tab strip
 * (ProjectShellScreen) was REMOVED to reclaim vertical space - EditorPane's own
 * strip is the single surviving strip. The shell therefore passes its WORKBENCH
 * THEME colors down so the surviving strip visually matches the removed strip's
 * themed active-tab highlight (instead of EditorPane's old hardcoded light
 * palette: white active tab on a light bar).
 *
 * Defaults keep the old EditorPane look for any call site that does not pass
 * a theme (compile-safe, no behavior change outside the strip).
 */
data class EditorTabColors(
    val barBg: Color = Color(0xFFECECEC),
    val activeBg: Color = Color(0xFFFFFFFF),
    val inactiveBg: Color = Color(0xFFECECEC),
    val activeIndicator: Color = Color(0xFF007ACC),
    val text: Color = Color(0xFF333333),
    val textInactive: Color = Color(0xFF717171),
    val divider: Color = Color(0xFFE0E0E0),
)

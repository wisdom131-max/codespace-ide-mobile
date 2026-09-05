package com.codespace.ide.ui.panes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.HorizontalDivider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.DpOffset

/**
 * Part B (2026-09-05) - WORKSPACE ROOTS UI for the terminal pane's 3-dot menu.
 *
 * Menu 1: WorkspaceRootsSection - a "WORKSPACE ROOTS" section listing every
 * root of the current project (via ProjectPathResolver.getAllWorkspaceRoots).
 * Tapping a root opens Menu 2.
 *
 * Menu 2: RootTerminalLockMenu - lists every currently open terminal with an
 * animated padlock. Tap a terminal to lock/unlock its binding to the selected
 * root. LOCKED means: this terminal's workDir / $WORKSPACE_PATH resolve to the
 * locked root at every session (re)creation (VS Code's per-terminal cwd model).
 * It does NOT live-cd an already-running shell.
 *
 * LIVE STATE (hard requirement): this menu reads the SHARED tabs list
 * (SnapshotStateList) DIRECTLY in composition - never a remembered copy -
 * so a terminal closed/crashed while the menu is open disappears from the
 * list immediately via recomposition.
 */

/**
 * Menu-1 section: header + one item per project root.
 * Styled to match TerminalPane's existing 3-dot menu sections.
 */
@Composable
internal fun WorkspaceRootsSection(
    roots: List<String>,
    activeRootPath: String?,
    onRootSelected: (String) -> Unit,
) {
    DropdownMenuItem(
        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
        text = { Text("WORKSPACE ROOTS", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = FontWeight.SemiBold) },
        onClick = {}, enabled = false)
    if (roots.isEmpty()) {
        DropdownMenuItem(
            leadingIcon = { Text("  ", fontSize = 13.sp, color = Color(0xFF717171)) },
            text = { Text("(no roots found for this project)", color = Color(0xFF717171), fontSize = 12.sp) },
            onClick = {}, enabled = false)
    } else {
        roots.forEach { rootPath ->
            val name = java.io.File(rootPath).name.ifBlank { rootPath }
            val isActive = rootPath == activeRootPath
            DropdownMenuItem(
                leadingIcon = { Text(if (isActive) "📍" else "📁", fontSize = 13.sp) },
                text = {
                    Text(
                        if (isActive) "$name (active)" else name,
                        color = Color(0xFF89B4FA), fontSize = 13.sp
                    )
                },
                onClick = { onRootSelected(rootPath) })
        }
    }
    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * Menu-2: per-terminal padlock picker for the selected root.
 * Stays open after a toggle so several terminals can be locked in one visit.
 */
@Composable
internal fun RootTerminalLockMenu(
    rootPath: String,
    tabs: SnapshotStateList<TabSession>,
    onDismiss: () -> Unit,
    onToggleLock: (tabId: String, root: String) -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 4.dp),
        modifier = Modifier.background(Color(0xFF2D2D2D))) {
        DropdownMenuItem(
            leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
            text = { Text("ROOT LOCK", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = FontWeight.SemiBold) },
            onClick = {}, enabled = false)
        DropdownMenuItem(
            leadingIcon = { Text("  ", fontSize = 13.sp, color = Color(0xFF717171)) },
            text = { Text(java.io.File(rootPath).name.ifBlank { rootPath }, fontSize = 12.sp, color = Color(0xFF89B4FA)) },
            onClick = {}, enabled = false)
        HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
        DropdownMenuItem(
            leadingIcon = { Text("  ", fontSize = 13.sp, color = Color(0xFF717171)) },
            text = { Text("Tap a terminal to lock/unlock it to this root", fontSize = 10.sp, color = Color(0xFF717171)) },
            onClick = {}, enabled = false)
        if (tabs.isEmpty()) {
            DropdownMenuItem(
                leadingIcon = { Text("  ", fontSize = 13.sp, color = Color(0xFF717171)) },
                text = { Text("(no open terminals)", color = Color(0xFF717171), fontSize = 12.sp) },
                onClick = {}, enabled = false)
        } else {
            // Live read of the shared tabs list - recomposes on any tab change.
            tabs.forEach { tab ->
                val lockedHere = tab.lockedRootPath == rootPath
                DropdownMenuItem(
                    leadingIcon = { AnimatedPadlock(locked = tab.lockedRootPath != null) },
                    text = {
                        Column {
                            Text(
                                if (lockedHere) "${tab.name} - locked" else tab.name,
                                color = Color(0xFFCCCCCC), fontSize = 13.sp
                            )
                            if (tab.lockedRootPath != null && !lockedHere) {
                                Text(
                                    "bound to " + java.io.File(tab.lockedRootPath!!).name,
                                    fontSize = 10.sp, color = Color(0xFFE5C07B)
                                )
                            }
                        }
                    },
                    onClick = { onToggleLock(tab.id, rootPath) })
            }
        }
        HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
        DropdownMenuItem(
            leadingIcon = { Text("✅", fontSize = 13.sp) },
            text = { Text("Done", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
            onClick = onDismiss)
    }
}

/**
 * Padlock that animates on state change (scale + fade between Lock/LockOpen).
 * Closed (amber) = bound to a root. Open (grey) = follows the active root.
 */
@Composable
internal fun AnimatedPadlock(locked: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = locked,
        transitionSpec = {
            (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith
                (scaleOut(targetScale = 0.5f) + fadeOut())
        },
        label = "padlock") { isLocked ->
        Icon(
            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (isLocked) "Locked to root" else "Unlocked",
            tint = if (isLocked) Color(0xFFE5C07B) else Color(0xFF969696),
            modifier = modifier)
    }
}

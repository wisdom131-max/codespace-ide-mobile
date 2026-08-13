package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * SourceControlPane — SCM panel for CodeSpace IDE Mobile.
 *
 * CLEARED on 2026-08-13 for full restructure per Wisdom's plan.
 * The previous implementation had:
 *   - Duplicate pull/fetch/push logic (header icons + overflow menu)
 *   - Broken string interpolation in overflow menu (${'$'}{result.take(60)} printed literally)
 *   - False-positive merge conflict detection (ProotInstaller's "(command completed, no output)"
 *     placeholder was treated as a conflicted file name)
 *   - No rotation-safe scroll handling (Test 41)
 *   - No upstream tracking fix for push (Test 42 — "No configured push destination")
 *
 * Awaiting Wisdom's restructuring spec before rebuilding.
 */
@Composable
fun SourceControlPane(projectId: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Source Control — restructuring in progress", fontSize = 13.sp)
    }
}

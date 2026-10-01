package com.codespace.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember

/**
 * Keeps this callback's cache outside the large ProjectShellScreen method.
 * Its behavior is unchanged: Open Recent opens the existing command palette.
 */
@Composable
internal fun rememberOpenRecentPaletteAction(visible: MutableState<Boolean>): () -> Unit {
    return remember(visible) { OpenRecentPaletteAction(visible) }
}

private class OpenRecentPaletteAction(
    private val visible: MutableState<Boolean>,
) : () -> Unit {
    override fun invoke() {
        visible.value = true
    }
}

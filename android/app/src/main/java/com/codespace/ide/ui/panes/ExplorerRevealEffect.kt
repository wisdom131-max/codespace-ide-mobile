package com.codespace.ide.ui.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.codespace.ide.util.ExplorerReveal

/**
 * U6-d — drains ExplorerReveal requests by expanding the ancestor folders of each path.
 *
 * Kept in its own file on purpose: ExplorerPane's composable body is already enormous, and
 * the 64KB method limit applies to it, so new effects are never inlined into that body. The
 * caller passes its own expansion map and re-scan trigger in.
 *
 * `expanded` is the pane's live state (path -> isExpanded), so this only turns dirs ON; it
 * never collapses anything and never selects or opens a file, which is what keeps a
 * multi-file agent write from hijacking the editor.
 */
@Composable
internal fun ExplorerRevealEffect(
    expanded: MutableMap<String, Boolean>,
    projectRoot: String?,
    onReveal: () -> Unit = {},
) {
    val request = ExplorerReveal.pending.value
    val currentExpanded by rememberUpdatedState(expanded)
    val currentRoot by rememberUpdatedState(projectRoot)
    val currentOnReveal by rememberUpdatedState(onReveal)

    LaunchedEffect(request, projectRoot) {
        if (request.isEmpty()) return@LaunchedEffect
        val root = currentRoot
        var changed = false
        for (path in request) {
            if (!ExplorerReveal.isInsideProject(path, root)) continue
            for (dir in ExplorerReveal.ancestorsToExpand(path, root)) {
                if (currentExpanded[dir] != true) {
                    currentExpanded[dir] = true
                    changed = true
                }
            }
        }
        // A path that needed no expansion was already visible; either way the request is
        // consumed so it cannot replay on the next recomposition.
        ExplorerReveal.consume()
        if (changed) currentOnReveal()
    }
}

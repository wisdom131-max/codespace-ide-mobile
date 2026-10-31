package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The green ⋮ chip, rebuilt (owner request, 2026-10-31).
 *
 * What it does now that it did not:
 *  - it can be DRAGGED anywhere in the editor (long-press, then move), snapping to the nearer
 *    edge on release and staying inside the window;
 *  - one small button cycles Solid → Ghost → Hidden, so a control over the text can be dimmed or
 *    reduced to a handle without being lost;
 *  - its menu is AI ONLY, with "Fix with AI" pinned first and "More actions..." as the door to the
 *    language-server actions, which is where those belong;
 *  - it no longer appears for every word under the caret, and it steps out of the way while the
 *    keyboard is open.
 *
 * All the judgement lives in [EditorActionMenuSpec] so it is unit tested; this file is layout.
 */

/** Drag/persist surface for the chip, kept out of CodeEditor's already-large body. */
internal class EditorChipPlacement(
    start: Pair<Float, Float>,
    private val maxX: Float,
    private val maxY: Float,
) {
    var x by mutableFloatStateOf(start.first)
    var y by mutableFloatStateOf(start.second)
    private var dragging = false

    fun drag(dx: Float, dy: Float) {
        val (nx, ny) = EditorActionMenuSpec.clamp(
            x + dx, y + dy, EditorActionMenuSpec.diameterDp(EditorChipState.SOLID), maxX, maxY,
        )
        x = nx
        y = ny
        dragging = true
    }

    /** Snap to the nearer edge, then persist: a parked chip must survive a restart. */
    fun release(context: android.content.Context) {
        if (!dragging) return
        dragging = false
        val (nx, ny) = EditorActionMenuSpec.snapped(
            x, y, EditorActionMenuSpec.diameterDp(EditorChipState.SOLID), maxX, maxY,
        )
        x = nx
        y = ny
        EditorChipPrefs.setPosition(context, x, y)
    }
}

/**
 * Everything the chip needs, created once per editor. Exists so the CodeEditor composable body
 * gains a single `val chip = rememberEditorChip()` instead of twenty lines of state setup, which
 * is what its 64KB method ceiling allows.
 */
internal class EditorChipHostState(
    private val context: android.content.Context,
    private val density: androidx.compose.ui.unit.Density,
    private val maxX: Float,
    private val maxY: Float,
    initial: EditorChipState,
    stored: Pair<Float, Float>?,
) {
    var state by mutableStateOf(initial)
        private set
    val placement = EditorChipPlacement(stored ?: (maxX - 44f).coerceAtLeast(4f) to 8f, maxX, maxY)

    fun offsetInt(): androidx.compose.ui.unit.IntOffset = with(density) {
        androidx.compose.ui.unit.IntOffset(placement.x.dp.roundToPx(), placement.y.dp.roundToPx())
    }

    fun cycle() {
        state = EditorActionMenuSpec.nextState(state)
        EditorChipPrefs.setState(context, state)
    }

    /** The nearest lint error within two lines of the caret, or null. */
    fun diagnostic(
        lintErrors: List<LintError>,
        positionMapper: PositionMapper,
        selectionStart: Int,
    ): LintError? {
        val caretLine = positionMapper.offsetToLine(selectionStart)
        return lintErrors.firstOrNull { err ->
            kotlin.math.abs(positionMapper.offsetToLine(err.start) - caretLine) <= 2
        }
    }

    fun visible(
        word: String?,
        hasSelection: Boolean,
        diagnostic: LintError?,
        imeHeightPx: Int,
    ): Boolean = EditorActionMenuSpec.shouldShow(
        state = state,
        hasSelection = hasSelection,
        hasWordUnderCursor = word != null && word.length >= 1,
        hasNearbyDiagnostic = diagnostic != null,
        imeVisible = imeHeightPx > 0,
    )
}

@Composable
internal fun rememberEditorChip(): EditorChipHostState {
    val ctx = LocalContext.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxX = cfg.screenWidthDp.toFloat()
    val maxY = cfg.screenHeightDp * 0.7f
    val stored = remember { EditorChipPrefs.position(ctx) }
    val initial = remember { EditorChipPrefs.state(ctx) }
    return remember(maxX, maxY) {
        EditorChipHostState(ctx, density, maxX, maxY, initial, stored)
    }
}

/** One call builds the prompt and hands it to the host: the chip never owns the handoff. */
internal fun chipPrompt(
    kind: String,
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    filePath: String?,
    languageName: String,
    diagnostic: LintError?,
    positionMapper: PositionMapper,
): String = EditorActionMenuSpec.build(
    kind,
    EditorActionMenuSpec.contextFor(
        text = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        fileName = filePath,
        languageName = languageName,
        diagnosticMessage = diagnostic?.message,
        diagnosticLine = diagnostic?.let { positionMapper.offsetToLine(it.start) + 1 },
    ),
)

/**
 * The chip itself: a ⋮ button that opens the AI menu and a state dot that cycles visibility.
 * Both keep a 36dp touch target even when the visible glyph shrinks.
 */
@Composable
internal fun EditorChipButton(
    state: EditorChipState,
    hasDiagnostic: Boolean,
    /** The host builds the prompt and owns the handoff: no prompt is constructed here. */
    onPick: (kind: String) -> Unit,
    onOpenMore: () -> Unit,
    onCycleState: () -> Unit,
    placement: EditorChipPlacement,
    accent: Color = Color(0xFF4EC9B0),
    surface: Color = Color(0xFF1E1E1E),
) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val hidden = state == EditorChipState.HIDDEN
    val diameter = EditorActionMenuSpec.diameterDp(state)

    Box(
        Modifier
            .size(diameter.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surface)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .alpha(EditorActionMenuSpec.alpha(state))
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, drag ->
                        change.consume()
                        placement.drag(drag.x / density, drag.y / density)
                    },
                    onDragEnd = { placement.release(ctx) },
                    onDragCancel = { placement.release(ctx) },
                )
            }
            .clickable { expanded = true },
        contentAlignment = Alignment.Center,
    ) {
        if (hidden) {
            // A handle, not a button: enough to find, small enough to ignore.
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        } else {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "AI actions",
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    // The state control sits beside the chip, small but with a full-size touch target.
    Box(
        Modifier
            .size(diameter.dp)
            .clip(RoundedCornerShape(8.dp))
            .alpha(EditorActionMenuSpec.alpha(state))
            .clickable { onCycleState() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        EditorChipState.SOLID -> accent
                        EditorChipState.GHOST -> accent.copy(alpha = 0.5f)
                        EditorChipState.HIDDEN -> Color(0xFF858585)
                    }
                )
        )
    }

    EditorAiMenu(
        expanded = expanded,
        hasDiagnostic = hasDiagnostic,
        onPick = onPick,
        onOpenMore = onOpenMore,
        onDismiss = { expanded = false },
    )
}

/** AI-only menu: the fix first, the transforms, then the door to the language server. */
@Composable
internal fun EditorAiMenu(
    expanded: Boolean,
    hasDiagnostic: Boolean,
    onPick: (String) -> Unit,
    onOpenMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = EditorActionMenuSpec.entries(hasDiagnostic)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF1E1E1E)),
    ) {
        Column(
            Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    if (entry.kind == EditorActionMenuSpec.FIX_WITH_AI) "\u26A1" else "\u2728",
                                    fontSize = 12.sp,
                                    color = if (entry.enabled) Color(0xFF4EC9B0) else Color(0xFF808080),
                                )
                                Text(
                                    entry.label,
                                    color = if (entry.enabled) Color(0xFFD4D4D4) else Color(0xFF666666),
                                    fontSize = 13.sp,
                                )
                            }
                            if (!entry.enabled && entry.reason != null) {
                                Text(entry.reason, color = Color(0xFF858585), fontSize = 10.sp)
                            }
                        }
                    },
                    enabled = entry.enabled,
                    onClick = {
                        onDismiss()
                        // The host owns both the prompt and the handoff, so a pick can never
                        // build a prompt that nowhere can send.
                        onPick(entry.kind)
                    },
                )
            }
            Spacer(Modifier.heightIn(min = 4.dp))
            DropdownMenuItem(
                text = {
                    Text(
                        EditorActionMenuSpec.MORE_LABEL,
                        color = Color(0xFF858585),
                        fontSize = 12.sp,
                    )
                },
                onClick = {
                    onDismiss()
                    onOpenMore()
                },
            )
        }
    }
}

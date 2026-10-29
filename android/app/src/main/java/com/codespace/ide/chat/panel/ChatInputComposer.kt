package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * U3: the composer card from the VS Code reference — the message field with the
 * toolbar beneath it, the model chip living HERE (the owner asked for it out of
 * the panel header), and the send control on the right.
 *
 * Nothing here decides layout: [ChatToolbarLayout.forWidth] says what is inline and
 * what moves into the overflow menu, and [ChatSendState.resolve] says what the
 * primary control does, so both are unit-tested on the JVM at 280 / 360 / 480 dp
 * instead of by eye on the device.
 *
 * Glyphs are used for the icons (+ v ^ and the stop square) rather than Material
 * icons: this file then has no icon imports to drift, and a missing icon is a
 * compile error while a glyph is not. The owner is verifying the visuals on the
 * device and will call out anything that reads wrong.
 */
@Composable
internal fun ChatInputComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    streaming: Boolean,
    needsSetup: Boolean,
    modelLabel: String,
    effortLabel: String?,
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    onAddContext: () -> Unit,
    onOpenModel: () -> Unit,
    onTools: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val widthDp = if (constraints.maxWidth == Int.MAX_VALUE) 2000 else maxWidth.value.toInt()
        val spec = ChatToolbarLayout.forWidth(widthDp, imeVisible)
        // Field height comes from the U1-e spec, already tested at 280/360/480 with and
        // without the keyboard, so the composer does not invent a second set of rules.
        val fieldSpec = ChatInputLayout.forWidth(widthDp, imeVisible)
        val send = ChatSendState.resolve(
            streaming = streaming,
            needsSetup = needsSetup,
            modelBlank = modelLabel.isBlank(),
            hasText = value.isNotBlank(),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                placeholder = { Text(placeholder, fontSize = ChatTokens.FONT_SMALL.sp) },
                singleLine = false,
                maxLines = fieldSpec.fieldMaxLines,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = ChatTokens.GAP_TIGHT.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarCluster(
                    spec = spec,
                    mode = mode,
                    modelLabel = modelLabel,
                    effortLabel = effortLabel,
                    onAddContext = onAddContext,
                    onOpenModel = onOpenModel,
                    onTools = onTools,
                    onModeChange = onModeChange,
                )
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SendCluster(
                        spec = send,
                        onSend = onSend,
                        onStop = onStop,
                        onSetup = onOpenModel,
                    )
                }
            }
        }
    }
}

/** The left-hand controls, in the reference's visual order, overflow last. */
@Composable
private fun ToolbarCluster(
    spec: ChatToolbarSpec,
    mode: ChatMode,
    modelLabel: String,
    effortLabel: String?,
    onAddContext: () -> Unit,
    onOpenModel: () -> Unit,
    onTools: () -> Unit,
    onModeChange: (ChatMode) -> Unit,
) {
    val order = listOf(ToolbarItem.ADD_CONTEXT, ToolbarItem.MODE, ToolbarItem.MODEL, ToolbarItem.TOOLS)
    for (item in order) {
        if (item in spec.inline) {
            when (item) {
                ToolbarItem.ADD_CONTEXT -> GlyphButton("+", "Add context", spec.touchTargetDp, onAddContext)
                ToolbarItem.MODE -> ModeChip(spec, mode, onModeChange)
                ToolbarItem.MODEL -> ModelChip(spec, modelLabel, effortLabel, onOpenModel)
                ToolbarItem.TOOLS -> GlyphButton("\u2699", "Tools and configure", spec.touchTargetDp, onTools)
            }
        }
    }
    if (spec.hasOverflow) {
        var open by remember { mutableStateOf(false) }
        Box {
            GlyphButton("\u22EF", "More controls", spec.touchTargetDp) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (item in spec.overflow) {
                    val label = when (item) {
                        ToolbarItem.ADD_CONTEXT -> "Add context"
                        ToolbarItem.MODE -> "Mode: " + mode.label
                        ToolbarItem.MODEL -> "Model: " + modelLabel.ifBlank { "none" }
                        ToolbarItem.TOOLS -> "Tools and configure"
                    }
                    DropdownMenuItem(
                        text = { Text(label, fontSize = ChatTokens.FONT_SMALL.sp) },
                        onClick = {
                            open = false
                            when (item) {
                                ToolbarItem.ADD_CONTEXT -> onAddContext()
                                ToolbarItem.MODE -> onModeChange(mode)
                                ToolbarItem.MODEL -> onOpenModel()
                                ToolbarItem.TOOLS -> onTools()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlyphButton(glyph: String, description: String, touchDp: Int, onClick: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Box(
        Modifier
            .size(touchDp.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = c.textSecondary, fontSize = ChatTokens.FONT_BODY.sp)
    }
}

@Composable
private fun ModeChip(spec: ChatToolbarSpec, mode: ChatMode, onModeChange: (ChatMode) -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = spec.touchTargetDp.dp)
                .widthIn(min = (if (spec.modeShowsLabel) 72 else 44).dp)
                .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .clickable { open = true }
                .padding(horizontal = ChatTokens.GAP_TIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (spec.modeShowsLabel) mode.label + " \u25BE" else "\u25BE",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in ChatMode.dropdownOrder) {
                // Ask needs no tools and works today. Edit and Agent both drive the
                // tool wire, which is on hold pending the advisor ruling - shown, and
                // honestly disabled, rather than pretending to be selectable.
                val usable = m == ChatMode.ASK
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label, fontSize = ChatTokens.FONT_SMALL.sp)
                            if (!usable) {
                                Text(
                                    "Needs the tool wire (on hold)",
                                    color = c.textSecondary,
                                    fontSize = ChatTokens.FONT_MICRO.sp,
                                )
                            }
                        }
                    },
                    enabled = usable,
                    onClick = { open = false; onModeChange(m) },
                )
            }
        }
    }
}

@Composable
private fun ModelChip(
    spec: ChatToolbarSpec,
    modelLabel: String,
    effortLabel: String?,
    onOpenModel: () -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    val text = when {
        modelLabel.isBlank() -> "No model"
        spec.modelShowsEffort && effortLabel != null -> modelLabel + " \u00B7 " + effortLabel
        else -> modelLabel
    }
    Box(
        Modifier
            .heightIn(min = spec.touchTargetDp.dp)
            .widthIn(max = spec.modelChipMaxDp.dp)
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(onClick = onOpenModel)
            .padding(horizontal = ChatTokens.GAP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (modelLabel.isBlank()) c.warning else c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            maxLines = 1,
            textAlign = TextAlign.Start,
        )
    }
}

/** Primary control: arrow, stop square, and the chevron that opens the stop menu. */
@Composable
private fun SendCluster(spec: SendControlSpec, onSend: () -> Unit, onStop: () -> Unit, onSetup: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .background(c.accent, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .clickable(enabled = spec.tapAction != SendAction.NONE) {
                    when (spec.tapAction) {
                        SendAction.SEND -> onSend()
                        SendAction.STOP -> onStop()
                        SendAction.OPEN_SETUP, SendAction.OPEN_MODEL_PICKER -> onSetup()
                        SendAction.NONE -> Unit
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (spec.showsStop) "\u25A0" else "\u2191",
                color = c.bg,
                fontSize = ChatTokens.FONT_BODY.sp,
            )
        }
        if (spec.showsChevron) {
            Box {
                GlyphButton("\u25BE", "Send options", 32) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (item in spec.menu) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.entry.label, fontSize = ChatTokens.FONT_SMALL.sp)
                                        if (item.shortcutHint != null) {
                                            Text(
                                                "  " + item.shortcutHint,
                                                color = c.textSecondary,
                                                fontSize = ChatTokens.FONT_MICRO.sp,
                                            )
                                        }
                                    }
                                    if (item.reason != null) {
                                        Text(
                                            item.reason,
                                            color = c.textSecondary,
                                            fontSize = ChatTokens.FONT_MICRO.sp,
                                        )
                                    }
                                }
                            },
                            enabled = item.enabled,
                            onClick = { menuOpen = false; if (item.enabled) onSend() },
                        )
                    }
                }
            }
        }
    }
}

package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * VS Code-shaped chat composer, adapted to a phone.
 *
 * Differences from the Material field this replaces, all of them taken from the
 * owner's recordings of BOTH chat panels side by side:
 *  - ONE card, not a field with a toolbar underneath. VS Code's toolbar lives inside
 *    the input surface, so the whole control reads as a single object.
 *  - No underline and no floating label. A BasicTextField draws nothing behind the
 *    text, so the lilac Material fill and its bright underline are gone by construction
 *    rather than restyled.
 *  - The card is the MEASURED VS Code surface: #3C3C3C with a 1dp #454545 border.
 *  - The send control is a round accent button, a square glyph while streaming.
 *  - A status row sits outside the card, as in VS Code, carrying the real approval level.
 *
 * The layout decisions still come from ChatToolbarLayout / ChatInputLayout (tested at
 * 280/360/480 with and without the keyboard); this file renders them and decides nothing.
 * Extracted on first write per the JVM 64KB rule.
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
    approvalsLabel: String,
) {
    val c = ChatTokens.LocalChatColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = if (constraints.maxWidth == Int.MAX_VALUE) 2000 else constraints.maxWidth
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val spec = ChatToolbarLayout.forWidth(widthDp, imeVisible)
        val fieldSpec = ChatInputLayout.forWidth(widthDp, imeVisible)
        val send = ChatSendState.resolve(
            streaming = streaming,
            needsSetup = needsSetup,
            modelBlank = modelLabel.isBlank(),
            hasText = value.isNotBlank(),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.input, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                    .border(1.dp, c.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                    .padding(
                        horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                        vertical = ChatTokens.PAD_VERTICAL.dp,
                    ),
            ) {
                ChatComposerField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    maxLines = fieldSpec.fieldMaxLines,
                )
                Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.weight(1f))
                    SendCluster(spec = send, onSend = onSend, onStop = onStop)
                }
            }
            ChatStatusRow(approvalsLabel = approvalsLabel, streaming = streaming)
        }
    }
}

/** Borderless field: nothing is drawn behind the text, which is the whole point. */
@Composable
private fun ChatComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxLines: Int,
) {
    val c = ChatTokens.LocalChatColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        textStyle = TextStyle(color = c.text, fontSize = ChatTokens.FONT_BODY.sp),
        cursorBrush = SolidColor(c.accent),
        maxLines = maxLines,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = c.textSecondary,
                        fontSize = ChatTokens.FONT_BODY.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

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
    val c = ChatTokens.LocalChatColors.current
    var modeOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (item in spec.inline) {
            when (item) {
                ToolbarItem.ADD_CONTEXT -> GlyphButton("+", "Add context", spec.touchTargetDp, onAddContext)
                ToolbarItem.MODE -> ModeChip(
                    label = if (spec.modeShowsLabel) mode.label else null,
                    enabled = true,
                    onClick = { modeOpen = true },
                )
                ToolbarItem.MODEL -> ModelChip(
                    model = modelLabel,
                    effort = if (spec.modelShowsEffort) effortLabel else null,
                    maxDp = spec.modelChipMaxDp,
                    onClick = onOpenModel,
                )
                ToolbarItem.TOOLS -> GlyphButton("\u2699", "Providers and keys", spec.touchTargetDp, onTools)
            }
        }
        if (spec.hasOverflow) {
            Box {
                GlyphButton("\u22EF", "More controls", spec.touchTargetDp) { overflowOpen = true }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    for (item in spec.overflow) {
                        when (item) {
                            ToolbarItem.ADD_CONTEXT -> DropdownMenuItem(
                                text = { Text("Add context") },
                                onClick = { overflowOpen = false; onAddContext() },
                            )
                            ToolbarItem.MODE -> DropdownMenuItem(
                                text = { Text("Mode: " + mode.label) },
                                onClick = { overflowOpen = false; modeOpen = true },
                            )
                            ToolbarItem.MODEL -> DropdownMenuItem(
                                text = { Text("Model: " + modelLabel.ifBlank { "none" }) },
                                onClick = { overflowOpen = false; onOpenModel() },
                            )
                            ToolbarItem.TOOLS -> DropdownMenuItem(
                                text = { Text("Providers and keys") },
                                onClick = { overflowOpen = false; onTools() },
                            )
                        }
                    }
                }
            }
        }
        if (modeOpen) {
            DropdownMenu(expanded = true, onDismissRequest = { modeOpen = false }) {
                for (m in ChatMode.dropdownOrder) {
                    val usable = m == ChatMode.ASK
                    DropdownMenuItem(
                        text = { Text(if (usable) m.label else m.label + " (needs tool wire)") },
                        enabled = usable,
                        onClick = { modeOpen = false; onModeChange(m) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(model: String, effort: String?, maxDp: Int, onClick: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    val label = when {
        model.isBlank() -> "Pick model"
        effort != null -> model + " . " + effort
        else -> model
    }
    Text(
        label,
        color = if (model.isBlank()) c.accent else c.text,
        fontSize = ChatTokens.FONT_SMALL.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = maxDp.dp)
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable { onClick() }
            .padding(horizontal = ChatTokens.GAP.dp, vertical = ChatTokens.GAP_TIGHT.dp),
    )
}

@Composable
private fun ModeChip(label: String?, enabled: Boolean, onClick: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        (label ?: "\u2261"),
        color = if (enabled) c.text else c.textSecondary,
        fontSize = ChatTokens.FONT_SMALL.sp,
        maxLines = 1,
        modifier = Modifier
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = ChatTokens.GAP.dp, vertical = ChatTokens.GAP_TIGHT.dp),
    )
}

@Composable
private fun GlyphButton(glyph: String, description: String, touchDp: Int, onClick: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Box(
        Modifier.size(touchDp.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = c.textSecondary, fontSize = ChatTokens.FONT_BODY.sp, maxLines = 1)
    }
}

@Composable
private fun SendCluster(spec: SendControlSpec, onSend: () -> Unit, onStop: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spec.showsChevron) {
            Box {
                GlyphButton("\u25BE", "Send options", 32) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (item in spec.menu) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.entry.label, fontSize = ChatTokens.FONT_SMALL.sp)
                                    if (!item.enabled) {
                                        Text(
                                            item.reason ?: "Not available yet",
                                            color = c.textSecondary,
                                            fontSize = ChatTokens.FONT_MICRO.sp,
                                        )
                                    }
                                }
                            },
                            enabled = item.enabled,
                            onClick = { menuOpen = false; onStop() },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier
                .size(32.dp)
                .background(
                    if (spec.dimmed) c.surfaceAlt else c.accent,
                    CircleShape,
                )
                .clickable {
                    // STOP is a different destination, not a relabelled send: while a
                    // reply is streaming the same control must cancel it.
                    if (spec.tapAction == SendAction.STOP) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (spec.showsStop) "\u25A0" else "\u2191",
                color = c.bg,
                fontSize = ChatTokens.FONT_SMALL.sp,
                maxLines = 1,
            )
        }
    }
}

/** Outside the card, like VS Code's: on-device badge + the real approval level. */
@Composable
private fun ChatStatusRow(approvalsLabel: String, streaming: Boolean) {
    val c = ChatTokens.LocalChatColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = ChatTokens.GAP_TIGHT.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Local", color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp, maxLines = 1)
        Spacer(Modifier.width(ChatTokens.GAP.dp))
        Text(
            "Approvals: " + approvalsLabel,
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            maxLines = 1,
        )
        if (streaming) {
            Spacer(Modifier.width(ChatTokens.GAP.dp))
            Text("working\u2026", color = c.accent, fontSize = ChatTokens.FONT_MICRO.sp, maxLines = 1)
        }
    }
}

package com.codespace.ide.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.ui.screens.ChatPanelColors

/**
 * SPEC-1..5 mobile chat composer (UI-sweep U01, VS Code chatInputMobile.css parity).
 *
 * Replaces the old single flat row (3x48dp IconButtons + 20dp bare mic + ghost Send
 * squeezing the field to ~180dp on a 412dp screen) with VS Code's phone structure:
 *
 *  - SPEC-1: toolbar row SEPARATE above the input box; whole block ime- and
 *    navigation-bars-padded (fixes the IME covering the send button on-device).
 *  - SPEC-2: flat borderless input box (BasicTextField on a radius-12 surface,
 *    no outline), min height 44dp.
 *  - SPEC-3: 36dp FILLED rounded-square send (desktop ghost circle was invisible
 *    enough to miss; filled reads as THE action).
 *  - SPEC-4: mic inside a 32dp padded target (RP6 voice slot unchanged, gesture
 *    target doubled).
 *  - SPEC-5: rounded-top container for the whole composer block.
 *  - SPEC-U2-1: all icons 16dp. SPEC-U3-3: entry text 16sp. SPEC-U2-3: radii
 *    on-scale (12 container/input, 8 chips).
 */
@Composable
internal fun ChatComposerMobile(
    input: String,
    onInput: (String) -> Unit,
    chatLoading: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    attachEnabled: Boolean,
    implicitCtxOn: Boolean,
    onToggleImplicit: () -> Unit,
    onHistory: () -> Unit,
    historyEnabled: Boolean,
    historyActive: Boolean,
    onMic: () -> Unit,
    colors: ChatPanelColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .background(colors.assistantBubble, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
    ) {
        // ── SPEC-1: toolbar row above the input box ──────────────────────
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerToolIcon(
                icon = Icons.Default.AttachFile,
                description = "Attach file to chat",
                onClick = onAttach,
                enabled = attachEnabled,
                tint = colors.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            ComposerToolIcon(
                icon = Icons.Default.AccountTree,
                description = "Implicit workspace context on/off",
                onClick = onToggleImplicit,
                enabled = true,
                tint = if (implicitCtxOn) colors.accent else colors.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            ComposerToolIcon(
                icon = Icons.Default.History,
                description = "Input history",
                onClick = onHistory,
                enabled = historyEnabled,
                tint = if (historyActive) colors.accent else colors.textSecondary,
            )
        }
        // ── SPEC-1/SPEC-5: input row inside the rounded-top container ────
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // SPEC-2: flat borderless input box + SPEC-U3-3 16sp entry text
            Box(
                Modifier
                    .weight(1f)
                    .background(colors.inputBg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { inner() }
                            if (input.isEmpty()) {
                                Text(
                                    "Ask Copilot\u2026",
                                    fontSize = 14.sp,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    },
                )
            }
            // SPEC-4: mic in a 32dp padded target
            Box(
                Modifier.padding(start = 6.dp).size(32.dp).clickable { onMic() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Mic, "Voice input",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            // SPEC-3: 36dp filled send chip (+ stop while streaming, R8 queue)
            Box(Modifier.padding(start = 6.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (input.isNotBlank()) colors.accent else colors.divider,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = input.isNotBlank()) { onSend(input) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    if (chatLoading) "Queue message" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (chatLoading) {
                Box(Modifier.padding(start = 6.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Color(0xFFEF4444), RoundedCornerShape(8.dp))
                        .clickable { onStop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Stop, "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Compact 32dp toolbar target with a 16dp glyph (SPEC-U3-1 compact tier + U2-1 icon scale). */
@Composable
private fun ComposerToolIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    tint: Color,
) {
    Box(
        Modifier.size(32.dp).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

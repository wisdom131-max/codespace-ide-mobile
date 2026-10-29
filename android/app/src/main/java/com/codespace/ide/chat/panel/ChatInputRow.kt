package com.codespace.ide.chat.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * U1 (build 657 finding e) — the message input, rebuilt around a tested decision.
 *
 * The layout comes from [ChatInputLayout.forWidth] (pure, JVM-tested at 280/360/480 dp
 * with and without the keyboard); this file only renders it. Extracted from
 * ChatPanelScreen (JVM 64KB rule) and kept to layout calls only — no business logic.
 *
 * What changed from the first attempt at this fix: the keyboard state feeds the
 * decision (an open keyboard halves the height, so the field drops to 2 lines and
 * stacking starts at 400 dp instead of 340), labels shorten before they clip, and an
 * ultra-narrow row stacks its actions instead of running off the edge.
 */
@Composable
fun ChatInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    primaryLabel: String,
    primaryColor: Color,
    onAttach: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    val density = LocalDensity.current
    // Real keyboard visibility: the IME inset is non-zero only while the keyboard is
    // up. The navigation-bar inset is subtracted so a gesture bar alone never counts
    // as "keyboard open" (which would shrink the field for no reason).
    val imeBottom = WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)
    val imeVisible = imeBottom > 0

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        val spec = ChatInputLayout.forWidth(maxWidth.value.toInt(), imeVisible)
        // Short labels first, clipping never: "Send anyway?" becomes "Send" at width.
        val sendLabel =
            if (spec.shortLabels) primaryLabel.substringBefore(" ").ifBlank { primaryLabel } else primaryLabel

        val field: @Composable (Modifier) -> Unit = { m ->
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = m.widthIn(min = spec.fieldMinWidthDp.dp),
                placeholder = { Text(placeholder, fontSize = ChatTokens.FONT_SMALL.sp) },
                singleLine = false,
                maxLines = spec.fieldMaxLines,
            )
        }

        val attachText: @Composable (Modifier) -> Unit = { m ->
            Text(
                "Attach",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = m.clickable { onAttach() }.heightIn(min = 44.dp)
                    .padding(vertical = ChatTokens.GAP_TIGHT.dp),
            )
        }
        val primaryText: @Composable (Modifier) -> Unit = { m ->
            Text(
                sendLabel,
                color = primaryColor,
                fontSize = ChatTokens.FONT_SMALL.sp,
                // 44dp touch floor: a bare Text was a ~16dp target on a phone.
                modifier = m.clickable { onPrimary() }.heightIn(min = 44.dp)
                    .padding(vertical = ChatTokens.GAP_TIGHT.dp),
            )
        }

        if (spec.mode == ChatInputMode.STACKED) {
            Column(Modifier.fillMaxWidth()) {
                field(Modifier.fillMaxWidth())
                if (spec.actionsStackVertically) {
                    Column(Modifier.fillMaxWidth().padding(top = ChatTokens.GAP_TIGHT.dp)) {
                        attachText(Modifier.fillMaxWidth())
                        primaryText(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = ChatTokens.GAP_TIGHT.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        attachText(Modifier.padding(end = ChatTokens.PAD_HORIZONTAL.dp))
                        primaryText(Modifier)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f, fill = true))
                attachText(Modifier.padding(start = ChatTokens.GAP.dp))
                primaryText(Modifier.padding(start = ChatTokens.GAP.dp))
            }
        }
    }
}

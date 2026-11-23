package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.engine.ContextMeter

/**
 * PROJECT NINE 10.03 (catalog 92) - the context window panel.
 *
 * Deliberately built from the same pieces as the overflow menu: the same panel radius, the same
 * surface, the same one-pixel border, the same padding and type tokens. It should read as another
 * panel in that menu, not as a screen of its own.
 *
 * Every number is measured by the engine at the moment it builds a request. Nothing here estimates.
 */
@Composable
internal fun ContextWindowPanel(
    colors: ChatColors,
    report: ContextMeter.Report?,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
                .padding(
                    horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                    vertical = ChatTokens.PAD_VERTICAL.dp,
                ),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Context Window",
                        color = colors.text,
                        fontSize = ChatTokens.FONT_BODY.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
                    Text(
                        if (report == null) "No request measured yet"
                        else report.usedLabel + " / " + report.windowLabel + " tokens",
                        color = colors.textSecondary,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                    )
                }
                // The percentage sits in a small outlined chip, the same shape as the composer chips.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                        .border(1.dp, colors.accent, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                        .padding(
                            horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp,
                            vertical = ChatTokens.CHIP_PAD_VERTICAL.dp,
                        ),
                ) {
                    Text(
                        (report?.percent ?: 0).toString() + "%",
                        color = colors.accent,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(ChatTokens.GAP.dp))
            val fraction = ((report?.percent ?: 0) / 100f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.surfaceAlt),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.accent),
                )
                // Reserved for the reply: drawn as hatching, because it is held back rather than used.
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(end = 3.dp)) {
                    repeat(9) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(6.dp)
                                .background(colors.border),
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                }
            }
            Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
            Text(
                "Reserved for response",
                color = colors.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )

            val sections = report?.sections.orEmpty().filter { it.tokens > 0 }
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(ChatTokens.GAP.dp))
                sections.forEach { section ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            section.label,
                            color = colors.textSecondary,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                        )
                        Text(
                            tenthsLabel(section.tokens, report?.windowTokens ?: 0),
                            color = colors.text,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                        )
                    }
                    Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
                }
            }

            Spacer(Modifier.height(ChatTokens.GAP.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable { onCompact() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Compact Conversation",
                    color = colors.text,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
            }
            Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
            Box(
                Modifier.fillMaxWidth().clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Close",
                    color = colors.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
            }
        }
    }
}

/** One decimal place, matching how a share of the window actually reads. */
private fun tenthsLabel(tokens: Int, window: Int): String {
    if (window <= 0) return "0%"
    val tenths = tokens * 1000 / window
    return (tenths / 10).toString() + "." + (tenths % 10) + "%"
}

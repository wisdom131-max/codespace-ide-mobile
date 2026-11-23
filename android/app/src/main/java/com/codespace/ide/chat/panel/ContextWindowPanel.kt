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
import kotlin.math.roundToInt

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
    /**
     * What the PROVIDER reported it billed for this session's last response, or null when the
     * provider sent no usage block. VS Code's widget treats this as the numerator and so does this
     * panel now: an estimate can be wrong in both directions, a reported count cannot.
     */
    usage: ContextMeter.Usage? = null,
    /**
     * The context window of the CURRENTLY SELECTED model. VS Code's denominator follows the
     * selection so switching models moves the percentage immediately; the measured report only
     * knows the window of the model that last sent a request.
     */
    modelWindowTokens: Int? = null,
    /** One line about the EMBEDDING model (downloading, ready, or why it fell back). */
    modelStatus: String? = null,
    modelHidden: Boolean = false,
    onToggleModel: () -> Unit = {},
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
            // The two halves of the VS Code model, computed once here.
            val window = (modelWindowTokens ?: report?.windowTokens ?: 0).coerceAtLeast(0)
            val measured = report?.usedTokens ?: 0
            val used = usage?.totalTokens ?: measured
            val percent = if (window <= 0) 0 else (used * 100.0 / window).roundToInt().coerceIn(0, 100)
            // VS Code: >=90 is an error state, >=75 a warning, and the ring/pie follows the real
            // percentage. Same thresholds here so the two agree on what "nearly full" means.
            val severity = when {
                percent >= 90 -> colors.error
                percent >= 75 -> colors.warning
                else -> colors.accent
            }
            val sourceLine = when {
                usage != null -> "reported by the model (" + ContextMeter.format(usage.promptTokens) +
                    " prompt + " + ContextMeter.format(usage.completionTokens) + " reply)"
                report != null -> "measured estimate - this provider reported no usage"
                else -> ""
            }
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
                        if (report == null && usage == null) "No request measured yet"
                        else ContextMeter.format(used) + " / " + ContextMeter.format(window) + " tokens",
                        color = colors.textSecondary,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                    )
                    if (sourceLine.isNotEmpty()) {
                        Text(
                            sourceLine,
                            color = colors.textSecondary,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                        )
                    }
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
                        percent.toString() + "%",
                        color = severity,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (percent >= 75 && report != null) {
                Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
                Text(
                    if (percent >= 90) "Almost full - compact soon or the next request will be trimmed."
                    else "Quality may decline as limit nears.",
                    color = severity,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
            }

            // DEVICE REPORT T6: the embedding model gets its own row, built from the same pieces as
            // the rest of this panel. It says what the model is doing right now - downloading with a
            // percentage, ready and where it lives, or why it fell back to built-in matching. It can
            // be hidden, and when hidden a single quiet line brings it back.
            if (modelStatus != null && !modelHidden) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Embedding model",
                            color = colors.text,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            modelStatus,
                            color = colors.textSecondary,
                            fontSize = ChatTokens.FONT_SMALL.sp,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                            .clickable { onToggleModel() }
                            .padding(
                                horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp,
                                vertical = ChatTokens.CHIP_PAD_VERTICAL.dp,
                            ),
                    ) {
                        Text("Hide", color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
                    }
                }
                Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
            } else if (modelStatus != null && modelHidden) {
                Text(
                    "Show embedding model status",
                    color = colors.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { onToggleModel() },
                )
                Spacer(Modifier.height(ChatTokens.GAP_TIGHT.dp))
            }

            Spacer(Modifier.height(ChatTokens.GAP.dp))
            val fraction = (percent / 100f).coerceIn(0f, 1f)
            val reserveFraction = if (window <= 0) 0f
                else ((report?.reservedTokens ?: 0).toFloat() / window).coerceIn(0f, 1f - fraction)
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
                // Reserved for the reply. It used to be nine fixed ticks - a decoration that said
                // "some space is held back" without saying how much. Now it is a band as wide as the
                // reserve actually is, so a 4K reserve on a 200K window looks different from a 32K
                // reserve on a 128K window, which is the whole point of showing it.
                if (reserveFraction > 0f) {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(fraction.coerceAtLeast(0.0001f)))
                        Box(
                            Modifier
                                .weight(reserveFraction.coerceAtLeast(0.0001f))
                                .height(6.dp)
                                .background(colors.warning.copy(alpha = 0.45f)),
                        )
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
                            // VS Code lists each part as a share of the PROMPT (percentageOfPrompt),
                            // not only as a share of the whole window. Both are here: the prompt share
                            // answers "where did my tokens go", the window share answers "how full".
                            (if (used <= 0) 0 else (section.tokens * 100 / used)).toString() + "% · " +
                                tenthsLabel(section.tokens, window),
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

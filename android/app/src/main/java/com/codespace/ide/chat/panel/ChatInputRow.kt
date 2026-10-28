package com.codespace.ide.chat.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * U1 (build 657 finding e): the message input collapsed to a sliver.
 *
 * The old row was a single Row with a weight(1f) TextField and two unweighted Texts.
 * In a narrow panel the two labels claim their intrinsic width first and the field
 * received the remainder - near zero - so the input was unusable exactly where it
 * matters (split view). Below [STACK_BELOW_DP] the field takes its own full-width line
 * and the actions move beneath it, so the field can never be squeezed out.
 *
 * Extracted from ChatPanelScreen on first write (JVM 64KB rule).
 */
private val STACK_BELOW_DP = 340.dp

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
    val field: @Composable (Modifier) -> Unit = { m ->
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = m.widthIn(min = 130.dp),
            placeholder = { Text(placeholder, fontSize = ChatTokens.FONT_SMALL.sp) },
            singleLine = false,
            maxLines = 4,
        )
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        val narrow = maxWidth < STACK_BELOW_DP
        if (narrow) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                field(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(top = ChatTokens.GAP_TIGHT.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Attach",
                        color = c.textSecondary,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier.padding(end = ChatTokens.PAD_HORIZONTAL.dp).clickable { onAttach() },
                    )
                    Text(
                        primaryLabel,
                        color = primaryColor,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier.clickable { onPrimary() },
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f, fill = true))
                Text(
                    "Attach",
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.padding(start = ChatTokens.GAP.dp).clickable { onAttach() },
                )
                Text(
                    primaryLabel,
                    color = primaryColor,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.padding(start = ChatTokens.GAP.dp).clickable { onPrimary() },
                )
            }
        }
    }
}

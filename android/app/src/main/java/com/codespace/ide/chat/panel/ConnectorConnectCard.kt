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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * The inline connector card — the chat half of a tool that already existed but was never shown.
 *
 * Before this file: `request_connector` set a side-channel flag that only the DEAD legacy
 * `CopilotChatPanelInline` consumed, so in the live panel the tool's promise ("the user is being
 * shown a Connect card") was false. The card is the surface that makes it true.
 *
 * Both buttons are wired to something real: Connect opens the Connectors Hub focused on the
 * service, where that service runs its own OAuth/token flow (the hub is the only place that can
 * finish a sign-in), and Reject dismisses the card. Reject does NOT claim the assistant was told:
 * the model learns about it on the next turn, and the note says exactly that.
 */
@Composable
internal fun ConnectorConnectCard(
    displayName: String,
    colors: ChatColors,
    onConnect: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .background(colors.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .padding(ChatTokens.PAD_HORIZONTAL.dp),
    ) {
        Text(
            displayName,
            color = colors.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "We need your permission to read and interact with " + displayName + ".",
            color = colors.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable(onClickLabel = "Reject this connector", onClick = onReject),
                contentAlignment = Alignment.Center,
            ) {
                Text("Reject", color = colors.text, fontSize = ChatTokens.FONT_SMALL.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .background(colors.text, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable(onClickLabel = "Open the connectors hub to connect", onClick = onConnect),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connect", color = colors.bg, fontSize = ChatTokens.FONT_SMALL.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

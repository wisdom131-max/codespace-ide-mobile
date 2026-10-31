package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.AgentQuestionGate
import com.codespace.ide.chat.ui.ChatTokens

/**
 * vscode_askQuestions / vscode_get_confirmation card.
 *
 * The model is SUSPENDED while this is on screen: whatever the user taps here is what the tool
 * returns. Same visual family as ChatApprovalCard (rounded panel, tight padding, one action row)
 * because it is the same kind of interruption.
 */
@Composable
internal fun ChatQuestionCard() {
    val c = ChatTokens.LocalChatColors.current
    val pending by AgentQuestionGate.pending.collectAsState()
    val current = pending ?: return
    var freeText by remember(current) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.COMPOSER_WRAP_PAD_H.dp, vertical = ChatTokens.COMPOSER_WRAP_PAD_V.dp)
            .background(c.surface, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .border(1.dp, c.accent, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            current.question,
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.Medium,
        )
        if (current.options.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                current.options.forEach { option ->
                    Text(
                        option,
                        color = c.accent,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .background(c.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, c.accent, RoundedCornerShape(10.dp))
                            .clickable { AgentQuestionGate.answer(option, null) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (current.allowFreeText) {
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                placeholder = { Text("Answer", fontSize = ChatTokens.FONT_SMALL.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "Send",
                    color = if (freeText.isBlank()) c.textSecondary else c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .clickable(enabled = freeText.isNotBlank()) {
                            AgentQuestionGate.answer(null, freeText)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "Skip",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier
                    .clickable { AgentQuestionGate.dismiss() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

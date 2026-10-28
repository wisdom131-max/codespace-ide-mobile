package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * U1 (build 657 findings a + d): the panel's honest "Set up a provider" empty
 * state. The pure resolution rules live in ChatPanelSetupLogic.kt (no Compose
 * imports, so the JVM suite tests them directly).
 */

/**
 * Empty state: nothing usable is configured, so the panel says so instead of
 * offering a send that is certain to fail.
 */
@Composable
fun SetupNeededState(onOpenProviders: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        Text(
            "Set up a provider",
            color = c.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "No AI provider is usable yet. Add a key for Gemini, Anthropic, OpenAI or another provider, " +
                "or add a custom OpenAI-compatible endpoint. Keys you saved before are listed there.",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onOpenProviders) { Text("Choose provider / add key") }
    }
}

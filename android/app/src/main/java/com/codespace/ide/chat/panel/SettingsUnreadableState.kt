package com.codespace.ide.chat.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * B1 (U9 P1, 2026-10-30): the settings stores could not be read, so the selection cannot be
 * validated. Deliberately NOT the setup screen: nothing is missing, the read failed, and the
 * user's provider/model pair is still intact. Retry re-runs resolution in place.
 *
 * Rule compliance: rounded 12dp surface, 12dp horizontal / 10dp vertical padding.
 */
@Composable
internal fun SettingsUnreadableState(detail: String, onRetry: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Provider settings could not be read",
            color = c.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            detail + " Your selected provider and model are kept; nothing was changed.",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
        )
        Text(
            "This is usually a temporary storage problem. Retry, or reopen this chat.",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/**
 * B2: shown above the model picker when an endpoint's saved model list (manual or live cache)
 * could not be read. The bundled catalog still renders below it, so the picker is never
 * presented as if the endpoint had no models.
 */
@Composable
internal fun ModelListUnreadableNotice(onRetry: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "This endpoint's saved model list could not be read",
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Your selection is unchanged. The models below are the built-in catalog.",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

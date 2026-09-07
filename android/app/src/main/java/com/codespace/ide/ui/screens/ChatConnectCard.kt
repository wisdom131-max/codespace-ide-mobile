package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1 C3 (Item 4): Base44-style inline "Connect <Service>" card for the
 * Copilot chat transcript. Rendered for messages with role "card_connect"
 * (text = service id). Generic across ALL connector types — OAuth and PAT
 * both just open the Connectors Hub, where each service runs its own flow.
 * Own file per the 64KB bytecode rule.
 */
@Composable
internal fun ChatConnectCard(
    serviceId: String,
    accent: Color,
    text: Color,
    textSecondary: Color,
    surface: Color,
    onConnect: () -> Unit,
) {
    val name = when (serviceId) {
        "gmail" -> "Gmail"; "gcalendar" -> "Google Calendar"; "gdrive" -> "Google Drive"
        "slack" -> "Slack"
        "sentry" -> "Sentry"; "vercel" -> "Vercel"; "cloudflare" -> "Cloudflare"
        "posthog" -> "PostHog"; "stripe" -> "Stripe"; "railway" -> "Railway"; "render" -> "Render"
        "gitlab" -> "GitLab"; "notion" -> "Notion"; "figma" -> "Figma"; "linear" -> "Linear"
        "jira" -> "Jira"; "discord" -> "Discord"; "canva" -> "Canva"; "huggingface" -> "Hugging Face"
        else -> serviceId.replaceFirstChar { it.uppercase() }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Cable, null,
                tint = accent, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Connect $name",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = text,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "— needs your sign-in / API token",
                    fontSize = 10.sp, color = textSecondary,
                )
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Connect", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

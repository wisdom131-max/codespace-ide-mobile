package com.codespace.ide.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * Phase 1 (Item 4): paste-token dialog for PAT-type connectors
 * (Sentry / Vercel / Cloudflare / PostHog / Stripe / Railway / Render).
 * The token is sent once to the backend, encrypted at rest there, and never
 * echoed back or displayed again. Own file per the 64KB bytecode rule.
 *
 * onConnect runs the network save in the caller's coroutine scope and reports
 * back via the callback — the dialog itself never blocks the main thread.
 */
@Composable
internal fun ConnectorPatDialog(
    serviceName: String,
    tokenHint: String?,
    tokenHelpUrl: String?,
    onConnect: (pat: String, onResult: (Boolean, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Connect $serviceName", color = Color(0xFFD4D4D4), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column {
                Text(
                    "Paste a personal API token. It is stored encrypted on the backend and never shown again.",
                    color = Color(0xFF858585), fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = pat,
                    onValueChange = { pat = it; error = null },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFD4D4D4)),
                    placeholder = {
                        Text(tokenHint ?: "API token", color = Color(0xFF6A6A6A), fontSize = 12.sp)
                    },
                    isError = error != null,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFF48771), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (tokenHelpUrl != null) {
                    Spacer(Modifier.width(4.dp))
                    Row(
                        Modifier.padding(top = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(tokenHelpUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) { }
                        }) {
                            Icon(Icons.Default.OpenInNew, null, tint = Color(0xFF007ACC), modifier = Modifier.padding(end = 4.dp).width(14.dp))
                            Text("Create a token at $tokenHelpUrl", color = Color(0xFF007ACC), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pat.isNotBlank(),
                onClick = {
                    busy = true
                    onConnect(pat.trim()) { ok, msg ->
                        busy = false
                        if (ok) onDismiss() else error = msg
                    }
                },
            ) { Text(if (busy) "Saving…" else "Connect", color = Color(0xFF007ACC), fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF858585), fontSize = 13.sp) } },
    )
}

/** The hub passes display names; map back to registry ids for the API call. */
internal fun serviceIdFromName(name: String): String = when (name) {
    "Gmail" -> "gmail"; "Google Calendar" -> "gcalendar"; "Google Drive" -> "gdrive"
    "Slack" -> "slack"
    "Sentry" -> "sentry"; "Vercel" -> "vercel"; "Cloudflare" -> "cloudflare"
    "PostHog" -> "posthog"; "Stripe" -> "stripe"; "Railway" -> "railway"; "Render" -> "render"
    "GitLab" -> "gitlab"; "Notion" -> "notion"; "Figma" -> "figma"; "Linear" -> "linear"
    "Jira" -> "jira"; "Discord" -> "discord"; "Canva" -> "canva"; "Hugging Face" -> "huggingface"
    else -> name.lowercase()
}

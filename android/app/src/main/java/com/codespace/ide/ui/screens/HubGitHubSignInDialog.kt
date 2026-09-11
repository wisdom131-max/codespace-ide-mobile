package com.codespace.ide.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.codespace.ide.data.GitHubAuth
import com.codespace.ide.data.SecureTokenStore

/**
 * HUB-GITHUB-PROPAGATION (2026-09-10): on-device repro showed that signing into
 * GitHub from Settings > Accounts correctly feeds the Source Control panel, but the
 * Connectors Hub's GitHub row was a dead-end pointer that only dismissed the sheet —
 * it neither showed the live state nor offered sign-in, so anything done from the Hub
 * "didn't propagate". Root cause: GitHub is NOT a backend connector (no OAuth app);
 * it is the local Device Flow that writes SecureTokenStore.githubToken /
 * githubUsername — the exact state Settings and Source Control both read.
 *
 * This dialog runs the IDENTICAL Device Flow (GitHubAuth.requestDeviceCode ->
 * pollForToken -> fetchUsername) and writes the SAME SecureTokenStore keys, so a
 * Hub sign-in now propagates everywhere Settings' sign-in does. Extracted into its
 * own file per the standing extraction rule.
 */
@Composable
internal fun HubGitHubSignInDialog(
    onDismiss: () -> Unit,
    onSuccess: (username: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var deviceCode by remember { mutableStateOf<GitHubAuth.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Kick off the device flow as soon as the dialog is composed. LaunchedEffect is
    // cancelled automatically when the dialog leaves composition (Cancel/dismiss),
    // which also stops the poll loop.
    LaunchedEffect(Unit) {
        try {
            val device = GitHubAuth.requestDeviceCode()
            deviceCode = device
            val token = GitHubAuth.pollForToken(device)
            val username = GitHubAuth.fetchUsername(token)
            val tokenStore = SecureTokenStore(context)
            tokenStore.githubToken = token
            tokenStore.githubUsername = username
            onSuccess(username)
        } catch (e: Exception) {
            error = e.message ?: "Sign-in failed"
        }
    }

    AlertDialog(
        onDismissRequest = { /* must Cancel explicitly — polling is still running */ },
        shape = MaterialTheme.shapes.medium,
        title = { Text("Connect GitHub") },
        text = {
            Column {
                val device = deviceCode
                if (device != null) {
                    Text("1. Open this on any device:")
                    Text(device.verificationUri, style = MaterialTheme.typography.bodyMedium)
                    Text("2. Enter this code:", modifier = Modifier.padding(top = 12.dp))
                    Text(
                        device.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
                } else if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Starting GitHub sign-in…")
                }
            }
        },
        confirmButton = {
            Row {
                val device = deviceCode
                if (device != null) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(device.userCode)) }) {
                        Text("Copy code")
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Open GitHub") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancel") }
        },
    )
}

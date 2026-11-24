package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.data.AuthEvents
import com.codespace.ide.data.ConnectorsApiClient
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PHASE 4c (owner instruction 2026-11-15): the connector card IS the setup, not a door.
 *
 * Asking Copilot for a connector used to set `showHub = true`, so the user was handed to the
 * Connectors Hub and had to find the service again. The hub is unchanged for Settings; this
 * surface does the same work in place, through the same single credential-write path
 * ([ConnectorsApiClient.savePat]) and the same consent page the hub opens, so there is one writer
 * and one refresh signal ([AuthEvents.bump]).
 */
@Composable
internal fun ConnectorSetupInline(
    service: String,
    displayName: String,
    colors: ChatColors,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    /** Open straight into change mode: the owner asked to alter permissions. */
    startReauthorising: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = remember { SecureTokenStore(context).lastAccessToken.orEmpty() }

    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<ConnectorsApiClient.ConnectorStatus?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // OWNER REQUEST (2026-11-24): a connected service can still need its card, to change what it is
    // allowed to do. Change mode drops the card back to its setup flow, which re-consents with the
    // access the owner picks, instead of stopping at "already connected".
    var reauthorising by remember(service) { mutableStateOf(startReauthorising) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(service) {
        val fetched = withContext(Dispatchers.IO) {
            ConnectorsApiClient.fetchStatus(token, context)
        }
        fetched.fold(
            onSuccess = { list -> status = list.firstOrNull { it.id == service } },
            onFailure = { loadError = it.message ?: "Could not read connector settings" },
        )
        loading = false
    }

    val flow = ConnectorSetupPlan.flowFor(status?.authType)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (reauthorising) "Change permissions for " + displayName else "Connect " + displayName,
            color = colors.text, fontSize = 13.sp,
        )
        when {
            loading -> Text("Reading connector settings...", color = colors.textSecondary, fontSize = 11.sp)
            loadError != null -> {
                Text(loadError ?: "", color = colors.error, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel) { Text("Close", fontSize = 12.sp) }
                }
            }
            ConnectorSetupPlan.isSettled(status?.connected == true) && !reauthorising -> {
                Text(displayName + " is already connected.", color = colors.success, fontSize = 11.sp)
                Text(
                    "Need it to do more, or less? Change permissions re-consents with the access you pick.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { reauthorising = true }, enabled = !busy) {
                        Text("Change permissions", fontSize = 12.sp)
                    }
                    TextButton(onClick = { onDone(displayName + " is already connected.") }) {
                        Text("Done", fontSize = 12.sp)
                    }
                }
            }
            flow == ConnectorSetupPlan.Flow.PAT -> {
                Text(
                    status?.tokenHint ?: "Paste a personal access token. It is stored encrypted.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = pat,
                    onValueChange = { pat = it },
                    singleLine = true,
                    label = { Text("Token", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error ?: "", color = colors.error, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", fontSize = 12.sp) }
                    TextButton(
                        enabled = !busy && pat.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ConnectorsApiClient.savePat(token, service, pat.trim(), context)
                                }
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        AuthEvents.bump()
                                        onDone(displayName + " connected.")
                                    },
                                    onFailure = { error = it.message ?: "Could not save the token" },
                                )
                            }
                        },
                    ) { Text(if (busy) "Connecting..." else "Connect", fontSize = 12.sp) }
                }
            }
            else -> {
                Text(
                    "This one signs in through its own website. The consent page opens in your " +
                        "browser; nothing else is needed here.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
                if (error != null) Text(error ?: "", color = colors.error, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", fontSize = 12.sp) }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ConnectorsApiClient.fetchAuthUrl(token, service, context)
                                }
                                busy = false
                                result.fold(
                                    onSuccess = { url ->
                                        val opened = runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(url),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                        if (opened.isSuccess) {
                                            onDone("Authorise " + displayName + " in your browser. " +
                                                "Its status updates when you come back.")
                                        } else {
                                            error = "Could not open a browser. The link is on the " +
                                                "connectors page."
                                        }
                                    },
                                    onFailure = { error = it.message ?: "Could not build the sign-in link" },
                                )
                            }
                        },
                    ) { Text(if (busy) "Opening..." else "Open sign-in page", fontSize = 12.sp) }
                }
            }
        }
    }
}

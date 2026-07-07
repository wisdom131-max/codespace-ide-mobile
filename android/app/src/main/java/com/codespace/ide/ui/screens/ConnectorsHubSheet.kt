package com.codespace.ide.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.ConnectorsApiClient
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connectors Hub — Gmail/Calendar/Drive/Slack rows now show REAL live status from the
 * backend (backend/src/connectors/ (TypeScript files) on Railway) and drive a real browser-based OAuth
 * flow, instead of the old dismiss-only stub rows. GitHub/SSH/AI Providers/Services rows
 * are separate systems, left as-is here.
 */
@Composable
internal fun ConnectorsHubSheet(
    onDismiss: () -> Unit,
) {
    val MenuBg   = Color(0xFF252526)
    val MenuText = Color(0xFFCCCCCC)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessToken = remember { SecureTokenStore(context).lastAccessToken.orEmpty() }

    var statuses by remember { mutableStateOf<List<ConnectorsApiClient.ConnectorStatus>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var busyService by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        if (accessToken.isBlank()) {
            loadError = "Sign in to CodeSpace IDE first to manage connectors."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) { ConnectorsApiClient.fetchStatus(accessToken) }
        result.fold(
            onSuccess = { statuses = it },
            onFailure = { loadError = it.message ?: "Failed to load connector status" },
        )
        loading = false
    }


    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable { onDismiss() }
    ) {
        Card(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 0.dp)
                .fillMaxWidth()
                .clickable(onClick = {}), // eat clicks so card doesn't dismiss
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MenuBg),
            elevation = CardDefaults.cardElevation(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                // Handle bar
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color(0xFF555555), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connectors", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MenuText)
                        Text("Sign in and manage services", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                    Icon(
                        Icons.Default.Refresh, "Refresh", tint = MenuText,
                        modifier = Modifier.size(20.dp).clickable { refreshKey++ }
                    )
                }
                Spacer(Modifier.height(16.dp))

                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF007ACC), strokeWidth = 2.dp)
                    }
                    loadError != null -> Text(loadError!!, fontSize = 12.sp, color = Color(0xFFE06C75))
                    else -> {
                        val iconFor = mapOf(
                            "gmail" to Icons.Default.Email,
                            "gcalendar" to Icons.Default.CalendarMonth,
                            "gdrive" to Icons.Default.Cloud,
                            "slack" to Icons.Default.Chat,
                        )
                        val colorFor = mapOf(
                            "gmail" to Color(0xFFD93025),
                            "gcalendar" to Color(0xFF1A73E8),
                            "gdrive" to Color(0xFF34A853),
                            "slack" to Color(0xFF4A154B),
                        )
                        statuses.forEach { s ->
                            ConnectorStatusRow(
                                icon = iconFor[s.id] ?: Icons.Default.Cloud,
                                name = s.name,
                                status = s,
                                color = colorFor[s.id] ?: Color(0xFF1565C0),
                                menuText = MenuText,
                                busy = busyService == s.id,
                                onConnect = {
                                    busyService = s.id
                                    toast = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ConnectorsApiClient.fetchAuthUrl(accessToken, s.id)
                                        }
                                        busyService = null
                                        result.fold(
                                            onSuccess = { authUrl ->
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                }.onFailure { toast = "Couldn't open browser: ${it.message}" }
                                            },
                                            onFailure = { toast = it.message ?: "Failed to start connecting ${s.name}" },
                                        )
                                    }
                                },
                                onDisconnect = {
                                    busyService = s.id
                                    toast = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ConnectorsApiClient.disconnect(accessToken, s.id)
                                        }
                                        busyService = null
                                        result.fold(
                                            onSuccess = { refreshKey++ },
                                            onFailure = { toast = it.message ?: "Failed to disconnect ${s.name}" },
                                        )
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        toast?.let {
                            Text(it, fontSize = 11.sp, color = Color(0xFFE5C07B), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF3C3C3C))
                Spacer(Modifier.height(8.dp))

                // GitHub — separate, already-working Device Flow system (Settings > Accounts)
                ConnectorRow(
                    icon = Icons.Default.Code,
                    name = "GitHub",
                    subtitle = "Sign in from Settings > Accounts",
                    color = Color(0xFF6E40C9),
                    menuText = MenuText,
                    onClick = { onDismiss() }
                )
                Spacer(Modifier.height(8.dp))
                // SSH
                ConnectorRow(
                    icon = Icons.Default.Computer,
                    name = "SSH",
                    subtitle = "Remote server access",
                    color = Color(0xFF0097A7),
                    menuText = MenuText,
                    onClick = { onDismiss() }
                )
                Spacer(Modifier.height(8.dp))
                // AI Keys
                ConnectorRow(
                    icon = Icons.Default.SmartToy,
                    name = "AI Providers",
                    subtitle = "OpenAI, Anthropic, Gemini keys",
                    color = Color(0xFF7B1FA2),
                    menuText = MenuText,
                    onClick = { onDismiss() }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun ConnectorStatusRow(
    icon: ImageVector,
    name: String,
    status: ConnectorsApiClient.ConnectorStatus,
    color: Color,
    menuText: Color,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0x1A007ACC), RoundedCornerShape(8.dp))
            .clickable(enabled = !busy && status.configured) {
                if (status.connected) onDisconnect() else onConnect()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = menuText)
            Text(
                when {
                    !status.configured -> "Not set up yet"
                    status.connected -> "Connected — tap to disconnect"
                    else -> "Tap to connect"
                },
                fontSize = 11.sp,
                color = if (status.connected) Color(0xFF98C379) else Color(0xFF888888),
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
        } else {
            Icon(
                if (status.connected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                null,
                tint = if (status.connected) Color(0xFF98C379) else Color(0xFF555555),
                modifier = Modifier.size(if (status.connected) 18.dp else 16.dp),
            )
        }
    }
}

@Composable
internal fun ConnectorRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    color: Color,
    menuText: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0x1A007ACC), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = menuText)
            Text(subtitle, fontSize = 11.sp, color = Color(0xFF888888))
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF555555), modifier = Modifier.size(16.dp))
    }
}

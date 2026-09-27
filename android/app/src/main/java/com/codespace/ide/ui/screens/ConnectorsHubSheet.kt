package com.codespace.ide.ui.screens

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import com.codespace.ide.data.ConnectorsApiClient
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.automirrored.filled.*

/**
 * Connectors Hub — Gmail/Calendar/Drive/Slack rows now show REAL live status from the
 * backend (backend/src/connectors/ on Render) and drive a real browser-based OAuth
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
    // IG07 (2026-09-27): the in-app OAuth WebView + its callback-capture machinery
    // (oauthWebViewUrl / oauthCallbackUrl / completeOAuthCallback delivery) were
    // DELETED — AgentConnectorManager's own docs say Google/Slack BLOCK embedded
    // WebViews for OAuth ("disallowed_useragent"), and the chat path already opens
    // the external browser. The Hub now uses the SAME external-browser transport; the
    // browser follows the callback to the backend directly, so no capture exists to
    // deliver. pendingOAuthId drives a status poll that flips the row when the user
    // finishes signing in.
    // Phase 1: PAT paste-token dialog target (Sentry/Vercel/Cloudflare/PostHog/Stripe/Railway/Render)
    var patDialogStatus by remember { mutableStateOf<ConnectorsApiClient.ConnectorStatus?>(null) }
    var pendingOAuthId by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var busyService by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // HUB-GITHUB-PROPAGATION: live GitHub Device Flow state (SecureTokenStore keys —
    // the same ones Settings > Accounts and Source Control read/write).
    var githubUser by remember { mutableStateOf(SecureTokenStore(context).githubUsername) }
    var showGithubDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        if (accessToken.isBlank()) {
            loadError = "Sign in to VN Code first to manage connectors."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) { ConnectorsApiClient.fetchStatus(accessToken, context) }
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
        // Bug-2 fix (2026-09-10): cap the sheet at 85% of screen height —
        // previously the Card wrapped ALL connector rows and any row past the
        // screen edge was clipped and unreachable (no scroll anywhere).
        val sheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Card(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 0.dp)
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .clickable(onClick = {}), // eat clicks so card doesn't dismiss
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MenuBg),
            elevation = CardDefaults.cardElevation(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                // Handle bar — P54 fix (2026-09-10): was a purely decorative Box.
                // Now supports the standard bottom-sheet gestures: drag DOWN past
                // ~56dp (or tap the grab area) dismisses the sheet.
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(Unit) {
                            var dragAccumPx = 0f
                            val dismissThresholdPx = with(density) { 56.dp.toPx() }
                            detectVerticalDragGestures(
                                onDragStart = { dragAccumPx = 0f },
                                onDragEnd = {
                                    if (dragAccumPx >= dismissThresholdPx) onDismiss()
                                },
                                onDragCancel = { },
                            ) { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0f) dragAccumPx += dragAmount else dragAccumPx = 0f
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { onDismiss() }
                        }
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .width(40.dp)
                            .height(4.dp)
                            .background(Color(0xFF555555), RoundedCornerShape(2.dp))
                    )
                }
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
                            "slack" to Icons.AutoMirrored.Filled.Chat,
                            "sentry" to Icons.Default.BugReport,
                            "vercel" to Icons.Default.ChangeHistory,
                            "cloudflare" to Icons.Default.Cloud,
                            "posthog" to Icons.Default.Insights,
                            "stripe" to Icons.Default.CreditCard,
                            "railway" to Icons.Default.Train,
                            "render" to Icons.Default.RocketLaunch,
                            "gitlab" to Icons.Default.Code,
                            "notion" to Icons.Default.Description,
                            "figma" to Icons.Default.DesignServices,
                            "linear" to Icons.Default.LinearScale,
                            "jira" to Icons.Default.Layers,
                            "discord" to Icons.Default.Forum,
                            "canva" to Icons.Default.Palette,
                            "huggingface" to Icons.Default.SentimentVerySatisfied,
                        )
                        val colorFor = mapOf(
                            "gmail" to Color(0xFFD93025),
                            "gcalendar" to Color(0xFF1A73E8),
                            "gdrive" to Color(0xFF34A853),
                            "slack" to Color(0xFF4A154B),
                            "sentry" to Color(0xFF6C5FC7),
                            "vercel" to Color(0xFFEDEDED),
                            "cloudflare" to Color(0xFFF38020),
                            "posthog" to Color(0xFFF54E00),
                            "stripe" to Color(0xFF635BFF),
                            "railway" to Color(0xFF9500E5),
                            "render" to Color(0xFF46E3B7),
                            "gitlab" to Color(0xFFFC6D26),
                            "notion" to Color(0xFFB3B3B3),
                            "figma" to Color(0xFFF24E1E),
                            "linear" to Color(0xFF5E6AD2),
                            "jira" to Color(0xFF0052CC),
                            "discord" to Color(0xFF5865F2),
                            "canva" to Color(0xFF7D2AE8),
                            "huggingface" to Color(0xFFFFD21E),
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
                                    if (s.authType == "pat") {
                                        // Phase 1: PAT services paste a token — no OAuth page involved
                                        patDialogStatus = s
                                    } else {
                                    busyService = s.id
                                    toast = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ConnectorsApiClient.fetchAuthUrl(accessToken, s.id, context)
                                        }
                                        busyService = null
                                        result.fold(
                                            onSuccess = { authUrl ->
                                                // IG07: external browser — the transport
                                                // Google/Slack actually allow (the in-app
                                                // WebView got "disallowed_useragent").
                                                try {
                                                    val intent = android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(authUrl),
                                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    pendingOAuthId = s.id
                                                    toast = "Finish signing in to ${s.name} in your browser — this row updates itself when you're done."
                                                } catch (e: Exception) {
                                                    toast = "Got the sign-in link but couldn't open a browser: ${e.message}\n$authUrl"
                                                }
                                            },
                                            onFailure = { toast = it.message ?: "Failed to start connecting ${s.name}" },
                                        )
                                        }
                                    }
                                },
                                onDisconnect = {
                                    busyService = s.id
                                    toast = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ConnectorsApiClient.disconnect(accessToken, s.id, context)
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

                // GitHub — HUB-GITHUB-PROPAGATION (2026-09-10): was a dead pointer row
                // that only dismissed the sheet. Now shows the LIVE SecureTokenStore state
                // (same keys Settings > Accounts writes and Source Control reads), offers
                // the identical Device Flow sign-in from the Hub, and signs out on tap when
                // connected — so the Hub finally propagates to the shared auth state.
                ConnectorRow(
                    icon = Icons.Default.Code,
                    name = "GitHub",
                    subtitle = if (githubUser != null)
                        "Connected as $githubUser — tap to sign out"
                    else
                        "Sign in with device code (same as Settings > Accounts)",
                    color = Color(0xFF6E40C9),
                    menuText = MenuText,
                    onClick = {
                        if (githubUser != null) {
                            val tokenStore = SecureTokenStore(context)
                            tokenStore.githubToken = null
                            tokenStore.githubUsername = null
                            githubUser = null
                            toast = "Signed out of GitHub"
                        } else {
                            showGithubDialog = true
                        }
                    }
                )
                if (showGithubDialog) {
                    HubGitHubSignInDialog(
                        onDismiss = { showGithubDialog = false },
                        onSuccess = { username ->
                            showGithubDialog = false
                            githubUser = username
                            toast = "✓ Connected to GitHub as $username"
                        },
                    )
                }
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

    // ── Phase 1: PAT paste-token dialog ────────────────────────────────────────
    patDialogStatus?.let { target ->
        ConnectorPatDialog(
            serviceName = target.name,
            tokenHint = target.tokenHint,
            tokenHelpUrl = target.tokenHelpUrl,
            onConnect = { pat, onResult ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ConnectorsApiClient.savePat(accessToken, target.id, pat, context)
                    }
                    result.fold(
                        onSuccess = {
                            refreshKey++  // re-fetch statuses so the row flips to Connected
                            onResult(true, "${target.name} connected.")
                        },
                        onFailure = { onResult(false, it.message ?: "Failed to save token") },
                    )
                }
            },
            onDismiss = { patDialogStatus = null },
        )
    }

    // ── IG07 (2026-09-27): OAuth completion poll ──────────────────────────────
    // The external browser delivers the callback to the backend directly; the app
    // polls the service status every 3s (up to 3 min) while a sign-in is pending, so
    // the row flips to Connected without the user re-opening the sheet.
    LaunchedEffect(pendingOAuthId) {
        val service = pendingOAuthId ?: return@LaunchedEffect
        var waited = 0
        while (waited < 180_000) {
            kotlinx.coroutines.delay(3_000)
            waited += 3_000
            val result = withContext(Dispatchers.IO) { ConnectorsApiClient.fetchStatus(accessToken, context) }
            val connected = result.getOrNull()?.any { it.id == service && it.connected } == true
            if (connected) {
                pendingOAuthId = null
                toast = "\u2713 Connected"
                refreshKey++
                return@LaunchedEffect
            }
        }
        // Timed out — refresh once anyway; the user may still be mid-flow.
        refreshKey++
        pendingOAuthId = null
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

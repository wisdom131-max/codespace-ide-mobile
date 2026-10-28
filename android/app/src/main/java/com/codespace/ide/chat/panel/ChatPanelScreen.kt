package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.keys.StoredEndpoint
import com.codespace.ide.chat.keys.StoredKeySlot
import com.codespace.ide.chat.ui.ChatRenderState
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.chat.ui.NativeChatListRenderer
import kotlinx.coroutines.delay

/**
 * WP2 (d-min): the chat panel on the rebuilt engine. Native renderer behind
 * the ChatListRenderer interface (WebView can replace it later), input row
 * with send/cancel, retry, provider/model/key selection from the WP1
 * read-only migration, "Thinking Ns" until the first delta, send budget with
 * "Send anyway?". Attachment chips are a WP8 placeholder (rendered by the
 * same component library when attachments return).
 */
@Composable
fun ChatPanelScreen(projectId: String, tokenStore: com.codespace.ide.data.SecureTokenStore) {
    val manager = remember {
        ChatSessionManager(com.codespace.ide.chat.keys.StoredCredentialsReader(
            tokenStore, com.codespace.ide.chat.CustomEndpointStore))
    }
    DisposableEffect(Unit) { onDispose { manager.close() } }
    val session = remember(projectId) { manager.session(projectId) }
    val state by session.ui.collectAsState()
    val selection by session.selection.collectAsState()
    var input by remember { mutableStateOf("") }
    var showSelect by remember { mutableStateOf(false) }
    val c = ChatTokens.LocalChatColors.current
    val scroll = rememberScrollState()

    // "Thinking Ns" ticker: recompute while streaming with no visible delta yet.
    var thinkTickMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.streaming, state.firstDeltaMs) {
        while (state.streaming && state.firstDeltaMs == 0L) {
            thinkTickMs = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selection.providerId + "  ·  " + selection.modelId,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .weight(1f)
                    .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable { showSelect = true }
                    .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.GAP.dp),
            )
        }
        Column(
            Modifier.weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)
        ) {
            val rows = if (state.streaming && state.firstDeltaMs == 0L)
                state.rows + listOf(ChatRowModel.Thinking(((thinkTickMs - state.streamStartedMs) / 1000L).coerceAtLeast(0L).toInt()))
            else state.rows
            NativeChatListRenderer.MessageList(
                ChatRenderState(
                    rows = rows,
                    onRetry = { session.retry() },
                    onSendAnyway = { session.sendAnyway() },
                    onCancel = { session.dismissOverBudget() },
                ),
                Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message " + selection.providerId, fontSize = ChatTokens.FONT_SMALL.sp) },
                singleLine = false,
                maxLines = 4,
            )
            Text(
                if (state.streaming) "Stop" else "Send",
                color = if (state.streaming) c.warning else c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .padding(start = ChatTokens.GAP.dp)
                    .clickable {
                        if (state.streaming) session.cancel()
                        else if (input.isNotBlank()) { session.send(input); input = "" }
                    },
            )
        }
    }
    if (showSelect) {
        ProviderModelDialog(
            manager = manager,
            current = selection,
            onApply = { session.select(it); showSelect = false },
            onDismiss = { showSelect = false },
        )
    }
}

/** Minimal provider/model/key picker over the WP1 snapshot (WP7 replaces it). */
@Composable
private fun ProviderModelDialog(
    manager: ChatSessionManager,
    current: PanelSelection,
    onApply: (PanelSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot = remember { manager.reader.snapshot() }
    var model by remember { mutableStateOf(current.modelId) }
    val c = ChatTokens.LocalChatColors.current
    // Providers with stored keys (WP1 read-only migration), custom endpoints last.
    val keyProviders: List<StoredKeySlot> = snapshot.keySlots.filter { it.hasKey }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Provider and model") },
        text = {
            Column {
                keyProviders.forEach { slot ->
                    Text(
                        slot.providerId + (if (slot.poolIndex != null) " (key " + slot.poolIndex + ")" else ""),
                        color = if (slot.slotName == current.keySlot) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = if (slot.providerId == "gemini") current.modelId else model
                                onApply(PanelSelection(
                                    providerId = if (slot.providerId.startsWith("custom")) slot.providerId else slot.providerId,
                                    keySlot = slot.slotName,
                                    modelId = model,
                                    baseUrl = if (slot.providerId == "gemini") "https://generativelanguage.googleapis.com" else PanelSelection.OPENAI_BASE,
                                ))
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                snapshot.endpoints.forEach { ep: StoredEndpoint ->
                    Text(
                        ep.label + " (custom endpoint)",
                        color = if (ep.providerId == current.providerId) c.accent else c.text,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onApply(PanelSelection(
                                    providerId = ep.providerId,
                                    keySlot = "ai_" + ep.providerId,
                                    modelId = model,
                                    baseUrl = ep.baseUrl,
                                ))
                            }
                            .padding(vertical = ChatTokens.GAP.dp),
                    )
                }
                TextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model id") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(current.copy(modelId = model)) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

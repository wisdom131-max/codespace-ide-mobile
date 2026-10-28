package com.codespace.ide.ui.screens

import androidx.compose.runtime.Composable
import com.codespace.ide.data.SecureTokenStore

/**
 * REMOVAL 2/7 kept the panel surface alive; WP2 (d-min, advisor 2026-10-28)
 * replaces the placeholder with the real panel on the rebuilt engine. This is
 * now a thin forwarder so the shell call site and layout stay untouched.
 */
@Composable
fun ChatRebuildPlaceholder(projectId: String, tokenStore: SecureTokenStore) {
    com.codespace.ide.chat.panel.ChatPanelScreen(projectId, tokenStore)
}

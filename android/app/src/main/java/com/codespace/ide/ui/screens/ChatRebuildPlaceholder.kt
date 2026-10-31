package com.codespace.ide.ui.screens

import androidx.compose.runtime.Composable
import com.codespace.ide.data.SecureTokenStore

/**
 * REMOVAL 2/7 kept the panel surface alive; WP2 (d-min, advisor 2026-10-28)
 * replaces the placeholder with the real panel on the rebuilt engine. This is
 * now a thin forwarder so the shell call site and layout stay untouched.
 */
@Composable
fun ChatRebuildPlaceholder(
    projectId: String,
    tokenStore: SecureTokenStore,
    onOpenFile: (String) -> Unit = {},
    /** M2: the header's close control acts on the shell's own panel flag. */
    onClosePanel: (() -> Unit)? = null,
    /** M3: "Move Chat into Editor Area"; the shell opens the chat tab. */
    onMoveToEditor: (() -> Unit)? = null,
    /** Editor AI handoff: a prompt waiting to be sent. */
    pendingPrompt: String? = null,
    onPromptConsumed: () -> Unit = {},
) {
    com.codespace.ide.chat.panel.ChatPanelScreen(
        projectId = projectId,
        tokenStore = tokenStore,
        onOpenFile = onOpenFile,
        onClosePanel = onClosePanel,
        onMoveToEditor = onMoveToEditor,
        pendingPrompt = pendingPrompt,
        onPromptConsumed = onPromptConsumed,
    )
}

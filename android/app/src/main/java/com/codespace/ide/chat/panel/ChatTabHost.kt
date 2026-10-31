package com.codespace.ide.chat.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * M3 — "Move Chat into Editor Area".
 *
 * The chat becomes a TAB in the editor area. The tab is identified by [ChatTabSupport.TAB_ID],
 * a sentinel that cannot collide with a real file path (no scheme is ever opened as a file).
 * The host renders the SAME ChatPanelScreen, so the session, the model selection and the
 * streaming state are the ones the sidebar was showing: moving the chat moves the view, not the
 * conversation.
 */
object ChatTabSupport {
    const val TAB_ID = "chat://panel"
    fun isChatTab(path: String?): Boolean = path == TAB_ID
}

@Composable
fun ChatTabHost(
    projectId: String,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    onOpenFile: (String) -> Unit,
    onReturnToSidebar: () -> Unit,
    /** Editor AI handoff, forwarded so the tab sends what the editor asked for. */
    pendingPrompt: String? = null,
    onPromptConsumed: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        ChatPanelScreen(
            projectId = projectId,
            tokenStore = tokenStore,
            onOpenFile = onOpenFile,
            // Closing the tab returns the chat to the sidebar rather than losing it.
            onClosePanel = onReturnToSidebar,
            pendingPrompt = pendingPrompt,
            onPromptConsumed = onPromptConsumed,
        )
    }
}

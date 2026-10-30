package com.codespace.ide.chat.window

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codespace.ide.chat.panel.ChatPanelScreen
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.data.SecureTokenStore

/**
 * M3 — "Move Chat into New Window", the genuine Android version.
 *
 * Android cannot float a child of the same Activity, so "a new window" is a real SECOND TASK:
 * the manifest declares `documentLaunchMode="always"` so each launch gets its own entry in
 * Recents, and `resizeableActivity="true"` so devices that support freeform or split screen can
 * actually float/resize it. On a phone that means a separate task in Recents, which is what the
 * platform offers; it is NOT a substitute for full screen, and the menu label is unchanged.
 *
 * Safety: both windows go through ChatRuntime, so they share ONE ChatSessionManager, and the
 * manager caches one ChatSession per (projectId, conversationId). Two windows therefore render
 * the same conversation from the same state and there is a single writer per transcript.
 * Closing either window releases only its claim; the manager (and any running stream) survives
 * while the other window is open.
 */
class ChatWindowActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "chat_window_project_id"

        fun intentFor(context: Context, projectId: String): Intent =
            Intent(context, ChatWindowActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId.isNullOrBlank()) {
            finish()
            return
        }
        val store = SecureTokenStore(applicationContext)
        setContent { ChatWindowScreen(projectId, store) }
    }
}

@Composable
private fun ChatWindowScreen(projectId: String, store: SecureTokenStore) {
    val c = ChatTokens.LocalChatColors.current
    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        ChatPanelScreen(
            projectId = projectId,
            tokenStore = store,
            // A staged file belongs to the project's editor, which lives in the main task.
            onOpenFile = { path -> com.codespace.ide.chat.panel.ChatFileOpenRequest.request(path) },
            // Closing this window is closing the task; the sidebar window is unaffected.
            onClosePanel = null,
        )
    }
}

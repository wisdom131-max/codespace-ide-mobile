package com.codespace.ide.chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A(a) dev-only design gallery: every chat component state rendered from the
 * token layer in one screen. Release builds never reach it — the
 * Settings-About entry row is BuildConfig.DEBUG-gated.
 */
class ChatDesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GalleryContent() }
    }
}

@Composable
private fun GalleryContent() {
    val c = ChatTokens.LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        Text(
            "VN Chat — design gallery (dev only)",
            color = c.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            fontWeight = FontWeight.SemiBold,
        )
        NativeChatListRenderer.MessageList(sampleState(), Modifier.padding(top = ChatTokens.GAP.dp))
    }
}

private fun sampleState(): ChatRenderState {
    return ChatRenderState(
        rows = listOf(
            ChatRowModel.User("Fix the failing test in EngineContractTest"),
            ChatRowModel.Thinking(3),
            ChatRowModel.AssistantStream(
                "The fake server was missing newline terminators, so the SSE parser saw one concatenated event.",
                true,
            ),
            ChatRowModel.Attachments(
                listOf(
                    AttachmentChipModel("FakeChatServer.kt", "4.2 KB"),
                    AttachmentChipModel("stack.txt", "0.9 KB"),
                )
            ),
            ChatRowModel.TrimmedHistory(12),
            ChatRowModel.OverBudget("Estimated 9.4k tokens — over the 8k send budget."),
            ChatRowModel.RateLimited(17),
            ChatRowModel.Offline,
            ChatRowModel.NoCredits,
            ChatRowModel.Failed(
                "Network problem",
                "Provider unreachable after the 120s read timeout.",
                true,
            ),
        )
    )
}

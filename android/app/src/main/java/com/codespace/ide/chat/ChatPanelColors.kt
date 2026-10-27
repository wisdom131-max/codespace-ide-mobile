package com.codespace.ide.chat

import androidx.compose.ui.graphics.Color

/**
 * REMOVAL 2/7 repair (2026-10-27): ChatPanelColors lived in CopilotChatPanelOverlay.kt,
 * which was deleted with the panel. Three still-compiling consumers reference it
 * (ChatComposerMobile, ChatRetryExport, AttachmentSheet) until their own removal
 * commits — re-homed here verbatim from the archived copy so every intermediate
 * commit stays green. Dies with the engine/attachments commits.
 */
data class ChatPanelColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val userBubble: Color,
    val assistantBubble: Color,
    val inputBg: Color,
    val divider: Color,
    val headerBg: Color,
    val scrim: Color,
)

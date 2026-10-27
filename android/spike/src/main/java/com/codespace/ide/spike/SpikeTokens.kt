package com.codespace.ide.spike

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design-system tokens for the spike (standalone subset mirroring the app's
 * theme). Rounded corners 8-12dp, panel padding 12dp horizontal / 10dp
 * vertical — the owner's standing UI rule.
 */
object SpikeTokens {
    val bg = Color(0xFFF7F6F3)
    val surface = Color(0xFFFFFFFF)
    val textPrimary = Color(0xFF1A1A1A)
    val textSecondary = Color(0xFF717171)
    val accent = Color(0xFF3B6EA5)
    val bubbleUser = Color(0xFFE3EDF7)
    val bubbleAssistant = Color(0xFFF2F1EC)
    val bubbleError = Color(0xFFFBE3E3)
    val codeBg = Color(0xFF1E1E1E)
    val codeFg = Color(0xFFEDEDED)
    val divider = Color(0xFFE0DFDA)

    val cornerPanel = 12.dp
    val cornerBubble = 10.dp
    val padH = 12.dp
    val padV = 10.dp

    val fontBody = 14.sp
    val fontSmall = 11.sp
    val fontMono = 12.sp
}

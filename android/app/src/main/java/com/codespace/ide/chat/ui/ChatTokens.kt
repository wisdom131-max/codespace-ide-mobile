// GENERATED from android/chat-tokens.json by scripts/generate_chat_tokens.py — DO NOT EDIT BY HAND.
// Regenerate: python3 scripts/generate_chat_tokens.py (from the repo root)
package com.codespace.ide.chat.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Chat surface colors, generated from the single token source (A(a)). */
data class ChatColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val errorBg: Color,
    val warningBg: Color,
    val streamCursor: Color,
)

/** A(a) chat design tokens — android/chat-tokens.json is the single source. */
object ChatTokens {

    val dark = ChatColors(
        bg = Color(0xFF1E1E1E),
        surface = Color(0xFF252526),
        surfaceAlt = Color(0xFF2A2D2E),
        border = Color(0xFF454545),
        text = Color(0xFFD4D4D4),
        textSecondary = Color(0xFF9D9D9D),
        accent = Color(0xFF3794FF),
        success = Color(0xFF4EC9B0),
        warning = Color(0xFFCCA700),
        error = Color(0xFFF14C4C),
        errorBg = Color(0xFF2D1B1E),
        warningBg = Color(0xFF2D2A19),
        streamCursor = Color(0xFFD4D4D4),
        input = Color(0xFF3C3C3C),
    )

    val light = ChatColors(
        bg = Color(0xFFFFFFFF),
        surface = Color(0xFFF5F5F5),
        surfaceAlt = Color(0xFFECECEC),
        border = Color(0xFFD0D0D0),
        text = Color(0xFF1E1E1E),
        textSecondary = Color(0xFF6B6B6B),
        accent = Color(0xFF005FB8),
        success = Color(0xFF0F7B6C),
        warning = Color(0xFF8A6100),
        error = Color(0xFFC42B1C),
        errorBg = Color(0xFFFBE9E7),
        warningBg = Color(0xFFFFF6DC),
        streamCursor = Color(0xFF1E1E1E),
        input = Color(0xFFFFFFFF),
    )

    val LocalChatColors = staticCompositionLocalOf { dark }

    // Radius (dp) — MANDATORY UI RULE: panels/chips 8-12dp rounded.
    const val RADIUS_PANEL = 12
    const val RADIUS_CHIP = 8

    // Padding (dp) — MANDATORY UI RULE: 12dp horizontal, 10dp vertical.
    const val PAD_HORIZONTAL = 12
    const val PAD_VERTICAL = 10

    const val GAP = 8
    const val GAP_TIGHT = 6

    // Typography (sp)
    const val FONT_MICRO = 10
    const val FONT_SMALL = 12
    const val FONT_BODY = 14
    const val FONT_MONO = 13
}

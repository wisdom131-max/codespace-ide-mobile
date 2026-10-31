package com.codespace.ide.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * One small copy glyph under a message (2026-10-31, owner request).
 *
 * Copying a reply used to mean selecting a paragraph by hand in a text field, which on a phone
 * fights the scroll. A single glyph the width of a character is enough, and it sits at the end of
 * the message so it reads as belonging to it without adding a row of chrome.
 *
 * The label is the whole confirmation: it says "Copied" for a moment and then goes back, because a
 * permanent badge would describe a moment that has passed.
 */
@Composable
fun CopyIconRow(
    text: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = true,
) {
    val c = ChatTokens.LocalChatColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1_400); copied = false }
    }
    if (text.isBlank()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = if (copied) "Copied" else "\u29C9",
            color = if (copied) c.accent else c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            modifier = Modifier
                .clickable(onClickLabel = "Copy message") {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

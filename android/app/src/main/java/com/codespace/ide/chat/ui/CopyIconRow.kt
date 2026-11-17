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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
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
/** The owner-approved Copy target: small on purpose. Tests pin it so it cannot drift back to 48dp. */
object CopyTarget {
    const val GLYPH_WIDTH_DP = 14
    const val GLYPH_HEIGHT_DP = 16
    const val PAD_HORIZONTAL_DP = 8
    const val PAD_VERTICAL_DP = 4
    val widthDp: Int get() = GLYPH_WIDTH_DP + PAD_HORIZONTAL_DP * 2
    val heightDp: Int get() = GLYPH_HEIGHT_DP + PAD_VERTICAL_DP * 2
    fun describe(): String = "Copy target " + widthDp + " x " + heightDp + " dp, owner-approved"
}

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
                // OWNER DECISION 2026-11-16: the 48dp expansion this branch added in checkpoint 5 was
                // REVERTED at the owner's explicit request. The Copy target stays the glyph's own
                // measured ~30x24dp (14dp glyph + 8dp horizontal padding, 16dp glyph + 4dp vertical),
                // deliberately. The role and the "Copy message" action label are kept because they cost
                // no pixels, and no parent hitbox or gesture layer may defeat this size again.
                .semantics { role = Role.Button }
                .clickable(onClickLabel = "Copy message") {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

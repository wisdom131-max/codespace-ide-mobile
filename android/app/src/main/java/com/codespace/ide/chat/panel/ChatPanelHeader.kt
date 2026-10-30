package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * M2 — the chat surface header.
 *
 * Reference order, left to right: the surface title, then the session controls, then close.
 * The model picker is NOT here (it lives in the composer toolbar, where the reference puts it)
 * and neither is anything that cannot act: no overflow button yet (M3 adds it with its items),
 * no expand control (M4 wires full screen).
 *
 * Every control is at least 40 dp and the row keeps the mandatory 12/10 padding.
 */
@Composable
internal fun ChatPanelHeaderRow(
    title: String,
    colors: ChatColors,
    onSessions: () -> Unit,
    onMic: () -> Unit,
    onNewSession: () -> Unit,
    onOverflow: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        // Item 6: the row was 10 dp of padding above and below a 40 dp chip, so it stood ~60 dp
        // tall for one line of text. The chips keep their full touch target; only the empty
        // space around them shrinks. The header design itself is unchanged.
        Modifier.fillMaxWidth()
            .padding(
                horizontal = ChatTokens.PAD_HORIZONTAL.dp,
                vertical = ChatTokens.NAV_ROW_PAD_VERTICAL.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier.weight(1f),
        )
        HeaderChip("Sessions", colors, onSessions)
        // M3 voice: the dictation trigger. It sits beside the composer rather than inside the
        // composer's tested 40 dp toolbar row, which is video-faithful and width-sensitive.
        HeaderChip("Mic", colors, onMic)
        HeaderChip("+", colors, onNewSession)
        HeaderChip("⋯", colors, onOverflow)
        if (onClose != null) HeaderChip("✕", colors, onClose)
    }
}

@Composable
private fun HeaderChip(label: String, colors: ChatColors, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = ChatToolbarLayout.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp, maxLines = 1)
    }
}

/** M3: one-line result surface under the header. Tappable to dismiss; never auto-hides silently. */
@Composable
internal fun ChatNoticeLine(text: String, colors: ChatColors, onDismiss: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .background(colors.surfaceAlt)
            .clickable(onClick = onDismiss)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
    ) {
        Text(text, color = colors.text, fontSize = ChatTokens.FONT_MICRO.sp)
    }
}

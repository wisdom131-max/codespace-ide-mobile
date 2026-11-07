package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The thin, non-blocking note above the editor text: why a tab is a read-only preview, or that a
 * limit applies, plus its page controls.
 *
 * Owner rules: a limit that changes behaviour must be stated in the UI (2026-11-06), and every
 * line of a preview must stay reachable (2026-11-07), so the banner carries Previous / Next /
 * Go to line when a preview has more than one page. It is still a banner: 12dp padding, 8dp
 * corners, no dialog, no blocking.
 */
@Composable
internal fun BoxScope.EditorNoticeBanner(
    text: String?,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onGoToLine: (() -> Unit)? = null,
) {
    if (text.isNullOrBlank()) return
    val hasControls = onPrev != null || onNext != null || onGoToLine != null
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(Color(0x33332B1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFE8C07D),
            fontSize = 11.sp,
            textAlign = TextAlign.Start,
        )
        if (hasControls) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                if (onPrev != null) BannerAction("Previous", onPrev)
                if (onNext != null) BannerAction("Next", onNext)
                if (onGoToLine != null) BannerAction("Go to line", onGoToLine)
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF7FB2E5),
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 16.dp, top = 2.dp, bottom = 2.dp),
    )
}

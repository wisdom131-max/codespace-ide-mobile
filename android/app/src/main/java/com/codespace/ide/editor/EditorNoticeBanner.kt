package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
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
 * limit applies. Never a dialog, never a blocker, always dismissible by fixing the cause.
 *
 * Owner rule (2026-11-06): a limit that changes behaviour must be stated in the UI. This is the
 * one place the editor says it, so there is a single place to audit for "did we tell the user".
 */
@Composable
internal fun BoxScope.EditorNoticeBanner(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text = text,
        color = Color(0xFFE8C07D),
        fontSize = 11.sp,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(Color(0x33332B1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

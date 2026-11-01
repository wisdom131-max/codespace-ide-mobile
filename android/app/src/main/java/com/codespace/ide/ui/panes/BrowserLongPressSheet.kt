package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Long press on an image or a link. WebView does no context menu of its own, so before this
 * existed a long press simply did nothing — no way to save an image or copy a link.
 */
@Composable
fun BrowserLongPressSheet() {
    val target = com.codespace.ide.preview.BrowserSession.pendingLongPress ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun dismiss() {
        com.codespace.ide.preview.BrowserSession.pendingLongPress = null
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        confirmButton = {},
        dismissButton = {},
        containerColor = Color(0xFF1B1B1D),
        shape = RoundedCornerShape(12.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetAction("Open") {
                    com.codespace.ide.preview.BrowserSession.webView?.loadUrl(target.url)
                    dismiss()
                }
                if (target.isImage) {
                    SheetAction("Download image") {
                        com.codespace.ide.preview.BrowserSession.startDownload(
                            context, target.url, null, null,
                        )
                        dismiss()
                    }
                } else {
                    SheetAction("Download link") {
                        com.codespace.ide.preview.BrowserSession.startDownload(
                            context, target.url, null, null,
                        )
                        dismiss()
                    }
                }
                target.anchorUrl?.let { href ->
                    if (href != target.url) {
                        SheetAction("Open its link") {
                            com.codespace.ide.preview.BrowserSession.webView?.loadUrl(href)
                            dismiss()
                        }
                    }
                }
                SheetAction("Copy link") {
                    clipboard.setText(AnnotatedString(target.url))
                    dismiss()
                }
                Text(
                    target.url,
                    fontSize = 10.sp,
                    color = Color(0xFF8A8A8A),
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        },
    )
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        color = Color(0xFFE8E8E8),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF26262A), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

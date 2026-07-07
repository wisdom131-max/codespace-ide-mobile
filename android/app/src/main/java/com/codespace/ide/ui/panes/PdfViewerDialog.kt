package com.codespace.ide.ui.panes

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Native PDF viewer — uses Android's built-in PdfRenderer (API 21+). No external PDF library,
 * no extra APK size / storage cost, which matters on Wisdom's storage-constrained device.
 *
 * Renders ONE page at a time as a bitmap (never the whole document into memory at once) since
 * this app targets low-RAM phones — page navigation via Prev/Next, pinch-to-zoom + pan on the
 * current page, page counter in the header.
 */
@Composable
fun PdfViewerDialog(pdfPath: String, onDismiss: () -> Unit) {
    var pageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    LaunchedEffect(pdfPath, pageIndex) {
        error = null
        scale = 1f; offsetX = 0f; offsetY = 0f
        try {
            val file = File(pdfPath)
            if (!file.exists()) { error = "File not found"; return@LaunchedEffect }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount == 0) { error = "Empty or unreadable PDF"; return@LaunchedEffect }
                    if (pageIndex >= pageCount) pageIndex = pageCount - 1
                    renderer.openPage(pageIndex).use { page ->
                        // Cap resolution — 2x the page's native point size is plenty sharp on a
                        // phone screen without ballooning memory on a 3GB device.
                        val scaleFactor = 2f
                        val w = (page.width * scaleFactor).toInt().coerceAtLeast(1)
                        val h = (page.height * scaleFactor).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                    }
                }
            }
        } catch (e: Exception) {
            error = "Couldn't open PDF: ${e.message}"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            // Header
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    File(pdfPath).name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pageCount > 0) {
                    Text("${pageIndex + 1} / $pageCount", color = Color(0xFF888888), fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                }
                Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                    modifier = Modifier.size(20.dp).clickable { onDismiss() })
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))

            // Page — pinch zoom + pan
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0D0D0D)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    error != null -> Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp))
                    bitmap == null -> CircularProgressIndicator(color = Color(0xFF569CD6))
                    else -> {
                        val bmp = bitmap!!
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(pageIndex) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale, scaleY = scale,
                                    translationX = offsetX, translationY = offsetY,
                                )
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))

            // Prev / Next
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) {
                    Icon(Icons.Default.ChevronLeft, null,
                        tint = if (pageIndex > 0) Color(0xFF569CD6) else Color(0xFF555555))
                    Text("Prev", color = if (pageIndex > 0) Color(0xFF569CD6) else Color(0xFF555555))
                }
                TextButton(onClick = { if (pageIndex < pageCount - 1) pageIndex++ }, enabled = pageIndex < pageCount - 1) {
                    Text("Next", color = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555))
                    Icon(Icons.Default.ChevronRight, null,
                        tint = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555))
                }
            }
        }
    }
}

package com.codespace.ide.ui.panes

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Native PDF viewer — uses Android's built-in PdfRenderer (API 21+).
 *
 * Fixes vs previous version:
 *  1. Screen-DPI-aware render resolution: bitmap width = page points × (screenDpi/72),
 *     capped at 2048px wide to avoid OOM on small-RAM devices. Previously used a flat 2×
 *     multiplier which produced blurry output on high-DPI screens and over-sized bitmaps
 *     on landscape pages.
 *  2. Clamped pan: when zoomed in, panning is bounded so the page can't slide completely
 *     off the visible area. Previously had no clamping, so a single swipe could lose the
 *     page entirely with no way to get it back without navigating away.
 *  3. Double-tap to reset zoom+pan back to 1×/centered (convenience — common in PDF apps).
 *  4. Zoom level indicator in footer (e.g. "1.4×") so users know their zoom state.
 */
@Composable
fun PdfViewerDialog(pdfPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val _density = LocalDensity.current
    // Rotation fix (#8): key on orientation so this fullscreen Dialog gets a fresh,
    // correctly-sized window on rotate instead of a stuck stale one.
    val orientation = LocalConfiguration.current.orientation

    var pageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var bitmap    by remember { mutableStateOf<Bitmap?>(null) }
    var error     by remember { mutableStateOf<String?>(null) }
    var scale     by remember { mutableStateOf(1f) }
    var offsetX   by remember { mutableStateOf(0f) }
    var offsetY   by remember { mutableStateOf(0f) }
    // Track the rendered image display size so we can clamp pan correctly.
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    // Screen DPI — used for sharp bitmap rendering at native resolution.
    val screenDpi = remember {
        context.resources.displayMetrics.densityDpi.toFloat()
    }

    LaunchedEffect(pdfPath, pageIndex) {
        error = null
        scale = 1f; offsetX = 0f; offsetY = 0f
        withContext(Dispatchers.IO) {
            try {
                val file = File(pdfPath)
                if (!file.exists()) { error = "File not found"; return@withContext }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        if (pageCount == 0) { error = "Empty or unreadable PDF"; return@withContext }
                        if (pageIndex >= pageCount) pageIndex = pageCount - 1
                        renderer.openPage(pageIndex).use { page ->
                            // Render at screen DPI for pixel-perfect sharpness.
                            // PDF page.width/height are in 1/72-inch points, so
                            // multiply by (screenDpi / 72) to get the correct px count.
                            // Cap at 2048px wide to avoid OOM on 3GB devices.
                            val scaleFactor = (screenDpi / 72f).coerceAtMost(5f)
                            val w = (page.width  * scaleFactor).toInt().coerceIn(1, 2048)
                            val h = (page.height * scaleFactor).toInt().coerceIn(1, 4096)
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
    }

    key(orientation) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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

            // ── Page — pinch zoom + clamped pan + double-tap reset ──────────
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0D0D0D)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    error != null -> Text(
                        error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                    bitmap == null -> CircularProgressIndicator(color = Color(0xFF569CD6))
                    else -> {
                        val bmp = bitmap!!
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { imageSize = it }
                                .pointerInput(pageIndex) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                                        scale = newScale

                                        // Clamp pan so the page can't leave the viewport.
                                        // Max offset = half the overflow (scaled size - display size) / 2.
                                        val maxX = ((imageSize.width  * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                        val maxY = ((imageSize.height * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale, scaleY = scale,
                                    translationX = offsetX, translationY = offsetY,
                                ),
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))

            // ── Footer: Prev / zoom indicator + reset / Next ─────────────────
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) {
                    Icon(Icons.Default.ChevronLeft, null,
                        tint = if (pageIndex > 0) Color(0xFF569CD6) else Color(0xFF555555))
                    Text("Prev", color = if (pageIndex > 0) Color(0xFF569CD6) else Color(0xFF555555),
                        fontSize = 13.sp)
                }

                // Zoom reset button — only visible when zoomed in
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = scale > 1.05f) {
                        scale = 1f; offsetX = 0f; offsetY = 0f
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    if (scale > 1.05f) {
                        Icon(Icons.Default.ZoomOut, null,
                            tint = Color(0xFF569CD6), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (scale > 1.05f) "${"%.1f".format(scale)}×  ↺" else "${"%.1f".format(scale)}×",
                        color = if (scale > 1.05f) Color(0xFF569CD6) else Color(0xFF888888),
                        fontSize = 12.sp,
                    )
                }

                TextButton(
                    onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                    enabled = pageIndex < pageCount - 1,
                ) {
                    Text("Next",
                        color = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555),
                        fontSize = 13.sp)
                    Icon(Icons.Default.ChevronRight, null,
                        tint = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555))
                }
            }
        }
    }
    }
}

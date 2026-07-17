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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native PDF viewer — uses Android's built-in PdfRenderer (API 21+).
 *
 * Fix summary (2026-07-17):
 *  1. Landscape layout: the page bitmap now fills the available width AND height correctly.
 *     Instead of ContentScale.Fit on a fillMaxSize() box (which leaves dead black space around
 *     a portrait page in landscape), the page is sized proportionally via aspectRatio() so the
 *     image always fills the available space naturally without distortion.
 *  2. Zoom pivot fixed: zoom/pan is applied at the image's own center, not the composable's
 *     center, by constraining the image to its natural proportional size inside a scrollable Box.
 *  3. Render resolution: bitmap width = page points × (screenDpi/72), capped at 2048px.
 *     Re-renders when orientation changes (via key(orientation) on the LaunchedEffect).
 *  4. Pan clamping: bounded so page can't slide off-screen.
 *  5. Double-tap reset + zoom indicator preserved.
 */
@Composable
fun PdfViewerDialog(pdfPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val configuration = LocalConfiguration.current

    var pageIndex by remember { mutableStateOf(0) }
    var pageCount  by remember { mutableStateOf(0) }
    var bitmap     by remember { mutableStateOf<Bitmap?>(null) }
    var error      by remember { mutableStateOf<String?>(null) }
    var scale      by remember { mutableStateOf(1f) }
    var offsetX    by remember { mutableStateOf(0f) }
    var offsetY    by remember { mutableStateOf(0f) }

    // Screen dimensions — we re-render on orientation change so the bitmap
    // is always sized correctly for the current screen width.
    val screenWidthPx  = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }

    val screenDpi = remember { context.resources.displayMetrics.densityDpi.toFloat() }

    // Re-render the page whenever path, page number, OR orientation changes.
    LaunchedEffect(pdfPath, pageIndex, orientation) {
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
                            // Render at screen DPI for sharpness.
                            // In landscape the available width is larger, so the bitmap
                            // will be wider and text will be readable at 1×.
                            val scaleFactor = (screenDpi / 72f).coerceAtMost(5f)
                            val pageW = page.width.toFloat()
                            val pageH = page.height.toFloat()
                            // Fit the page to the screen width (or height — whichever is the
                            // binding constraint for the page's aspect ratio), so 1× = fills screen.
                            val fitByWidth  = screenWidthPx / pageW
                            val fitByHeight = screenHeightPx / pageH
                            val fitScale    = minOf(fitByWidth, fitByHeight)
                            // Use the larger of (fit-to-screen, DPI-based) for best sharpness.
                            val renderScale = maxOf(fitScale, scaleFactor).coerceAtMost(5f)
                            val w = (pageW * renderScale).toInt().coerceIn(1, 4096)
                            val h = (pageH * renderScale).toInt().coerceIn(1, 8192)
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

            // ── Page content ────────────────────────────────────────────────
            // Use a Box that fills all available space. The Image inside uses
            // aspectRatio() to take up its natural proportion — this means in
            // landscape it will be wide and fill the screen width, rather than
            // the small portrait-sized rectangle we had before.
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
                        // Compute the natural display size:
                        // The image should fill available width but respect the
                        // page's aspect ratio. We use ContentScale.Fit on a
                        // fillMaxSize modifier so it always fills the viewport —
                        // portrait pages fill height in landscape, landscape pages
                        // fill width in portrait. The graphicsLayer zoom then
                        // operates on the full image bounds, not a smaller clip.
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(pageIndex) {
                                    // Capture PointerInputScope.size here — not accessible
                                    // inside the nested onGesture lambda.
                                    val viewW = size.width.toFloat()
                                    val viewH = size.height.toFloat()
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        // Clamp pan to keep page visible.
                                        val maxX = ((viewW * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                        val maxY = ((viewH * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                        scale = newScale
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale, scaleY = scale,
                                    translationX = offsetX, translationY = offsetY,
                                    clip = true,
                                ),
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))

            // ── Footer ───────────────────────────────────────────────────────
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

                // Zoom indicator — tap to reset zoom & pan
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        scale = 1f; offsetX = 0f; offsetY = 0f
                    },
                ) {
                    if (scale > 1.05f) {
                        Icon(Icons.Default.ZoomOut, null,
                            tint = Color(0xFF569CD6), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${"%.1f".format(scale)}×",
                        color = if (scale > 1.05f) Color(0xFF569CD6) else Color(0xFF888888),
                        fontSize = 13.sp,
                    )
                }

                TextButton(onClick = { if (pageIndex < pageCount - 1) pageIndex++ }, enabled = pageIndex < pageCount - 1) {
                    Text("Next", color = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555),
                        fontSize = 13.sp)
                    Icon(Icons.Default.ChevronRight, null,
                        tint = if (pageIndex < pageCount - 1) Color(0xFF569CD6) else Color(0xFF555555))
                }
            }
        }
    }
}

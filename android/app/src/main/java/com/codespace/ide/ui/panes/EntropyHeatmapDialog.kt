package com.codespace.ide.ui.panes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min

// ── Entropy Heatmap Viewer ────────────────────────────────────────────────────
// Computes Shannon entropy per 256-byte block and renders a color-coded heatmap.
// Low entropy (0-3): blue (structured/zeros). Medium (3-6): green (code/text).
// High (7-8): red (compressed/encrypted). Legend + stats included.

private const val BLOCK_SIZE = 256
private const val BLOCKS_PER_ROW = 64

private data class EntropyBlock(val offset: Long, val entropy: Float)
private data class EntropyStats(
    val min: Float, val max: Float, val avg: Float,
    val highCount: Int, val medCount: Int, val lowCount: Int,
    val totalBlocks: Int
)

private fun shannonEntropy(data: ByteArray, start: Int, len: Int): Float {
    if (len == 0) return 0f
    val freq = IntArray(256)
    val end = min(start + len, data.size)
    for (i in start until end) freq[data[i].toInt() and 0xFF]++
    val n = (end - start).toFloat()
    var h = 0.0
    for (c in freq) {
        if (c > 0) {
            val p = c / n
            h -= p * log2(p)
        }
    }
    return h.toFloat()
}

private fun entropyColor(e: Float): Color {
    return when {
        e < 1f -> Color(0xFF1A237E)   // near-zero: dark blue
        e < 2f -> Color(0xFF283593)
        e < 3f -> Color(0xFF1565C0)   // low: blue
        e < 4f -> Color(0xFF2E7D32)   // medium-low: dark green
        e < 5f -> Color(0xFF388E3C)   // medium: green
        e < 6f -> Color(0xFFF9A825)   // medium-high: amber
        e < 7f -> Color(0xFFE65100)   // high: deep orange
        else   -> Color(0xFFB71C1C)   // very high: red (encrypted/compressed)
    }
}

@Composable
fun EntropyHeatmapDialog(file: java.io.File, onDismiss: () -> Unit) {
    val bg = Color(0xFF1E1E1E); val surface = Color(0xFF252526)
    val border = Color(0xFF3C3C3C); val text = Color(0xFFD4D4D4)
    val muted = Color(0xFF858585)

    var blocks by remember { mutableStateOf<List<EntropyBlock>>(emptyList()) }
    var stats  by remember { mutableStateOf<EntropyStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf<String?>(null) }
    var selectedBlock by remember { mutableStateOf<EntropyBlock?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val result = mutableListOf<EntropyBlock>()
                var offset = 0L
                var pos = 0
                while (pos < bytes.size) {
                    val len = min(BLOCK_SIZE, bytes.size - pos)
                    val e = shannonEntropy(bytes, pos, len)
                    result += EntropyBlock(offset, e)
                    offset += len
                    pos += len
                }
                val entropies = result.map { it.entropy }
                val high = result.count { it.entropy >= 7f }
                val med  = result.count { it.entropy in 4f..7f }
                val low  = result.count { it.entropy < 4f }
                val s = EntropyStats(
                    min = entropies.minOrNull() ?: 0f,
                    max = entropies.maxOrNull() ?: 0f,
                    avg = if (entropies.isEmpty()) 0f else entropies.average().toFloat(),
                    highCount = high, medCount = med, lowCount = low,
                    totalBlocks = result.size
                )
                blocks = result
                stats = s
            } catch (ex: Exception) {
                error = ex.message ?: "Failed to read file"
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = bg, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                // ── Title bar
                Row(
                    Modifier.fillMaxWidth().background(surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Entropy Heatmap", color = text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("· ${file.name}", color = muted, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = muted, modifier = Modifier.size(16.dp))
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF569CD6))
                            Spacer(Modifier.height(12.dp))
                            Text("Computing entropy…", color = muted, fontSize = 13.sp)
                        }
                    }
                } else if (error != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Error: $error", color = Color(0xFFF44747), fontSize = 13.sp)
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // ── Stats row
                        stats?.let { s ->
                            Row(
                                Modifier.fillMaxWidth().background(surface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                StatChip("Blocks", "${s.totalBlocks}", muted, text)
                                StatChip("Avg", "%.2f".format(s.avg), muted, text)
                                StatChip("Min", "%.2f".format(s.min), muted, Color(0xFF4FC3F7))
                                StatChip("Max", "%.2f".format(s.max), muted, Color(0xFFEF5350))
                                StatChip("High (≥7)", "${s.highCount}", muted, Color(0xFFEF5350))
                                StatChip("Med (4-7)", "${s.medCount}", muted, Color(0xFFF9A825))
                                StatChip("Low (<4)", "${s.lowCount}", muted, Color(0xFF66BB6A))
                            }
                            Divider(color = border)
                        }

                        // ── Heatmap canvas
                        val rowCount = (blocks.size + BLOCKS_PER_ROW - 1) / BLOCKS_PER_ROW
                        val cellPx  = 10f
                        val mapHeightDp = (rowCount * cellPx + 4).dp
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                .height(mapHeightDp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                blocks.forEachIndexed { idx, block ->
                                    val col = idx % BLOCKS_PER_ROW
                                    val row = idx / BLOCKS_PER_ROW
                                    val x = col * cellPx
                                    val y = row * cellPx
                                    drawRect(
                                        color = entropyColor(block.entropy),
                                        topLeft = Offset(x, y),
                                        size = Size(cellPx - 1f, cellPx - 1f)
                                    )
                                }
                            }
                        }

                        Divider(color = border)

                        // ── Legend
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Legend:", color = muted, fontSize = 11.sp)
                            LegendEntry(Color(0xFF1565C0), "0–3 Structured")
                            LegendEntry(Color(0xFF388E3C), "3–5 Code/Text")
                            LegendEntry(Color(0xFFF9A825), "5–6 Mixed")
                            LegendEntry(Color(0xFFE65100), "6–7 High")
                            LegendEntry(Color(0xFFB71C1C), "7–8 Encrypted/Compressed")
                        }

                        Divider(color = border)

                        // ── Block table (first 200)
                        Text("Block Details (256-byte blocks):",
                            color = muted, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            // header
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                Text("Offset", color = muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(100.dp))
                                Text("Entropy", color = muted, fontSize = 11.sp, modifier = Modifier.width(70.dp))
                                Text("Level", color = muted, fontSize = 11.sp)
                            }
                            val displayBlocks = if (blocks.size > 200) blocks.take(200) else blocks
                            displayBlocks.forEach { block ->
                                val lvl = when {
                                    block.entropy < 3f -> "Structured"
                                    block.entropy < 5f -> "Code/Text"
                                    block.entropy < 7f -> "Mixed"
                                    else -> "Encrypted/Compressed"
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text("0x%08X".format(block.offset),
                                        color = text, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(100.dp))
                                    Text("%.4f".format(block.entropy),
                                        color = entropyColor(block.entropy), fontSize = 11.sp,
                                        modifier = Modifier.width(70.dp))
                                    Text(lvl, color = muted, fontSize = 11.sp)
                                }
                            }
                            if (blocks.size > 200) {
                                Text("… and ${blocks.size - 200} more blocks",
                                    color = muted, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, labelColor: Color, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = labelColor, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color(0xFF858585), fontSize = 10.sp)
    }
}

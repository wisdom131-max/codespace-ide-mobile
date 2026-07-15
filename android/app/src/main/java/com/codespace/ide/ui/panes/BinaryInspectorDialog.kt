package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BinaryInspectorDialog — structured binary file analysis.
 * Shows header bytes, entropy analysis, section detection.
 * Phase 21 Step 4 — fallback viewer for unknown binary files.
 */

data class BinarySection(
    val name: String,
    val offset: Long,
    val size: Long,
    val type: String
)

data class EntropyResult(
    val overall: Double,
    val first1K: Double,
    val last1K: Double,
    val blocks: List<Pair<Long, Double>>
)

@Composable
fun BinaryInspectorDialog(file: File, onDismiss: () -> Unit) {
    var sections by remember { mutableStateOf<List<BinarySection>>(emptyList()) }
    var entropy by remember { mutableStateOf<EntropyResult?>(null) }
    var headerHex by remember { mutableStateOf("") }
    var headerAscii by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var fileSize by remember { mutableStateOf(0L) }

    LaunchedEffect(file) {
        loading = true
        fileSize = file.length()
        val (se, en, hx, as) = withContext(Dispatchers.IO) {
            val s = detectSections(file)
            val e = calcEntropy(file)
            val header = readHeader(file, 256)
            val hexStr = header.joinToString(" ") { "%02X".format(it) }
            val asciiStr = header.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
            Triple(s, e, hexStr) to asciiStr
        }
        sections = se
        entropy = en
        headerHex = hx
        headerAscii = as
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = Color(0xFF1E1E1E)) {
            Column(Modifier.width(360.dp).heightIn(max = 550.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Binary Inspector", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, null, tint = Color(0xFF858585), modifier = Modifier.size(18.dp).clickable { onDismiss() })
                }
                Text(file.name, color = Color(0xFF858585), fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0xFF333333))

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF569CD6), modifier = Modifier.size(20.dp))
                    }
                } else {
                    // File size
                    SectionTitle("File Size")
                    Text(FileDetector.formatFileSize(fileSize), color = Color(0xFFCCCCCC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                    // Header hex dump
                    SectionTitle("Header (first 256 bytes)")
                    Text(headerHex, color = Color(0xFF569CD6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                    Spacer(Modifier.height(2.dp))
                    Text(headerAscii, color = Color(0xFF4EC9B0), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))

                    // Entropy analysis
                    SectionTitle("Entropy Analysis")
                    entropy?.let { e ->
                        InfoLine("Overall", "%.4f".format(e.overall))
                        InfoLine("First 1KB", "%.4f".format(e.first1K))
                        InfoLine("Last 1KB", "%.4f".format(e.last1K))
                        if (e.overall > 0.95) {
                            Text("⚠ High entropy — likely compressed/encrypted", color = Color(0xFFFF6B6B), fontSize = 10.sp)
                        } else if (e.overall < 0.3) {
                            Text("Low entropy — likely text or structured data", color = Color(0xFF4EC9B0), fontSize = 10.sp)
                        }
                    }

                    // Detected sections
                    if (sections.isNotEmpty()) {
                        SectionTitle("Detected Sections (${sections.size})")
                        sections.forEach { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(s.name, color = Color(0xFFDCDCAA), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text("0x${s.offset.toString(16)}", color = Color(0xFF858585), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
                                Text(FileDetector.formatFileSize(s.size), color = Color(0xFF858585), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close", color = Color(0xFF569CD6))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, color = Color(0xFF569CD6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF858585), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
        Text(value, color = Color(0xFFCCCCCC), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── Binary analysis helpers ────────────────────────────────────────────

private fun readHeader(file: File, count: Int): ByteArray {
    return try {
        val raf = RandomAccessFile(file, "r")
        val buf = ByteArray(minOf(count, raf.length().toInt().coerceAtLeast(0)))
        raf.readFully(buf)
        raf.close()
        buf
    } catch (e: Exception) { ByteArray(0) }
}

private fun calcEntropy(file: File): EntropyResult {
    return try {
        val raf = RandomAccessFile(file, "r")
        val total = raf.length()
        val buf = ByteArray(4096)
        val blocks = mutableListOf<Pair<Long, Double>>()

        // Overall histogram
        val hist = IntArray(256)
        var totalBytes = 0

        // First 1KB histogram
        val firstHist = IntArray(256)
        raf.seek(0)
        var firstRead = raf.read(ByteArray(minOf(1024, total.toInt())))
        // Reset and read full file
        raf.seek(0)
        var blockIdx = 0
        while (true) {
            val read = raf.read(buf)
            if (read <= 0) break
            for (i in 0 until read) {
                hist[buf[i].toInt() and 0xFF]++
                if (totalBytes < firstRead) firstHist[buf[i].toInt() and 0xFF]++
            }
            totalBytes += read
            if (read == buf.size) {
                // Calculate block entropy
                val blockHist = IntArray(256)
                for (i in 0 until buf.size) blockHist[buf[i].toInt() and 0xFF]++
                val e = shannon(blockHist, buf.size)
                blocks.add((blockIdx * 4096L) to e)
                blockIdx++
            }
        }

        // Last 1KB histogram
        val lastHist = IntArray(256)
        raf.seek(maxOf(0, total - 1024))
        val lastBuf = ByteArray(minOf(1024, (total - maxOf(0, total - 1024)).toInt()))
        raf.read(lastBuf)
        for (b in lastBuf) lastHist[b.toInt() and 0xFF]++

        raf.close()

        EntropyResult(
            overall = shannon(hist, totalBytes),
            first1K = shannon(firstHist, firstRead.coerceAtLeast(1)),
            last1K = shannon(lastHist, lastBuf.size.coerceAtLeast(1)),
            blocks = blocks.takeLast(20)
        )
    } catch (e: Exception) {
        EntropyResult(0.0, 0.0, 0.0, emptyList())
    }
}

private fun shannon(hist: IntArray, total: Int): Double {
    if (total == 0) return 0.0
    var entropy = 0.0
    for (count in hist) {
        if (count > 0) {
            val p = count.toDouble() / total
            entropy -= p * (Math.log(p) / Math.log(2.0))
        }
    }
    return entropy
}

private fun detectSections(file: File): List<BinarySection> {
    val sections = mutableListOf<BinarySection>()
    val info = FileDetector.detect(file)

    // ELF sections
    if (info.isElf) {
        sections.add(BinarySection("ELF Header", 0, 64, "ELF Header"))
    }

    // APK/ZIP sections
    if (info.isApk || info.detectedFormat == FileFormat.ZIP || info.detectedFormat == FileFormat.JAR || info.detectedFormat == FileFormat.AAR) {
        sections.add(BinarySection("ZIP Local Files", 0, 0, "Archive"))
        sections.add(BinarySection("ZIP Central Dir", 0, 0, "Archive"))
    }

    // DEX sections
    if (info.detectedFormat == FileFormat.DEX) {
        sections.add(BinarySection("DEX Header", 0, 112, "DEX Header"))
        sections.add(BinarySection("String IDs", 0, 0, "DEX"))
        sections.add(BinarySection("Type IDs", 0, 0, "DEX"))
        sections.add(BinarySection("Proto IDs", 0, 0, "DEX"))
        sections.add(BinarySection("Class Defs", 0, 0, "DEX"))
    }

    // SQLite sections
    if (info.detectedFormat == FileFormat.SQLITE) {
        sections.add(BinarySection("SQLite Header", 0, 100, "Database Header"))
        sections.add(BinarySection("Page 1", 100, 4096, "Schema Page"))
    }

    return sections
}

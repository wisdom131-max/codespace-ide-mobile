package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// ── Binary Diff Viewer — P21-X-10 ────────────────────────────────────────────
// Side-by-side hex diff of two arbitrary files.
// Highlights added/removed/changed bytes with colour coding.
// Rows: 16 bytes per row. Columns: offset | hex A | hex B | ASCII A | ASCII B
// Diff stats panel: total bytes, changed, added, removed, match %.

private const val DIFF_BYTES_PER_ROW = 16
private const val DIFF_MAX_ROWS = 4096          // 65 536 bytes — enough for most binaries
private const val DIFF_LARGE_FILE = 10_485_760  // 10 MB

private enum class DiffKind { SAME, CHANGED, ADDED, REMOVED }

private data class DiffRow(
    val offset: Int,
    val bytesA: ByteArray?,    // null = file A shorter than B here
    val bytesB: ByteArray?,    // null = file B shorter than A here
    val kinds: List<DiffKind>, // per-byte classification, length = max(bytesA,bytesB)
    val hasDiff: Boolean,
)

private data class DiffResult(
    val rows: List<DiffRow>,
    val totalBytesA: Long,
    val totalBytesB: Long,
    val changedBytes: Int,
    val addedBytes: Int,
    val removedBytes: Int,
    val matchPct: Float,
    val truncated: Boolean,
)

// ── Diff engine ───────────────────────────────────────────────────────────────
private fun computeDiff(fileA: java.io.File, fileB: java.io.File): DiffResult {
    val sizeA = fileA.length()
    val sizeB = fileB.length()
    val maxRead = (DIFF_MAX_ROWS * DIFF_BYTES_PER_ROW).toLong()
    val truncated = sizeA > maxRead || sizeB > maxRead

    val bytesA = fileA.inputStream().use { it.readNBytes(minOf(sizeA, maxRead).toInt()) }
    val bytesB = fileB.inputStream().use { it.readNBytes(minOf(sizeB, maxRead).toInt()) }

    val maxLen = maxOf(bytesA.size, bytesB.size)
    val rows = mutableListOf<DiffRow>()

    var changed = 0; var added = 0; var removed = 0

    var off = 0
    while (off < maxLen) {
        val rowEnd = minOf(off + DIFF_BYTES_PER_ROW, maxLen)
        val sliceA = if (off < bytesA.size) bytesA.copyOfRange(off, minOf(rowEnd, bytesA.size)) else null
        val sliceB = if (off < bytesB.size) bytesB.copyOfRange(off, minOf(rowEnd, bytesB.size)) else null
        val rowLen = rowEnd - off

        val kinds = mutableListOf<DiffKind>()
        var hasDiff = false

        for (i in 0 until rowLen) {
            val hasA = sliceA != null && i < sliceA.size
            val hasB = sliceB != null && i < sliceB.size
            val kind = when {
                hasA && hasB -> if (sliceA!![i] == sliceB!![i]) DiffKind.SAME else { changed++; hasDiff = true; DiffKind.CHANGED }
                hasA -> { removed++; hasDiff = true; DiffKind.REMOVED }
                else -> { added++; hasDiff = true; DiffKind.ADDED }
            }
            kinds += kind
        }

        rows += DiffRow(off, sliceA, sliceB, kinds, hasDiff)
        off = rowEnd
    }

    val sameBytes = maxLen - changed - added - removed
    val matchPct = if (maxLen == 0) 100f else sameBytes.toFloat() / maxLen.toFloat() * 100f

    return DiffResult(
        rows = rows,
        totalBytesA = sizeA,
        totalBytesB = sizeB,
        changedBytes = changed,
        addedBytes = added,
        removedBytes = removed,
        matchPct = matchPct,
        truncated = truncated,
    )
}

private fun Byte.toHex(): String = "%02X".format(this.toInt() and 0xFF)
private fun Byte.toPrintable(): Char {
    val c = this.toInt() and 0xFF
    return if (c in 32..126) c.toChar() else '.'
}
private fun formatFileSz(b: Long): String = when {
    b >= 1_048_576L -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1_024L     -> "%.1f KB".format(b / 1_024.0)
    else            -> "$b B"
}

// ── Colours ───────────────────────────────────────────────────────────────────
private val DiffBg       = Color(0xFF1E1E1E)
private val DiffSurface  = Color(0xFF252526)
private val DiffBorder   = Color(0xFF3C3C3C)
private val DiffText     = Color(0xFFD4D4D4)
private val DiffMuted    = Color(0xFF858585)
private val DiffAccent   = Color(0xFF569CD6)
private val DiffChanged  = Color(0xFFE6DB74)
private val DiffAdded    = Color(0xFF4EC994)
private val DiffRemoved  = Color(0xFFF44747)
private val DiffChangedBg = Color(0xFF3A3000)
private val DiffAddedBg   = Color(0xFF0A2E1E)
private val DiffRemovedBg = Color(0xFF2E0A0A)

// ── Composable ────────────────────────────────────────────────────────────────
@Composable
fun BinaryDiffViewerDialog(
    fileA: java.io.File? = null,
    fileB: java.io.File? = null,
    onDismiss: () -> Unit,
) {
    var pickedA by remember { mutableStateOf(fileA) }
    var pickedB by remember { mutableStateOf(fileB) }
    var result  by remember { mutableStateOf<DiffResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errMsg  by remember { mutableStateOf<String?>(null) }
    var tab     by remember { mutableStateOf(0) }   // 0=All 1=Diffs only 2=Stats
    var showDiffsOnly by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-run diff when both files are set
    LaunchedEffect(pickedA, pickedB) {
        val a = pickedA; val b = pickedB
        if (a != null && b != null) {
            loading = true; errMsg = null; result = null
            withContext(Dispatchers.IO) {
                try {
                    if (a.length() > DIFF_LARGE_FILE || b.length() > DIFF_LARGE_FILE) {
                        errMsg = "File exceeds 10 MB limit — diff is capped at first ${DIFF_MAX_ROWS * DIFF_BYTES_PER_ROW / 1024}KB"
                    }
                    result = computeDiff(a, b)
                } catch (ex: Exception) {
                    errMsg = "Diff failed: ${ex.message}"
                }
            }
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = DiffBg, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {

                // ── Title bar ──────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().background(DiffSurface)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CompareArrows, null, tint = DiffAccent,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Binary Diff", color = DiffText, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, null, tint = DiffMuted, modifier = Modifier.size(16.dp))
                    }
                }

                // ── File pickers ───────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().background(DiffSurface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiffFilePill("A", pickedA?.name, Modifier.weight(1f))
                    Text("vs", color = DiffMuted, fontSize = 11.sp)
                    DiffFilePill("B", pickedB?.name, Modifier.weight(1f))
                }
                HorizontalDivider(color = DiffBorder)

                when {
                    pickedA == null || pickedB == null -> {
                        // Empty state — files passed from ExplorerPane context menu
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)) {
                                Icon(Icons.Default.CompareArrows, null, tint = DiffMuted,
                                    modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("Open via Explorer → long-press a file →\n\"Diff: Set File A\" then \"Diff: Set File B\"",
                                    color = DiffMuted, fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = DiffAccent)
                            Spacer(Modifier.height(10.dp))
                            Text("Computing diff…", color = DiffMuted, fontSize = 12.sp)
                        }
                    }
                    else -> {
                        val res = result
                        // Tab bar
                        val tabs = listOf("All bytes", "Diffs only", "Stats")
                        TabRow(selectedTabIndex = tab, containerColor = DiffSurface,
                            contentColor = DiffText) {
                            tabs.forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i; showDiffsOnly = i == 1 },
                                    text = { Text(t, fontSize = 12.sp) })
                            }
                        }

                        errMsg?.let { msg ->
                            Row(Modifier.fillMaxWidth().background(Color(0xFF3A2A00))
                                .padding(8.dp)) {
                                Text(msg, color = DiffChanged, fontSize = 11.sp)
                            }
                        }

                        when (tab) {
                            // ── Hex diff view ─────────────────────────────────
                            0, 1 -> {
                                if (res == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No result", color = DiffMuted, fontSize = 12.sp)
                                    }
                                } else {
                                    // Column headers
                                    Row(
                                        Modifier.fillMaxWidth().background(DiffSurface)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("Offset", color = DiffMuted, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.width(60.dp))
                                        Text("◀ File A (hex)", color = DiffMuted, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f))
                                        Text("File B (hex) ▶", color = DiffMuted, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f))
                                        Text("A·B ascii", color = DiffMuted, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.width(80.dp))
                                    }
                                    HorizontalDivider(color = DiffBorder)

                                    val visibleRows = if (showDiffsOnly)
                                        res.rows.filter { it.hasDiff } else res.rows

                                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                        if (res.truncated) {
                                            item {
                                                Text(
                                                    "⚠ Showing first ${DIFF_MAX_ROWS * DIFF_BYTES_PER_ROW / 1024}KB only",
                                                    color = DiffChanged, fontSize = 10.sp,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }
                                        itemsIndexed(visibleRows) { _, row ->
                                            DiffRowView(row)
                                        }
                                    }
                                }
                            }

                            // ── Stats ─────────────────────────────────────────
                            2 -> {
                                if (res == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No result", color = DiffMuted, fontSize = 12.sp)
                                    }
                                } else {
                                    Column(
                                        Modifier.fillMaxSize()
                                            .padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        DiffStatRow("File A", pickedA?.absolutePath ?: "", DiffAccent)
                                        DiffStatRow("File A size", formatFileSz(res.totalBytesA), DiffText)
                                        DiffStatRow("File B", pickedB?.absolutePath ?: "", DiffAccent)
                                        DiffStatRow("File B size", formatFileSz(res.totalBytesB), DiffText)
                                        HorizontalDivider(color = DiffBorder)
                                        DiffStatRow("Match", "%.1f%%".format(res.matchPct),
                                            if (res.matchPct > 90) DiffAdded else if (res.matchPct > 50) DiffChanged else DiffRemoved)
                                        DiffStatRow("Changed bytes", res.changedBytes.toString(), DiffChanged)
                                        DiffStatRow("Added bytes (B>A)", res.addedBytes.toString(), DiffAdded)
                                        DiffStatRow("Removed bytes (A>B)", res.removedBytes.toString(), DiffRemoved)
                                        DiffStatRow("Total compared", "${maxOf(res.totalBytesA, res.totalBytesB)} bytes", DiffMuted)
                                        if (res.truncated) {
                                            HorizontalDivider(color = DiffBorder)
                                            Text("⚠ Files exceed ${DIFF_LARGE_FILE / 1_048_576}MB — diff capped at first ${DIFF_MAX_ROWS * DIFF_BYTES_PER_ROW / 1024}KB",
                                                color = DiffChanged, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Row composable ────────────────────────────────────────────────────────────
@Composable
private fun DiffRowView(row: DiffRow) {
    val rowBg = if (row.hasDiff) Color(0xFF1A1A1A) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(rowBg).padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Offset
        Text(
            "%08X".format(row.offset),
            color = DiffMuted, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(60.dp)
        )
        // Hex A
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val bytesA = row.bytesA
            row.kinds.forEachIndexed { i, kind ->
                val byte = bytesA?.getOrNull(i)
                val color = when {
                    byte == null -> DiffRemoved
                    kind == DiffKind.SAME -> DiffText
                    kind == DiffKind.CHANGED -> DiffChanged
                    kind == DiffKind.REMOVED -> DiffRemoved
                    else -> DiffText
                }
                val bg = when (kind) {
                    DiffKind.CHANGED -> DiffChangedBg
                    DiffKind.REMOVED -> DiffRemovedBg
                    DiffKind.ADDED   -> Color.Transparent
                    else -> Color.Transparent
                }
                Box(Modifier.background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 1.dp)) {
                    Text(
                        byte?.toHex() ?: "--",
                        color = color, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        // Hex B
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val bytesB = row.bytesB
            row.kinds.forEachIndexed { i, kind ->
                val byte = bytesB?.getOrNull(i)
                val color = when {
                    byte == null -> DiffMuted
                    kind == DiffKind.SAME -> DiffText
                    kind == DiffKind.CHANGED -> DiffChanged
                    kind == DiffKind.ADDED   -> DiffAdded
                    else -> DiffText
                }
                val bg = when (kind) {
                    DiffKind.CHANGED -> DiffChangedBg
                    DiffKind.ADDED   -> DiffAddedBg
                    else -> Color.Transparent
                }
                Box(Modifier.background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 1.dp)) {
                    Text(
                        byte?.toHex() ?: "--",
                        color = color, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        // ASCII side-by-side
        Row(Modifier.width(80.dp)) {
            val asciiA = (0 until DIFF_BYTES_PER_ROW).map { i ->
                row.bytesA?.getOrNull(i)?.toPrintable() ?: ' '
            }.joinToString("")
            val asciiB = (0 until DIFF_BYTES_PER_ROW).map { i ->
                row.bytesB?.getOrNull(i)?.toPrintable() ?: ' '
            }.joinToString("")
            Text(asciiA, color = DiffMuted, fontSize = 8.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.weight(1f))
            Text("│", color = DiffBorder, fontSize = 8.sp,
                fontFamily = FontFamily.Monospace)
            Text(asciiB, color = DiffMuted, fontSize = 8.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.weight(1f))
        }
    }
    HorizontalDivider(color = DiffBorder.copy(alpha = 0.25f), thickness = 0.5.dp)
}

@Composable
private fun DiffFilePill(label: String, name: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xFF2D2D2D), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = DiffAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(name ?: "No file", color = if (name != null) DiffText else DiffMuted,
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DiffStatRow(key: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = DiffMuted, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(200.dp))
        Text(value, color = valueColor, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = DiffBorder.copy(alpha = 0.4f))
}

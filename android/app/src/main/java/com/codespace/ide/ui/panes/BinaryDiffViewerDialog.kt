package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.CompareArrows
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
import java.io.File

// ── Palette ─────────────────────────────────────────────────────────────────
private val BgPanel    = Color(0xFF1E1E1E)
private val BgHeader   = Color(0xFF252526)
private val BgRow      = Color(0xFF2A2A2A)
private val BgAdded    = Color(0xFF1B3A1B)
private val BgRemoved  = Color(0xFF3A1B1B)
private val BgChanged  = Color(0xFF1B2B3A)
private val BgSame     = Color.Transparent
private val TextMain   = Color(0xFFD4D4D4)
private val TextMuted  = Color(0xFF858585)
private val TextGreen  = Color(0xFF4EC9B0)
private val TextRed    = Color(0xFFFF6B6B)
private val TextBlue   = Color(0xFF569CD6)
private val Accent     = Color(0xFF007ACC)

// ── Data model ──────────────────────────────────────────────────────────────
private enum class ByteDiffKind { SAME, CHANGED, ONLY_LEFT, ONLY_RIGHT }

private data class DiffRow(
    val offset: Long,
    val leftBytes: ByteArray?,   // null = padding (row exists only on right)
    val rightBytes: ByteArray?,  // null = padding (row exists only on left)
    val kind: ByteDiffKind,
    val diffPositions: Set<Int> = emptySet() // indices in the 16-byte chunk that differ
)

// ── Binary diff algorithm ────────────────────────────────────────────────────
/**
 * Aligns two byte arrays by 16-byte blocks. Uses LCS on blocks to produce
 * a list of DiffRows suitable for side-by-side display.
 * Keeps memory low: reads entire files (limit 8 MB each) then processes in 16B chunks.
 */
private const val MAX_BYTES = 8 * 1024 * 1024   // 8 MB per side
private const val ROW_SIZE  = 16

private fun computeDiff(left: ByteArray, right: ByteArray): List<DiffRow> {
    val lChunks = left.toChunks()
    val rChunks = right.toChunks()

    // LCS table (block-level)
    val lLen = lChunks.size
    val rLen = rChunks.size
    val dp   = Array(lLen + 1) { IntArray(rLen + 1) }
    for (i in lLen downTo 0) {
        for (j in rLen downTo 0) {
            if (i == lLen || j == rLen) { dp[i][j] = 0; continue }
            dp[i][j] = if (lChunks[i].contentEquals(rChunks[j])) dp[i + 1][j + 1] + 1
                        else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }

    // Trace back LCS
    val rows = mutableListOf<DiffRow>()
    var i = 0; var j = 0
    var offsetL = 0L; var offsetR = 0L
    while (i < lLen || j < rLen) {
        when {
            i < lLen && j < rLen && lChunks[i].contentEquals(rChunks[j]) -> {
                rows += DiffRow(offsetL, lChunks[i], rChunks[j], ByteDiffKind.SAME)
                offsetL += ROW_SIZE; offsetR += ROW_SIZE; i++; j++
            }
            j < rLen && (i >= lLen || dp[i][j + 1] >= dp[i + 1][j]) -> {
                rows += DiffRow(offsetR, null, rChunks[j], ByteDiffKind.ONLY_RIGHT)
                offsetR += ROW_SIZE; j++
            }
            i < lLen && (j >= rLen || dp[i + 1][j] > dp[i][j + 1]) -> {
                rows += DiffRow(offsetL, lChunks[i], null, ByteDiffKind.ONLY_LEFT)
                offsetL += ROW_SIZE; i++
            }
            else -> break
        }
    }
    return mergeAdjacent(rows)
}

/** Adjacent ONLY_LEFT + ONLY_RIGHT pairs → CHANGED (keeps alignment tighter) */
private fun mergeAdjacent(rows: List<DiffRow>): List<DiffRow> {
    val out = mutableListOf<DiffRow>()
    var k = 0
    while (k < rows.size) {
        val cur = rows[k]
        if (cur.kind == ByteDiffKind.ONLY_LEFT && k + 1 < rows.size && rows[k + 1].kind == ByteDiffKind.ONLY_RIGHT) {
            val nxt = rows[k + 1]
            val diffs = mutableSetOf<Int>()
            val lb = cur.leftBytes!!; val rb = nxt.rightBytes!!
            for (p in 0 until minOf(lb.size, rb.size)) if (lb[p] != rb[p]) diffs += p
            out += DiffRow(cur.offset, cur.leftBytes, nxt.rightBytes, ByteDiffKind.CHANGED, diffs)
            k += 2
        } else {
            out += cur
            k++
        }
    }
    return out
}

private fun ByteArray.toChunks(): List<ByteArray> =
    (0 until size step ROW_SIZE).map { copyOfRange(it, minOf(it + ROW_SIZE, size)) }

// ── Formatting helpers ───────────────────────────────────────────────────────
private fun ByteArray.toHexRow(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun ByteArray.toAsciiRow(): String =
    joinToString("") { b -> val c = (b.toInt() and 0xFF); if (c in 0x20..0x7E) c.toChar().toString() else "." }

// ── Stats ────────────────────────────────────────────────────────────────────
private data class DiffStats(val same: Int, val changed: Int, val onlyLeft: Int, val onlyRight: Int) {
    val total get() = same + changed + onlyLeft + onlyRight
    val diffPct get() = if (total == 0) 0f else (changed + onlyLeft + onlyRight) * 100f / total
}
private fun List<DiffRow>.stats() = DiffStats(
    same      = count { it.kind == ByteDiffKind.SAME },
    changed   = count { it.kind == ByteDiffKind.CHANGED },
    onlyLeft  = count { it.kind == ByteDiffKind.ONLY_LEFT },
    onlyRight = count { it.kind == ByteDiffKind.ONLY_RIGHT },
)

// ── Public entry point ───────────────────────────────────────────────────────
@Composable
fun BinaryDiffViewerDialog(
    fileA: File,
    fileB: File? = null,
    onDismiss: () -> Unit,
    onPickFileB: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BgPanel,
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Header bar
                Row(
                    Modifier.fillMaxWidth().background(BgHeader).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CompareArrows, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Binary Diff", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Spacer(Modifier.width(8.dp))
                    Text(fileA.name, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (fileB != null) {
                        Icon(Icons.Default.CompareArrows, null, tint = TextMuted, modifier = Modifier.size(14.dp).padding(horizontal = 4.dp))
                        Text(fileB.name, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    if (onPickFileB != null) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onPickFileB, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.FolderOpen, "Pick second file", tint = Accent, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                }

                if (fileB == null) {
                    // No second file selected yet
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.CompareArrows, null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Select a second file to compare", color = TextMuted, fontSize = 14.sp)
                        if (onPickFileB != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onPickFileB, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Browse…", fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    // Compute diff on IO
                    var rows    by remember(fileA, fileB) { mutableStateOf<List<DiffRow>>(emptyList()) }
                    var loading by remember(fileA, fileB) { mutableStateOf(true) }
                    var error   by remember(fileA, fileB) { mutableStateOf<String?>(null) }

                    LaunchedEffect(fileA, fileB) {
                        loading = true; error = null
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val lBytes = fileA.readBytes().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it }
                                val rBytes = fileB.readBytes().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it }
                                rows = computeDiff(lBytes, rBytes)
                            } catch (e: Exception) {
                                error = e.message ?: "Unknown error"
                            }
                        }
                        loading = false
                    }

                    when {
                        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Accent, modifier = Modifier.size(36.dp))
                        }
                        error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("Error: $error", color = TextRed, fontSize = 13.sp)
                        }
                        else -> {
                            val stats = remember(rows) { rows.stats() }
                            DiffBody(fileA.name, fileB.name, rows, stats)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffBody(nameA: String, nameB: String, rows: List<DiffRow>, stats: DiffStats) {
    val listState = rememberLazyListState()

    // ── Stats strip
    Row(
        Modifier.fillMaxWidth().background(BgHeader).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatChip("=${stats.same}",        TextMuted)
        StatChip("~${stats.changed}",     TextBlue)
        StatChip("+${stats.onlyRight}",   TextGreen)
        StatChip("-${stats.onlyLeft}",    TextRed)
        Spacer(Modifier.weight(1f))
        Text("${stats.diffPct.let { "%.1f".format(it) }}% differ", fontSize = 10.sp, color = TextMuted)
    }

    // ── Column headers
    Row(
        Modifier.fillMaxWidth().background(BgRow).padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text("Offset", fontSize = 9.sp, color = TextMuted, modifier = Modifier.width(72.dp))
        Text(nameA, fontSize = 9.sp, color = TextMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(nameB, fontSize = 9.sp, color = TextMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = Color(0xFF3C3C3C))

    // ── Diff rows
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { idx, _ -> idx }) { _, row ->
            BinaryDiffRow(row)
        }
    }
}

@Composable
private fun BinaryDiffRow(row: DiffRow) {
    val bg = when (row.kind) {
        ByteDiffKind.SAME       -> BgSame
        ByteDiffKind.CHANGED    -> BgChanged
        ByteDiffKind.ONLY_LEFT  -> BgRemoved
        ByteDiffKind.ONLY_RIGHT -> BgAdded
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        // Offset
        Text(
            "%08X".format(row.offset),
            fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp)
        )
        // Left side
        SideCell(row.leftBytes, row.diffPositions, row.kind, isLeft = true)
        Spacer(Modifier.width(4.dp))
        // Right side
        SideCell(row.rightBytes, row.diffPositions, row.kind, isLeft = false)
    }
}

@Composable
private fun RowScope.SideCell(
    bytes: ByteArray?,
    diffPos: Set<Int>,
    kind: ByteDiffKind,
    isLeft: Boolean,
) {
    val baseColor = when (kind) {
        ByteDiffKind.ONLY_LEFT  -> if (isLeft) TextRed else TextMuted
        ByteDiffKind.ONLY_RIGHT -> if (isLeft) TextMuted else TextGreen
        ByteDiffKind.CHANGED    -> if (isLeft) TextRed else TextGreen
        ByteDiffKind.SAME       -> TextMain
    }
    if (bytes == null) {
        // padding — show dashes
        Text("-- -- -- -- -- -- -- -- -- -- -- -- -- -- -- --  ................",
            fontSize = 9.sp, color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()))
    } else {
        val hex   = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val ascii = bytes.joinToString("") { b -> val c = (b.toInt() and 0xFF); if (c in 0x20..0x7E) c.toChar().toString() else "." }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Text(
                hex + "  " + ascii,
                fontSize = 9.sp,
                color = baseColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun StatChip(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 10.dp)
    )
}

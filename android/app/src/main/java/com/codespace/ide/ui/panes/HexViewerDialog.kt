package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.RandomAccessFile

private const val HEX_VIEW_CAP = 65536

@Composable
fun HexViewerDialog(file: File, onDismiss: () -> Unit) {
    val orientation = LocalConfiguration.current.orientation
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var totalSize by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        try {
            totalSize = file.length()
            val readLen = minOf(totalSize, HEX_VIEW_CAP.toLong()).toInt()
            truncated = totalSize > readLen
            val buf = ByteArray(readLen)
            RandomAccessFile(file, "r").use { it.readFully(buf) }
            rows = buf.toList().chunked(16).mapIndexed { i, chunk ->
                val offset = (i * 16)
                val hex = chunk.joinToString(" ") { b -> "%02X".format(b) }.padEnd(47)
                val ascii = chunk.joinToString("") { b ->
                    val c = b.toInt().toChar()
                    if (c.code in 32..126) c.toString() else "."
                }
                "%08X  %s  %s".format(offset, hex, ascii)
            }
        } catch (e: Exception) {
            error = "Couldn't read file: ${e.message}"
        }
    }

    key(orientation) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("$totalSize bytes" + if (truncated) " (showing first $HEX_VIEW_CAP)" else "",
                            color = Color(0xFF888888), fontSize = 10.sp)
                    }
                    Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                        modifier = Modifier.size(20.dp).clickable { onDismiss() })
                }
                HorizontalDivider(color = Color(0xFF3A3A3A))
                Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
                    when {
                        error != null -> Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                        rows.isEmpty() -> CircularProgressIndicator(color = Color(0xFF569CD6), modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                            items(rows) { row ->
                                Text(row, color = Color(0xFF4EC9B0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

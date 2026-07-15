package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * StringsViewerDialog — extracts printable ASCII/UTF-8 strings from binary files.
 * Equivalent to the Unix `strings` command. Shows offset + string content.
 * Phase 21 Step 4 — fallback viewer for binary files.
 */

data class ExtractedString(val offset: Long, val text: String, val isAscii: Boolean)

@Composable
fun StringsViewerDialog(file: File, onDismiss: () -> Unit) {
    var strings by remember { mutableStateOf<List<ExtractedString>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchText by remember { mutableStateOf("") }
    var minLen by remember { mutableStateOf(4) }

    LaunchedEffect(file) {
        loading = true
        strings = withContext(Dispatchers.IO) { extractStrings(file, minLen) }
        loading = false
    }

    val filtered = remember(strings, searchText) {
        if (searchText.isBlank()) strings
        else strings.filter { it.text.contains(searchText, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = Color(0xFF1E1E1E)) {
            Column(Modifier.width(360.dp).heightIn(max = 500.dp).padding(12.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strings: ${file.name}", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text("${strings.size}", color = Color(0xFF858585), fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Close, null, tint = Color(0xFF858585), modifier = Modifier.size(18.dp).clickable { onDismiss() })
                }

                Spacer(Modifier.height(8.dp))

                // Search bar
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, tint = Color(0xFF858585), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f).background(Color(0xFF2D2D2D)).padding(horizontal = 8.dp, vertical = 4.dp),
                        decorationBox = { inner ->
                            if (searchText.isEmpty()) { Text("Filter...", color = Color(0xFF666666), fontSize = 12.sp) }
                            inner()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF569CD6), modifier = Modifier.size(20.dp))
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("No strings found", color = Color(0xFF858585), fontSize = 12.sp)
                    }
                } else {
                    // Column header
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text("Offset", color = Color(0xFF569CD6), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(70.dp))
                        Text("String", color = Color(0xFF569CD6), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = Color(0xFF333333))

                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filtered) { s ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)) {
                                Text("0x${s.offset.toString(16).padStart(8, '0')}", color = Color(0xFF858585), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(70.dp))
                                Text(s.text.take(200), color = if (s.isAscii) Color(0xFFCCCCCC) else Color(0xFFCE9178), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
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

private fun extractStrings(file: File, minLen: Int): List<ExtractedString> {
    val result = mutableListOf<ExtractedString>()
    val buf = ByteArray(8192)
    val sb = StringBuilder()
    var stringStart = 0L
    var offset = 0L

    try {
        val raf = RandomAccessFile(file, "r")
        while (true) {
            val read = raf.read(buf)
            if (read <= 0) break
            for (i in 0 until read) {
                val b = buf[i]
                // Printable ASCII (32-126) or tab/newline
                if (b in 32..126 || b == 9.toByte() || b == 10.toByte() || b == 13.toByte()) {
                    if (sb.isEmpty()) stringStart = offset
                    sb.append(b.toInt().toChar())
                } else {
                    if (sb.length >= minLen) {
                        val text = sb.toString().trim()
                        if (text.length >= minLen) {
                            result.add(ExtractedString(stringStart, text, true))
                        }
                    }
                    sb.clear()
                }
                offset++
            }
        }
        // Last string
        if (sb.length >= minLen) {
            val text = sb.toString().trim()
            if (text.length >= minLen) {
                result.add(ExtractedString(stringStart, text, true))
            }
        }
        raf.close()
    } catch (e: Exception) {
        // Return whatever we have
    }

    return result
}

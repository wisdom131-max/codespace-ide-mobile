package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.FileIndexer
import kotlinx.coroutines.delay

/**
 * P9-1: Symbol Search overlay — VS Code "Go to Symbol in Workspace" (Ctrl+T).
 * Shows a floating search box at the top of the editor; typing filters
 * the workspace symbol index and tapping a result jumps to that file+line.
 */
@Composable
fun SymbolSearchPanel(
    onNavigate: (filePath: String, line: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val indexerState = remember { mutableStateOf(FileIndexer.getState()) }
    LaunchedEffect(Unit) {
        while (true) {
            indexerState.value = FileIndexer.getState()
            delay(500)
        }
    }

    val results = remember(query) { FileIndexer.search(query) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252526), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        // Search input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Search, null, tint = Color(0xFF808080), modifier = Modifier.size(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search symbols in workspace...", fontSize = 12.sp, color = Color(0xFF808080)) },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD4D4D4),
                ),
                trailingIcon = {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF808080), modifier = Modifier.size(14.dp))
                    }
                },
            )
        }

        // Indexing status
        if (indexerState.value.isIndexing) {
            Text(
                "Indexing ${indexerState.value.indexedFiles}/${indexerState.value.totalFiles} files...",
                modifier = Modifier.padding(vertical = 4.dp),
                color = Color(0xFF808080),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else if (indexerState.value.isComplete) {
            Text(
                "${indexerState.value.totalSymbols} symbols indexed",
                modifier = Modifier.padding(vertical = 2.dp),
                color = Color(0xFF6A9955),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Results
        if (query.isNotBlank() && results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            ) {
                items(results) { sym ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(sym.filePath, sym.line); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Kind badge
                        val (label, tint) = when (sym.kind) {
                            "class"     -> "C" to Color(0xFF4EC9B0)
                            "function"  -> "f" to Color(0xFFDCDCAA)
                            "variable"  -> "v" to Color(0xFF9CDCFE)
                            "interface"  -> "I" to Color(0xFFB8D7A3)
                            "enum"      -> "E" to Color(0xFFB5CEA8)
                            else        -> "?" to Color(0xFF808080)
                        }
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(tint.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = tint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            sym.name,
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            sym.fileName,
                            color = Color(0xFF808080),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Text(
                            "L${sym.line}",
                            color = Color(0xFF6A9955),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        } else if (query.isNotBlank() && results.isEmpty() && !indexerState.value.isIndexing) {
            Text(
                "No symbols found for '$query'",
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color(0xFF808080),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

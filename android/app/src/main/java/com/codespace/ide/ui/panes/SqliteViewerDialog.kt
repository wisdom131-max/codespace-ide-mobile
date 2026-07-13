package com.codespace.ide.ui.panes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqliteViewerDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation

    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var columns by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // Copy file to context.cacheDir and open SQLite connection
    LaunchedEffect(file) {
        try {
            val cacheFile = File(context.cacheDir, "temp_sqlite_viewer_${System.currentTimeMillis()}.db")
            file.inputStream().use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val db = SQLiteDatabase.openDatabase(cacheFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tableList = mutableListOf<String>()
            val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            if (cursor.moveToFirst()) {
                do {
                    val tableName = cursor.getString(0)
                    if (tableName != "android_metadata" && tableName != "sqlite_sequence") {
                        tableList.add(tableName)
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()
            db.close()
            cacheFile.delete()

            tables = tableList
            if (tableList.isNotEmpty()) {
                selectedTable = tableList.first()
            } else {
                error = "No user tables found in database."
            }
        } catch (e: Exception) {
            error = "Failed to open SQLite database: ${e.message}"
        }
    }

    // Load first 200 rows of selected table
    LaunchedEffect(file, selectedTable) {
        val table = selectedTable ?: return@LaunchedEffect
        try {
            val cacheFile = File(context.cacheDir, "temp_sqlite_viewer_${System.currentTimeMillis()}.db")
            file.inputStream().use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val db = SQLiteDatabase.openDatabase(cacheFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT * FROM `$table` LIMIT 200", null)
            val colList = cursor.columnNames.toList()
            columns = colList

            val rowList = mutableListOf<Map<String, String>>()
            if (cursor.moveToFirst()) {
                do {
                    val rowMap = mutableMapOf<String, String>()
                    for (i in colList.indices) {
                        val colName = colList[i]
                        val value = try {
                            cursor.getString(i) ?: "NULL"
                        } catch (e: Exception) {
                            "BLOB/Error"
                        }
                        rowMap[colName] = value
                    }
                    rowList.add(rowMap)
                } while (cursor.moveToNext())
            }
            cursor.close()
            db.close()
            cacheFile.delete()

            rows = rowList
        } catch (e: Exception) {
            error = "Failed to load table data: ${e.message}"
        }
    }

    key(orientation) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                // Header
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("SQLite Database Viewer", color = Color(0xFF888888), fontSize = 10.sp)
                    }
                    Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                        modifier = Modifier.size(20.dp).clickable { onDismiss() })
                }
                HorizontalDivider(color = Color(0xFF3A3A3A))

                if (error != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                    }
                } else if (tables.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF569CD6))
                    }
                } else {
                    // Table Selection Dropdown / Tab
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2D2D30))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF3E3E42), RoundedCornerShape(4.dp))
                                .clickable { isDropdownExpanded = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Table: ${selectedTable ?: ""}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                        }

                        DropdownMenu(
                            expanded = isDropdownExpanded,
                            onDismissRequest = { isDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF252526))
                        ) {
                            tables.forEach { tableName ->
                                DropdownMenuItem(
                                    text = { Text(tableName, color = Color.White) },
                                    onClick = {
                                        selectedTable = tableName
                                        isDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF3A3A3A))

                    // LazyColumn showing rows as horizontally scrollable cards
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D0D))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rows) { rowMap ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    columns.forEach { colName ->
                                        Column {
                                            Text(
                                                text = colName,
                                                color = Color(0xFF569CD6),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = rowMap[colName] ?: "NULL",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
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
